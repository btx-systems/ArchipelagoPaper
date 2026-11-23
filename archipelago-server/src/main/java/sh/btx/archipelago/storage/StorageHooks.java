package sh.btx.archipelago.storage;

import ca.spottedleaf.concurrentutil.executor.thread.BalancedPrioritisedThreadPool;
import ca.spottedleaf.moonrise.common.util.MoonriseCommon;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import com.google.common.base.Preconditions;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventRunner;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.util.ServerShutdownThread;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * What patches call. Everything here does what vanilla does, or nothing, when no storage is used.
 */
@NullMarked
public final class StorageHooks {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private static final Map<ResourceKey<Level>, StoredWorld> WORLDS = new ConcurrentHashMap<>();
    private static volatile @Nullable WorldStorageProvider provider;
    private static volatile @Nullable PlayerStorageBridge players;
    private static volatile @Nullable StoredMaps maps;
    private static @Nullable StorageMarker marker;
    private static volatile boolean used; // unlike inUse, stays true

    private StorageHooks() {
    }

    // startup

    /**
     * Runs the event, before anything that can be replaced is read.
     */
    public static void initialize(final LevelStorageSource.LevelStorageAccess access) throws IOException {
        final StorageMarker marker = new StorageMarker(access.getLevelDirectory().path());
        StorageHooks.marker = marker;

        LifecycleEventRunner.INSTANCE.callStaticRegistrarEvent(LifecycleEvents.STORAGE, new PaperStorageRegistrar(
            (registered, plugin) -> {
                provider = registered;
                LOGGER.info("{} provides world storage", plugin);
            },
            (registered, plugin) -> {
                players = new PlayerStorageBridge(registered);
                used = true;
                LOGGER.info("{} provides player storage", plugin);
            },
            (registered, plugin) -> {
                maps = StoredMaps.forServer(registered);
                used = true;
                LOGGER.info("{} provides map storage", plugin);
            }
        ), BootstrapContext.class);

        if (players != null) {
            marker.add(StorageMarker.PLAYERS);
        } else if (marker.contains(StorageMarker.PLAYERS)) {
            throw missing(marker, "players");
        }
        if (maps != null) {
            marker.add(StorageMarker.MAPS);
        } else if (marker.contains(StorageMarker.MAPS)) {
            throw missing(marker, "maps");
        }
        if (provider == null && !marker.worlds().isEmpty()) {
            throw missing(marker, "worlds " + marker.worlds());
        }
    }

    private static StorageException missing(final StorageMarker marker, final String what) {
        return new StorageException(
            "The " + what + " of this server are kept in storage from a plugin, and no plugin provided it. Refusing to continue, "
                + "as they would be replaced with new ones. Fix or reinstall that plugin. To use built-in storage instead, edit " + marker.file().toAbsolutePath()
        );
    }

    public static void openDefaultWorld(final LevelStorageSource.LevelStorageAccess access, final ResourceKey<Level> dimension) {
        if (WORLDS.containsKey(dimension)) {
            return; // the overworld, which is opened early for its gen settings
        }
        final StoredWorld world = open(dimension, null);
        if (world == null) {
            return;
        }
        try {
            if (marker != null) {
                marker.add(dimension.identifier().toString());
            }
        } catch (final IOException ex) {
            throw new StorageException("Could not record that world '" + dimension.identifier() + "' uses storage", ex);
        }
        final Path folder = access.getDimensionPath(dimension);
        if (Files.isDirectory(folder.resolve("region"))) {
            LOGGER.warn("World '{}' uses storage from a plugin. The chunks in {} are not used", dimension.identifier(), folder);
        }
    }

    private static @Nullable StoredWorld open(final ResourceKey<Level> dimension, final @Nullable WorldStorage requested) {
        final Identifier id = dimension.identifier();
        // before asking the provider, and even without storage: it would share that of the world being loaded
        Preconditions.checkState(!WORLDS.containsKey(dimension), "World '%s' is already being loaded", id);
        WorldStorage storage = requested;
        final WorldStorageProvider provider = StorageHooks.provider;
        if (storage == null && provider != null) {
            try {
                storage = provider.open(StoredWorld.key(id));
            } catch (final Throwable thr) {
                throw new StorageException("Could not open storage of world '" + id + "'", thr);
            }
        }
        if (storage == null) {
            if (marker != null && marker.contains(id.toString())) {
                throw missing(marker, "chunks and data of world '" + id + "'");
            }
            return null;
        }
        for (final StoredWorld open : WORLDS.values()) {
            Preconditions.checkArgument(open.storage() != storage, "That storage is already used by world '%s'. Each world needs its own", open.id());
        }
        final StoredWorld world = new StoredWorld(id, storage, closed -> WORLDS.remove(dimension, closed));
        Preconditions.checkState(WORLDS.putIfAbsent(dimension, world) == null, "World '%s' is already being loaded", id);
        used = true;
        LOGGER.info("World '{}' is stored by {}", id, storage.getClass().getName());
        return world;
    }

    /**
     * Startup failed before there was a server to save and stop.
     */
    public static void abort() {
        closeAll();
    }

    // plugin-created worlds

    public static void checkExistingWorld(final World existing, final WorldCreator creator) {
        final WorldStorage requested = creator.storage();
        if (requested == null) {
            return;
        }
        final StoredWorld stored = ((CraftWorld) existing).getHandle().storedWorld;
        Preconditions.checkArgument(stored != null && stored.storage() == requested, "World %s is already loaded, with other storage", existing.key());
    }

    /**
     * One call of createWorld, which only cleans up what it opened itself.
     */
    public static final class Creation {

        private @Nullable ResourceKey<Level> dimension;
        private @Nullable StoredWorld world;

        public void open(final ResourceKey<Level> dimension, final WorldCreator creator) {
            final StoredWorld opened = StorageHooks.open(dimension, creator.storage());
            this.dimension = dimension;
            this.world = opened;
            if (opened != null && creator.storage() == null && marker != null) { // from the provider
                try {
                    marker.add(StorageMarker.OTHER_WORLDS);
                } catch (final IOException ex) {
                    throw new StorageException("Could not record that world '" + dimension.identifier() + "' uses storage", ex);
                }
            }
        }

        public void end(final CraftServer server, final @Nullable World created) {
            final StoredWorld world = this.world;
            if (world == null) {
                return;
            }
            final ServerLevel level = server.getServer().getLevel(this.dimension);
            if (level == null || level.storedWorld != world) {
                world.closeUnusedOrAwait(); // failed before it was a world, or is unloaded already
            } else if (created != null) {
                // so write-through storages have the UUID and seed before any chunk. Staged ones can still discard
                level.getDataStorage().scheduleSave();
            } else if (!server.unloadWorld(level.getWorld(), false)) {
                LOGGER.error("Creating world '{}' failed, but it could not be unloaded again, so is still loaded with its storage", world.id());
            }
        }
    }

    public static void checkUniqueId(final MinecraftServer server, final ResourceKey<Level> dimension, final UUID id) {
        if (!WORLDS.containsKey(dimension)) {
            return;
        }
        final World other = server.server.getWorld(id);
        if (other != null) {
            throw new IllegalStateException(
                "World '" + dimension.identifier() + "' has the same UUID as " + other.key() + ". When cloning a world, don't copy " + WorldDataKeys.METADATA
            );
        }
    }

    // lookups

    public static @Nullable StoredWorld world(final ResourceKey<Level> dimension) {
        return WORLDS.get(dimension);
    }

    public static boolean isStored(final ResourceKey<Level> dimension) {
        return WORLDS.containsKey(dimension);
    }

    public static SavedDataStorage savedData(final ResourceKey<Level> dimension, final Path folder, final DataFixer fixer, final HolderLookup.Provider registries) {
        final StoredWorld world = WORLDS.get(dimension);
        return world == null ? new SavedDataStorage(folder, fixer, registries) : world.savedData(folder, registries);
    }

    /**
     * An error result only ever means that there is none, failing to read throws.
     */
    public static <T extends SavedData> DataResult<T> readExistingSavedData(final StoredWorld world, final SavedDataType<T> type, final HolderLookup.Provider registries) {
        final T data = StorageSavedDataStorage.read(world, type, registries);
        return data == null ? DataResult.error(() -> type + " is not in the storage of world '" + world.id() + "'") : DataResult.success(data);
    }

    public static @Nullable WorldGenSettings overworldGenSettings(final HolderLookup.Provider registries) {
        final StoredWorld world = WORLDS.get(Level.OVERWORLD);
        return world == null ? null : StorageSavedDataStorage.read(world, WorldGenSettings.TYPE, registries);
    }

    public static boolean hasWorldData(final ResourceKey<Level> dimension, final boolean hasFolder) {
        final StoredWorld world = WORLDS.get(dimension);
        if (world == null) {
            return hasFolder;
        }
        try {
            return world.readData(WorldDataKeys.WORLD_GEN_SETTINGS) != null;
        } catch (final IOException ex) {
            throw new StorageException("Could not load world '" + dimension.identifier() + "'", ex);
        }
    }

    /**
     * @return where to keep the world's config, or null to use the file
     */
    public static @Nullable StoredWorld configStorage(final Identifier dimension, final Path file) {
        if (WORLDS.isEmpty() || Files.exists(file)) {
            return null;
        }
        return WORLDS.get(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    // chunk system

    public static MoonriseRegionFileIO.RegionDataController controller(
        final @Nullable StoredWorld world, final MoonriseRegionFileIO.RegionDataController vanilla, final ChunkTaskScheduler scheduler
    ) {
        return world == null ? vanilla : new StorageDataController(world, vanilla, scheduler.ioExecutor, scheduler.compressionExecutor);
    }

    public static BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue ioExecutor(final ServerLevel level) {
        return (level.storedWorld == null ? MoonriseCommon.SERVER_IO_GROUP : StorageThreads.GROUP).createExecutor();
    }

    /**
     * Cancels the write that vanilla would replace the stored entities with.
     */
    public static void retainEntities(final ServerLevel level, final int chunkX, final int chunkZ, final CompoundTag entities, final Throwable cause) {
        final StoredWorld world = level.storedWorld;
        if (world != null) {
            world.retainEntities(chunkX, chunkZ, entities);
            throw new RetainedWriteException(cause);
        }
    }

    static boolean wasRetained(final Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof RetainedWriteException) {
                return true;
            }
        }
        return false;
    }

    private static final class RetainedWriteException extends RuntimeException {

        RetainedWriteException(final Throwable cause) {
            super("Not writing, the entities will be merged with the stored ones later", cause);
        }
    }

    public static boolean hidesEntities(final ServerLevel level, final int chunkX, final int chunkZ) {
        final StoredWorld world = level.storedWorld;
        return world != null && world.hidesEntities(chunkX, chunkZ);
    }

    public static void notSerialized(final ServerLevel level, final MoonriseRegionFileIO.RegionFileType type, final int chunkX, final int chunkZ) {
        final StoredWorld world = level.storedWorld;
        if (world != null) {
            world.notCaptured(StorageDataController.dataType(type), chunkX, chunkZ);
        }
    }

    /**
     * @return what loading should use of what was read
     */
    public static @Nullable CompoundTag loadable(
        final ServerLevel level, final MoonriseRegionFileIO.RegionFileType type, final int chunkX, final int chunkZ, final @Nullable CompoundTag read
    ) {
        final StoredWorld world = level.storedWorld;
        return read != null && world != null && world.hidesStored(StorageDataController.dataType(type), chunkX, chunkZ) ? null : read;
    }

    /**
     * Taken before a transient merge starts reading, as scheduling its write replaces what was stored.
     */
    public static boolean mergesIntoNothing(final ServerLevel level, final int chunkX, final int chunkZ) {
        final StoredWorld world = level.storedWorld;
        return world != null && world.hidesStored(WorldStorage.ChunkDataType.ENTITIES, chunkX, chunkZ);
    }

    public static void validateChunk(final ServerLevel level, final CompoundTag chunk) {
        if (level.storedWorld != null && chunk.getString("Status").isPresent() && chunk.read("Status", ChunkStatus.CODEC).isEmpty()) {
            throw new StorageException("Unknown chunk status " + chunk.getString("Status").get());
        }
    }

    public static void loadFailed(final ServerLevel level, final MoonriseRegionFileIO.RegionFileType type, final int chunkX, final int chunkZ) {
        final StoredWorld world = level.storedWorld;
        if (world != null) {
            world.quarantine(StorageDataController.dataType(type), chunkX, chunkZ);
        }
    }

    // unloading

    /**
     * @return false if the world must stay loaded
     */
    public static boolean checkpointed(final ServerLevel level) {
        final StoredWorld world = level.storedWorld;
        return world == null || world.awaitLastSave();
    }

    public static boolean isOpen(final ServerLevel level) {
        final StoredWorld world = level.storedWorld;
        return world != null && !world.isClosing();
    }

    public static boolean closedCleanly(final ServerLevel level) {
        final StoredWorld world = level.storedWorld;
        return world == null || world.awaitClose();
    }

    // players

    public static @Nullable PlayerStorageBridge players() {
        return players;
    }

    public static PlayerDataStorage createPlayerDataStorage(final LevelStorageSource.LevelStorageAccess access, final DataFixer fixer) {
        final PlayerStorageBridge players = StorageHooks.players;
        return players == null ? new PlayerDataStorage(access, fixer) : new StoragePlayerDataStorage(access, fixer, players);
    }

    public static @Nullable ServerStatsCounter createStatsCounter(final MinecraftServer server, final UUID id) {
        final PlayerStorageBridge players = StorageHooks.players;
        return players == null ? null : new StorageStatsCounter(server, id, players);
    }

    public static void retryPlayerSaves() {
        final PlayerStorageBridge players = StorageHooks.players;
        if (players != null) {
            players.retryFailedSaves();
        }
    }

    // maps

    public static @Nullable StoredMaps maps() {
        return maps;
    }

    /**
     * Before the empty map is used up.
     */
    public static boolean canCreateMap() {
        final StoredMaps maps = StorageHooks.maps;
        return maps == null || maps.reserveId();
    }

    public static void saveMaps(final boolean flush) {
        final StoredMaps maps = StorageHooks.maps;
        if (maps != null) {
            maps.save(flush);
        }
    }

    public static void worldLoaded(final ServerLevel level) {
        final StoredMaps maps = StorageHooks.maps;
        if (maps != null) {
            maps.worldLoaded(level.dimension());
        }
    }

    /**
     * @return false if the world must stay loaded, as the next server to load it would show its maps as they were
     */
    public static boolean mapsSaved(final ServerLevel level) {
        final StoredMaps maps = StorageHooks.maps;
        return maps == null || maps.saveWorld(level.dimension());
    }

    // lifetime

    /**
     * Plugins are disabled before the last save, so their classes have to stay loadable.
     */
    public static boolean inUse() {
        return provider != null || players != null || maps != null || !WORLDS.isEmpty();
    }

    /**
     * A signal stops the server from a shutdown hook if main is busy for over a second, as it is while it waits for a
     * storage to save. System.exit in a hook never returns, and it isn't needed there.
     */
    public static boolean skipExit() {
        return used && Thread.currentThread() instanceof ServerShutdownThread;
    }

    /**
     * Runs after the last save of a stopping server. Worlds that are still open never finished loading.
     */
    public static void shutdown() {
        closeAll();
    }

    private static void closeAll() {
        for (final StoredWorld world : List.copyOf(WORLDS.values())) {
            world.closeUnusedOrAwait(); // main can still be saving one it is unloading
        }
        final PlayerStorageBridge players = StorageHooks.players;
        if (players != null) {
            players.close();
        }
        final StoredMaps maps = StorageHooks.maps;
        if (maps != null) {
            maps.close();
        }
    }
}
