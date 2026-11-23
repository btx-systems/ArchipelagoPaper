package sh.btx.archipelago.storage;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The server's side of a {@link MapStorage}, which replaces the maps in the server's saved data.
 * <p>
 * A map is written by the server that has its world loaded, and read again by one that gets the world. Maps are kept
 * as the same objects while they are read again, as plugins and players hold them. Methods other than
 * {@link #nextId()} and {@link #close()} are called on the main thread.
 */
@NullMarked
public final class StoredMaps {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final int DEFAULT_DATA_VERSION = 1343; // as in SavedDataStorage
    private static final long RETRY_MISSING_NANOS = TimeUnit.SECONDS.toNanos(10L); // another server may create it
    private static final long RETRY_UNREADABLE_NANOS = TimeUnit.SECONDS.toNanos(60L);
    private static final long REFRESH_NANOS = TimeUnit.SECONDS.toNanos(60L);
    private static final long CLOSING_RETRY_NANOS = TimeUnit.SECONDS.toNanos(30L);
    static final int RESERVED_IDS = 8;

    private static final class Entry {
        @Nullable MapItemSavedData data; // null when there is none, or it couldn't be read
        boolean unreadable;
        long checkedAt;
        @Nullable CompletableFuture<@Nullable StoredData> refresh;
    }

    /**
     * The latest data of a map that isn't stored yet. Compared by identity.
     */
    private record Write(StoredData data, ResourceKey<Level> dimension) {
    }

    private final MapStorage storage;
    private final Predicate<ResourceKey<Level>> isLoaded;
    private final Supplier<HolderLookup.Provider> registries;
    private final Consumer<MapItemSavedData> initialized;
    private final LongSupplier clock;
    private final ExecutorService executor;

    private final Map<Integer, Entry> cache = new ConcurrentHashMap<>();
    private final Map<Integer, Write> unsaved = new ConcurrentHashMap<>();
    private final Set<Integer> notQueued = ConcurrentHashMap.newKeySet(); // unsaved ones whose write failed
    private CompletableFuture<?> lastWrite = CompletableFuture.completedFuture(null); // the executor runs one at a time

    private final ArrayDeque<Integer> reserved = new ArrayDeque<>(); // guarded by itself
    private final ReentrantLock allocating = new ReentrantLock(); // nextMapId is never called concurrently
    private boolean reserving; // guarded by reserved

    private volatile boolean closed;

    StoredMaps(
        final MapStorage storage,
        final Predicate<ResourceKey<Level>> isLoaded,
        final Supplier<HolderLookup.Provider> registries,
        final Consumer<MapItemSavedData> initialized,
        final LongSupplier clock
    ) {
        this.storage = storage;
        this.isLoaded = isLoaded;
        this.registries = registries;
        this.initialized = initialized;
        this.clock = clock;
        this.executor = Executors.newSingleThreadExecutor(run -> {
            final Thread thread = new Thread(run, "Archipelago Map Storage");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((failed, thr) -> LOGGER.error("Uncaught exception in thread {}", failed.getName(), thr));
            return thread;
        });
    }

    static StoredMaps forServer(final MapStorage storage) {
        return new StoredMaps(
            storage,
            dimension -> MinecraftServer.getServer().getLevel(dimension) != null,
            () -> MinecraftServer.getServer().registryAccess(),
            data -> new org.bukkit.event.server.MapInitializeEvent(data.mapView).callEvent(),
            System::nanoTime
        );
    }

    // reading

    /**
     * @return the map, or null if there is none or it can't be read now
     */
    public @Nullable MapItemSavedData get(final MapId mapId) {
        final int id = mapId.id();
        final long now = this.clock.getAsLong();
        final Entry cached = this.cache.get(id);
        if (cached != null) {
            final MapItemSavedData data = cached.data;
            if (data != null) {
                this.refreshIfElsewhere(id, cached, data, now);
                return data;
            }
            if (now - cached.checkedAt < (cached.unreadable ? RETRY_UNREADABLE_NANOS : RETRY_MISSING_NANOS)) {
                return null;
            }
        }
        if (this.closed) {
            return null;
        }
        final Entry entry = new Entry();
        entry.checkedAt = now;
        try {
            final StoredData stored = this.storage.loadMap(id);
            if (stored != null) {
                final MapItemSavedData data = this.decode(mapId, stored);
                data.id = mapId;
                entry.data = data;
                this.cache.put(id, entry); // before plugins get it, they can ask for it again
                this.initialized.accept(data);
                return data;
            }
        } catch (final Throwable thr) {
            entry.unreadable = true;
            LOGGER.error("Could not load map #{}. It shows nothing until it can be, and is not replaced", id, thr);
        }
        this.cache.put(id, entry);
        return null;
    }

    /**
     * Maps of worlds loaded on other servers can change there.
     */
    private void refreshIfElsewhere(final int id, final Entry entry, final MapItemSavedData data, final long now) {
        final CompletableFuture<@Nullable StoredData> refresh = entry.refresh;
        if (refresh != null) {
            if (!refresh.isDone()) {
                return;
            }
            entry.refresh = null;
            entry.checkedAt = now;
            if (refresh.isCompletedExceptionally() || this.unsaved.containsKey(id) || this.isLoaded.test(data.dimension)) {
                return; // tried again later, or this server has the latest
            }
            final StoredData stored = refresh.join();
            if (stored != null) {
                try {
                    data.archipelago$replaceWith(this.decode(new MapId(id), stored));
                } catch (final IOException ex) {
                    LOGGER.warn("Could not read map #{} again: {}", id, ex.toString());
                }
            }
            return;
        }
        if (this.closed || now - entry.checkedAt < REFRESH_NANOS || this.unsaved.containsKey(id) || this.isLoaded.test(data.dimension)) {
            return;
        }
        entry.checkedAt = now;
        entry.refresh = CompletableFuture.supplyAsync(() -> {
            try {
                return this.storage.loadMap(id);
            } catch (final Throwable thr) {
                throw new CompletionException(thr);
            }
        }, this.executor);
    }

    /**
     * A world that was on another server comes with the maps it saved there.
     */
    public void worldLoaded(final ResourceKey<Level> dimension) {
        if (this.closed) {
            return;
        }
        for (final Map.Entry<Integer, Entry> cached : List.copyOf(this.cache.entrySet())) {
            final int id = cached.getKey();
            final Entry entry = cached.getValue();
            final MapItemSavedData data = entry.data;
            if (data == null) {
                if (!entry.unreadable) {
                    entry.checkedAt = 0L; // it may be stored by now
                }
                continue;
            }
            if (!data.dimension.equals(dimension) || this.unsaved.containsKey(id)) {
                continue; // what isn't stored yet is the latest
            }
            final CompletableFuture<@Nullable StoredData> refresh = entry.refresh;
            if (refresh != null) {
                StorageWait.await(refresh, () -> LOGGER.warn("Still waiting for map storage to load map #{}", id));
                entry.refresh = null;
            }
            entry.checkedAt = this.clock.getAsLong();
            try {
                final StoredData stored = this.storage.loadMap(id);
                if (stored != null) {
                    data.archipelago$replaceWith(this.decode(new MapId(id), stored));
                }
            } catch (final Throwable thr) {
                // it would be saved as it was before the world moved, over what was saved there
                LOGGER.error("Could not load map #{} again for world '{}'. It shows nothing until it can be", id, dimension.identifier(), thr);
                final Entry unreadable = new Entry();
                unreadable.unreadable = true;
                unreadable.checkedAt = this.clock.getAsLong();
                this.cache.put(id, unreadable);
            }
        }
    }

    private MapItemSavedData decode(final MapId id, final StoredData stored) throws IOException {
        final SavedDataType<MapItemSavedData> type = MapItemSavedData.type(id);
        final CompoundTag saved = StoredNbt.decode(stored);
        final CompoundTag upgraded = type.dataFixType().update(
            DataFixers.getDataFixer(), saved, NbtUtils.getDataVersion(saved, DEFAULT_DATA_VERSION), SharedConstants.getCurrentVersion().dataVersion().version()
        );
        final Tag data = upgraded.get("data");
        if (data == null) {
            throw new IOException("There is no 'data' tag");
        }
        try {
            return type.codec().parse(this.registries.get().createSerializationContext(NbtOps.INSTANCE), data)
                .getOrThrow(error -> new IOException("It could not be parsed: " + error));
        } catch (final RuntimeException ex) {
            throw new IOException("It could not be parsed", ex);
        }
    }

    // creating

    /**
     * A new map, which is stored right away, so that other servers can show it.
     */
    public void set(final MapId mapId, final MapItemSavedData data) {
        final Entry entry = new Entry();
        entry.data = data;
        entry.checkedAt = this.clock.getAsLong();
        this.cache.put(mapId.id(), entry);
        this.write(mapId.id(), data);
    }

    /**
     * @throws StorageException if there is no ID
     */
    public MapId nextId() {
        Integer id;
        synchronized (this.reserved) {
            id = this.reserved.poll();
        }
        if (id == null) {
            this.allocating.lock();
            try {
                synchronized (this.reserved) {
                    id = this.reserved.poll(); // reserved while waiting
                }
                if (id == null) {
                    id = this.allocate();
                }
            } finally {
                this.allocating.unlock();
            }
        }
        if (this.cache.containsKey(id) || this.unsaved.containsKey(id)) {
            throw new StorageException("Map storage handed out ID " + id + ", which is already used by a map");
        }
        this.reserveAhead();
        return new MapId(id);
    }

    /**
     * @return whether an ID is ready for a new map
     */
    boolean reserveId() {
        synchronized (this.reserved) {
            if (!this.reserved.isEmpty()) {
                return true;
            }
        }
        this.allocating.lock();
        try {
            synchronized (this.reserved) {
                if (!this.reserved.isEmpty()) {
                    return true;
                }
            }
            final int id = this.allocate();
            synchronized (this.reserved) {
                this.reserved.addFirst(id);
            }
            return true;
        } catch (final StorageException ex) {
            LOGGER.error("Could not make a new map", ex);
            return false;
        } finally {
            this.allocating.unlock();
        }
    }

    private int allocate() {
        if (this.closed) {
            throw new StorageException("Map storage is closed");
        }
        try {
            return this.storage.nextMapId();
        } catch (final Throwable thr) {
            throw new StorageException("Could not get an ID for a new map", thr);
        }
    }

    private void reserveAhead() {
        synchronized (this.reserved) {
            if (this.reserving || this.closed || this.reserved.size() > RESERVED_IDS / 2) {
                return;
            }
            this.reserving = true;
        }
        this.executor.execute(() -> {
            try {
                while (!this.closed) {
                    synchronized (this.reserved) {
                        if (this.reserved.size() >= RESERVED_IDS) {
                            return;
                        }
                    }
                    final int id;
                    this.allocating.lock();
                    try {
                        id = this.allocate();
                    } finally {
                        this.allocating.unlock();
                    }
                    synchronized (this.reserved) {
                        this.reserved.add(id);
                    }
                }
            } catch (final StorageException ex) {
                LOGGER.warn("Could not reserve IDs for new maps: {}", ex.getCause() == null ? ex.toString() : ex.getCause().toString());
            } finally {
                synchronized (this.reserved) {
                    this.reserving = false;
                }
            }
        });
    }

    // saving

    /**
     * Saves the maps of loaded worlds that changed.
     */
    public void save(final boolean flush) {
        if (this.closed) {
            return;
        }
        this.cache.forEach((id, entry) -> {
            final MapItemSavedData data = entry.data;
            if (data != null && data.isDirty() && this.isLoaded.test(data.dimension)) {
                this.write(id, data);
            }
        });
        this.retryFailed(null);
        if (flush) {
            this.awaitWrites();
        }
    }

    /**
     * @return whether every map of the world is stored
     */
    public boolean saveWorld(final ResourceKey<Level> dimension) {
        if (this.closed) {
            return true;
        }
        this.cache.forEach((id, entry) -> {
            final MapItemSavedData data = entry.data;
            if (data != null && data.isDirty() && data.dimension.equals(dimension)) {
                this.write(id, data);
            }
        });
        this.retryFailed(dimension);
        this.awaitWrites();
        for (final Map.Entry<Integer, Write> write : this.unsaved.entrySet()) {
            if (write.getValue().dimension().equals(dimension)) {
                LOGGER.error("Map #{} of world '{}' could not be saved, so the world stays loaded", write.getKey(), dimension.identifier());
                return false;
            }
        }
        return true;
    }

    private void write(final int id, final MapItemSavedData data) {
        final StoredData stored;
        try {
            final CompoundTag tag = new CompoundTag();
            tag.put("data", MapItemSavedData.type(new MapId(id)).codec().encodeStart(this.registries.get().createSerializationContext(NbtOps.INSTANCE), data).getOrThrow());
            NbtUtils.addCurrentDataVersion(tag);
            stored = StoredNbt.encode(tag); // the colors are copied here, on main
        } catch (final Throwable thr) {
            LOGGER.error("Could not encode map #{}", id, thr); // stays dirty
            return;
        }
        data.setDirty(false);
        this.unsaved.put(id, new Write(stored, data.dimension));
        this.notQueued.remove(id);
        this.queue(id);
    }

    private void queue(final int id) {
        this.lastWrite = CompletableFuture.runAsync(() -> this.store(id), this.executor);
    }

    private void store(final int id) {
        final Write write = this.unsaved.get(id);
        if (write == null) {
            return; // a later write already stored it
        }
        try {
            this.storage.saveMap(id, write.data());
            this.unsaved.remove(id, write);
        } catch (final Throwable thr) {
            LOGGER.error("Could not save map #{}. It is kept in memory and will be retried", id, thr);
            if (this.unsaved.get(id) == write) { // a later write is queued already otherwise
                this.notQueued.add(id);
            }
        }
    }

    private void retryFailed(final @Nullable ResourceKey<Level> dimension) {
        for (final Integer id : List.copyOf(this.notQueued)) {
            final Write write = this.unsaved.get(id);
            if (write == null) {
                this.notQueued.remove(id);
            } else if ((dimension == null || write.dimension().equals(dimension)) && this.notQueued.remove(id)) {
                this.queue(id);
            }
        }
    }

    private void awaitWrites() {
        StorageWait.await(this.lastWrite, () -> LOGGER.warn("Still waiting for map storage to save {} maps", this.unsaved.size()));
    }

    boolean hasUnsaved() {
        return !this.unsaved.isEmpty();
    }

    // closing

    private long clockForClose() {
        return System.nanoTime(); // real time, as it waits for real retries
    }

    /**
     * After the last save of a stopping server.
     */
    public synchronized void close() {
        if (this.closed) {
            return;
        }
        final long start = this.clockForClose();
        this.awaitWrites();
        while (!this.unsaved.isEmpty() && this.clockForClose() - start < CLOSING_RETRY_NANOS) {
            this.retryFailed(null);
            this.awaitWrites();
            if (!this.unsaved.isEmpty()) {
                try {
                    Thread.sleep(1000L);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        this.closed = true;
        if (!this.unsaved.isEmpty()) {
            LOGGER.error("Map storage closed WITHOUT saving these maps: {}", this.unsaved.keySet());
        }
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(10L, TimeUnit.SECONDS)) {
                LOGGER.warn("Map storage is still busy while closing");
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        try {
            this.storage.close();
        } catch (final Throwable thr) {
            LOGGER.error("Failed to close map storage", thr);
        }
    }
}
