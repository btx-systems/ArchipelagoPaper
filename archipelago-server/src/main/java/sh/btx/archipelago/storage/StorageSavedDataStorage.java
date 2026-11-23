package sh.btx.archipelago.storage;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.key.Key;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Per-world saved data in a {@link WorldStorage}. Unlike vanilla's, failing to read throws instead of becoming
 * default data that replaces what is stored, joining waits for writes that are already running, and a failed write
 * doesn't stop all later ones.
 */
@NullMarked
final class StorageSavedDataStorage extends SavedDataStorage {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final int DEFAULT_DATA_VERSION = 1343; // as in SavedDataStorage

    private final StoredWorld world;
    private final HolderLookup.Provider registries;
    private CompletableFuture<?> writes = CompletableFuture.completedFuture(null);
    private volatile boolean failedToEncode;

    StorageSavedDataStorage(final StoredWorld world, final Path unusedFolder, final HolderLookup.Provider registries) {
        super(unusedFolder, DataFixers.getDataFixer(), registries);
        this.world = world;
        this.registries = registries;
    }

    // everything else is read while the world is created. These would be read while ticking, where throwing crashes
    void preloadLazyData() {
        this.get(WorldBorder.TYPE);
        this.get(WanderingTraderData.TYPE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends SavedData> @Nullable T get(final SavedDataType<T> type) {
        Optional<SavedData> data = this.cache.get(type);
        if (data == null) {
            data = Optional.ofNullable(read(this.world, type, this.registries));
            this.cache.put(type, data);
        }
        return (T) data.orElse(null);
    }

    /**
     * @return the data, or null if none is stored
     * @throws StorageException if it could not be read
     */
    static <T extends SavedData> @Nullable T read(final StoredWorld world, final SavedDataType<T> type, final HolderLookup.Provider registries) {
        final Key key = StoredWorld.key(type.id());
        try {
            final StoredData stored = world.readData(key);
            if (stored == null) {
                return null;
            }
            final CompoundTag saved = StoredNbt.decode(stored);
            final CompoundTag upgraded = type.dataFixType().update(
                DataFixers.getDataFixer(), saved, NbtUtils.getDataVersion(saved, DEFAULT_DATA_VERSION), SharedConstants.getCurrentVersion().dataVersion().version()
            );
            final Tag data = upgraded.get("data");
            if (data == null) {
                throw new IOException("There is no 'data' tag");
            }
            final DataResult<T> parsed = type.codec().parse(registries.createSerializationContext(NbtOps.INSTANCE), data);
            if (type.equals(ServerClockManager.TYPE)) {
                // keyed by a datapack registry, so a removed datapack would keep every world from loading
                return parsed.resultOrPartial(error -> LOGGER.warn("Dropping clocks that don't exist any more from world '{}': {}", world.id(), error))
                    .orElseThrow(() -> new IOException("It could not be parsed"));
            }
            // not partially: what is missing from it would be erased by the next save
            return parsed.getOrThrow(error -> new IOException("It could not be parsed: " + error));
        } catch (final Exception | StackOverflowError ex) {
            throw new StorageException("Could not load " + key + " of world '" + world.id() + "'", ex);
        }
    }

    @Override
    public CompletableFuture<?> scheduleSave() {
        if (this.world.isClosed()) {
            return this.writes;
        }
        // encoded here, as the tags can share mutable values with the live world
        final Map<Key, StoredData> toWrite = new HashMap<>();
        final RegistryOps<Tag> ops = this.registries.createSerializationContext(NbtOps.INSTANCE);
        this.failedToEncode = false;
        this.cache.forEach((type, cached) -> cached.filter(SavedData::isDirty).ifPresent(data -> {
            try {
                toWrite.put(StoredWorld.key(type.id()), StoredNbt.encode(encode(type, data, ops)));
                data.setDirty(false);
            } catch (final Throwable thr) { // stays dirty. Anything thrown here would lose what was encoded before it
                this.failedToEncode = true;
                LOGGER.error("Could not encode {} of world '{}'", type, this.world.id(), thr);
            }
        }));
        if (!toWrite.isEmpty()) {
            this.writes = this.writes
                .exceptionally(thr -> null)
                .thenRunAsync(() -> toWrite.forEach(this.world::writeData), Util.ioPool());
        }
        return this.writes;
    }

    @SuppressWarnings("unchecked")
    private static <T extends SavedData> CompoundTag encode(final SavedDataType<T> type, final SavedData data, final RegistryOps<Tag> ops) {
        final Codec<T> codec = type.codec();
        final CompoundTag tag = new CompoundTag();
        tag.put("data", codec.encodeStart(ops, (T) data).getOrThrow());
        NbtUtils.addCurrentDataVersion(tag);
        return tag;
    }

    @Override
    public void saveAndJoin() {
        this.scheduleSave();
        this.awaitWrites();
    }

    boolean failedToEncode() {
        return this.failedToEncode;
    }

    void awaitWrites() {
        StorageWait.await(this.writes, () -> LOGGER.warn("Still waiting for storage of world '{}' to write its data", this.world.id()));
    }

    @Override
    public void close() {
        // must not save, the world's StoredWorld decides whether changes are saved or discarded
        this.awaitWrites();
    }
}
