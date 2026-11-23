package sh.btx.archipelago.storage;

import ca.spottedleaf.dataconverter.minecraft.MCDataConverter;
import ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry;
import ca.spottedleaf.dataconverter.minecraft.util.Version;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The server's side of a {@link WorldStorage}, for as long as its world is loaded.
 * <p>
 * Three locks, always taken in this order:
 * <ol>
 *     <li>{@code chunkSaving}: a flushed save of the level, and the start of closing. Shutdown can come from another
 *     thread (signals, the watchdog), and chunks saved after the chunk system halts are never written.</li>
 *     <li>{@code writeGate}: writes share it, saving and closing have it alone. A save sees no write in progress.</li>
 *     <li>{@code access}: reads and writes share it, retrying and closing have it alone. Reads are not blocked during
 *     {@link WorldStorage#save()}, as that runs on the main thread and may cause a chunk to load.</li>
 * </ol>
 * Order within a chunk and type comes from {@link StorageDataController}, which never runs two tasks for one at once.
 */
@NullMarked
public final class StoredWorld {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private static final int FINAL_SAVE_ATTEMPTS = 3;
    private static final long FINAL_SAVE_RETRY_MILLIS = 1000L;
    // in a row. Enough to get past bad keys, without waiting for a timeout per key when the backend is down
    static final int MAX_FAILED_RETRIES = 8;
    // when closing everything is tried, but only so much time is spent on failures, so shutdown gets to other worlds
    private static final long CLOSING_FAILURE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(15L);
    private static final long CLOSING_FAILURE_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(60L); // even if some work
    // after which the rest of the world is saved without the entities that can't be merged yet
    static final int MERGE_ATTEMPTS_BEFORE_SAVING_WITHOUT = 3;
    static final int MAX_REFUSED_UNLOADS = 3;

    private record ChunkId(WorldStorage.ChunkDataType type, int x, int z) {
    }

    @FunctionalInterface
    private interface Retry {
        void run() throws Exception;
    }

    private final Identifier id;
    private final WorldStorage storage;
    private final Consumer<StoredWorld> onClose;

    private final ReentrantLock chunkSaving = new ReentrantLock();
    private final ReentrantReadWriteLock writeGate = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock access = new ReentrantReadWriteLock();

    private final Map<ChunkId, Optional<StoredData>> failedChunkWrites = new ConcurrentHashMap<>(); // empty = delete
    private final Map<Key, StoredData> failedDataWrites = new ConcurrentHashMap<>();
    // entities that moved into unloaded chunks, whose stored entities could not be read to add them to
    private final Map<ChunkId, StoredData> failedMerges = new ConcurrentHashMap<>();
    private final Map<ChunkId, Integer> mergeAttempts = new ConcurrentHashMap<>();

    private final Set<ChunkId> quarantined = ConcurrentHashMap.newKeySet();
    // quarantined ones with writes from after that. Writes from before are good, can still be queued, and are stored
    private final Set<ChunkId> replaced = ConcurrentHashMap.newKeySet();
    private final Map<ChunkId, Optional<StoredData>> neverSaved = new ConcurrentHashMap<>(); // what those writes wrote
    // what could not be turned into data. Failing on main clears when a later write is scheduled, as an older one can
    // still be queued then. Failing afterwards is reported in order, so clears when a write runs
    private final Set<ChunkId> notCaptured = ConcurrentHashMap.newKeySet();
    private final Set<ChunkId> notEncoded = ConcurrentHashMap.newKeySet();
    private int refusedUnloads; // main thread

    private final Set<CompletableFuture<?>> savesInProgress = ConcurrentHashMap.newKeySet();
    private final CompletableFuture<Boolean> closeResult = new CompletableFuture<>();

    private @Nullable StorageSavedDataStorage savedData;
    private volatile @Nullable CompletableFuture<@Nullable Void> lastSave;
    private volatile @Nullable Thread closer;
    private volatile boolean closing;
    private volatile boolean closed;
    private volatile boolean incomplete;

    StoredWorld(final Identifier id, final WorldStorage storage, final Consumer<StoredWorld> onClose) {
        this.id = id;
        this.storage = storage;
        this.onClose = onClose;
    }

    WorldStorage storage() {
        return this.storage;
    }

    Identifier id() {
        return this.id;
    }

    boolean isClosed() {
        return this.closed;
    }

    public boolean isClosing() {
        return this.closing;
    }

    static Key key(final Identifier id) {
        return Key.key(id.getNamespace(), id.getPath());
    }

    StorageSavedDataStorage savedData(final Path unusedFolder, final HolderLookup.Provider registries) {
        if (this.savedData == null) {
            final StorageSavedDataStorage created = new StorageSavedDataStorage(this, unusedFolder, registries);
            created.preloadLazyData();
            this.savedData = created;
        }
        return this.savedData;
    }

    // reading

    @Nullable StoredData readChunk(final WorldStorage.ChunkDataType type, final int x, final int z) throws IOException {
        final ChunkId chunk = new ChunkId(type, x, z);
        this.access.readLock().lock();
        try {
            this.checkOpen();
            // what the world has had since the original could not be loaded. Nothing else is hidden here: merging
            // entities relies on reads being what is stored. Loading does the hiding, see hidesStored
            final Optional<StoredData> sessionOnly = this.neverSaved.get(chunk);
            if (sessionOnly != null) {
                return sessionOnly.orElse(null);
            }
            final Optional<StoredData> unwritten = this.failedChunkWrites.get(chunk);
            final StoredData latest = unwritten != null ? unwritten.orElse(null) : this.storage.readChunk(type, x, z);
            final StoredData unmerged = this.failedMerges.get(chunk);
            return unmerged == null ? latest : mergeEntities(chunk, latest, unmerged);
        } catch (final IOException ex) {
            throw ex;
        } catch (final Throwable thr) {
            throw new IOException("Storage of world '" + this.id + "' failed to read " + type + " of chunk " + x + ", " + z, thr);
        } finally {
            this.access.readLock().unlock();
        }
    }

    @Nullable StoredData readData(final Key key) throws IOException {
        this.access.readLock().lock();
        try {
            this.checkOpen();
            final StoredData unwritten = this.failedDataWrites.get(key);
            return unwritten != null ? unwritten : this.storage.readData(key);
        } catch (final IOException ex) {
            throw ex;
        } catch (final Throwable thr) {
            throw new IOException("Storage of world '" + this.id + "' failed to read " + key, thr);
        } finally {
            this.access.readLock().unlock();
        }
    }

    private void checkOpen() throws IOException {
        if (this.closed) {
            throw new IOException("Storage of world '" + this.id + "' is closed");
        }
    }

    // writing, which never throws: what fails is kept, given to reads and retried before saving

    /**
     * Called when a write is scheduled, which can be long before it runs.
     *
     * @return whether it must not be stored, as it was made from what replaced data that could not be loaded
     */
    boolean writeScheduled(final WorldStorage.ChunkDataType type, final int x, final int z) {
        if (!this.notCaptured.isEmpty()) {
            this.notCaptured.remove(new ChunkId(type, x, z));
        }
        if (this.quarantined.isEmpty()) {
            return false;
        }
        final ChunkId chunk = new ChunkId(type, x, z);
        if (!this.quarantined.contains(chunk)) {
            return false;
        }
        this.replaced.add(chunk);
        return true;
    }

    /**
     * Showing the original next to edits that aren't saved would duplicate whatever is taken from it.
     *
     * @return whether loading must treat the data as absent
     */
    boolean hidesStored(final WorldStorage.ChunkDataType type, final int x, final int z) {
        if (this.quarantined.isEmpty()) {
            return false;
        }
        final ChunkId chunk = new ChunkId(type, x, z);
        return this.quarantined.contains(chunk) && !this.replaced.contains(chunk);
    }

    /**
     * Entities load alongside their chunk, so are already loaded when it turns out that the chunk can't be.
     *
     * @return whether to leave them out, as they are stored entities of a chunk whose changes aren't saved
     */
    boolean hidesEntities(final int x, final int z) {
        return this.quarantined.contains(new ChunkId(WorldStorage.ChunkDataType.CHUNK, x, z))
            && !this.replaced.contains(new ChunkId(WorldStorage.ChunkDataType.ENTITIES, x, z));
    }

    void writeChunk(final WorldStorage.ChunkDataType type, final int x, final int z, final @Nullable StoredData data, final boolean notToBeStored) {
        final ChunkId chunk = new ChunkId(type, x, z);
        this.writeGate.readLock().lock();
        this.access.readLock().lock();
        try {
            if (this.closed) {
                LOGGER.warn("Not writing {} of chunk {}, {}: storage of world '{}' is closed", type, x, z, this.id);
                return;
            }
            this.notEncoded.remove(chunk);
            if (notToBeStored) {
                this.neverSaved.put(chunk, Optional.ofNullable(data));
                return;
            }
            try {
                this.storage.writeChunk(type, x, z, data);
                this.failedChunkWrites.remove(chunk);
            } catch (final Throwable thr) {
                this.logFailedWrite(type + " of chunk " + x + ", " + z, thr);
                this.failedChunkWrites.put(chunk, Optional.ofNullable(data));
            }
            this.failedMerges.remove(chunk); // was read with them merged in, so contains them
            this.mergeAttempts.remove(chunk);
        } finally {
            this.access.readLock().unlock();
            this.writeGate.readLock().unlock();
        }
    }

    void writeData(final Key key, final StoredData data) {
        this.writeGate.readLock().lock();
        this.access.readLock().lock();
        try {
            if (this.closed) {
                LOGGER.warn("Not writing {}: storage of world '{}' is closed", key, this.id);
                return;
            }
            try {
                this.storage.writeData(key, data);
                this.failedDataWrites.remove(key);
            } catch (final Throwable thr) {
                this.logFailedWrite(key.asString(), thr);
                this.failedDataWrites.put(key, data);
            }
        } finally {
            this.access.readLock().unlock();
            this.writeGate.readLock().unlock();
        }
    }

    private void logFailedWrite(final String what, final Throwable thr) {
        if (this.failedWrites() == 0) {
            LOGGER.error("Storage of world '{}' failed to write {}. It will be retried before saving", this.id, what, thr);
        } else { // one stack trace per outage
            LOGGER.error("Storage of world '{}' failed to write {}: {}", this.id, what, thr.toString());
        }
    }

    private int failedWrites() {
        return this.failedChunkWrites.size() + this.failedDataWrites.size() + this.failedMerges.size();
    }

    /**
     * Data that could not even be turned into bytes, so there is nothing to retry. The rest is still saved, as it would
     * otherwise all be lost with it, but it keeps the world from unloading with saving, and is reported when closing.
     */
    void notEncoded(final WorldStorage.ChunkDataType type, final int x, final int z) {
        this.notEncoded(type, x, z, this.replaced.contains(new ChunkId(type, x, z)));
    }

    void notEncoded(final WorldStorage.ChunkDataType type, final int x, final int z, final boolean notToBeStored) {
        final ChunkId chunk = new ChunkId(type, x, z);
        if (notToBeStored) {
            this.neverSaved.putIfAbsent(chunk, Optional.empty()); // it wasn't to be stored. The original stays hidden
        } else {
            this.notEncoded.add(chunk);
        }
    }

    void notCaptured(final WorldStorage.ChunkDataType type, final int x, final int z) {
        this.notCaptured.add(new ChunkId(type, x, z));
    }

    private boolean failedToSerialize() {
        final StorageSavedDataStorage savedData = this.savedData;
        return !this.notCaptured.isEmpty() || !this.notEncoded.isEmpty() || (savedData != null && savedData.failedToEncode());
    }

    /**
     * Keeps entities that moved into an unloaded chunk, whose own entities could not be read to merge them with.
     * Writing just these, as vanilla does, would replace the stored ones.
     */
    void retainEntities(final int x, final int z, final CompoundTag entities) {
        final ChunkId chunk = new ChunkId(WorldStorage.ChunkDataType.ENTITIES, x, z);
        this.access.readLock().lock();
        try {
            final StoredData added = StoredNbt.encode(entities);
            final StoredData earlier = this.failedMerges.get(chunk);
            this.failedMerges.put(chunk, earlier == null ? added : mergeEntities(chunk, earlier, added));
        } catch (final Throwable thr) {
            this.incomplete = true;
            LOGGER.error("Lost entities that moved into chunk {}, {} of world '{}'", x, z, this.id, thr);
        } finally {
            this.access.readLock().unlock();
        }
    }

    private static StoredData mergeEntities(final ChunkId chunk, final @Nullable StoredData stored, final StoredData added) throws IOException {
        if (stored == null) {
            return added;
        }
        final CompoundTag merged = StoredNbt.decode(added);
        final CompoundTag into = StoredNbt.decode(stored);
        StorageDataController.checkPosition(chunk.type(), chunk.x(), chunk.z(), into);
        ChunkEntitySlices.copyEntities(upgradeEntities(into), merged);
        return StoredNbt.encode(merged);
    }

    // up front, as merging stamps entities with the current version
    static CompoundTag upgradeEntities(final CompoundTag tag) {
        final CompoundTag upgraded = MCDataConverter.convertTag(
            MCTypeRegistry.ENTITY_CHUNK, DataFixTypes.ENTITY_CHUNK, tag, NbtUtils.getDataVersion(tag, -1), Version.getCurrentVersion()
        );
        NbtUtils.addCurrentDataVersion(upgraded); // converting doesn't, and converting twice corrupts
        return upgraded;
    }

    /**
     * Stops what is made from now on from being stored, for what could not be loaded: the server continues with
     * empty or generated data in its place. A later successful read is no reason to lift this, as what is in memory
     * is still not what was stored.
     */
    void quarantine(final WorldStorage.ChunkDataType type, final int x, final int z) {
        if (this.closed) {
            return;
        }
        // without its blocks, the entities and POI that get saved for a chunk are wrong too
        final List<WorldStorage.ChunkDataType> blocked = type == WorldStorage.ChunkDataType.CHUNK
            ? List.of(WorldStorage.ChunkDataType.values())
            : List.of(type);
        boolean added = false;
        for (final WorldStorage.ChunkDataType each : blocked) {
            added |= this.quarantined.add(new ChunkId(each, x, z));
        }
        if (added) {
            LOGGER.error("Could not load {} of chunk {}, {} in world '{}'. What is stored is left untouched: changes to {} there will NOT be saved until the world is loaded again", type, x, z, this.id, blocked);
        }
    }

    // saving

    /**
     * Throws when called from inside of a callback, where saving or unloading would wait for itself.
     */
    public void checkNotInCallback() {
        if (this.writeGate.isWriteLockedByCurrentThread() || this.writeGate.getReadHoldCount() > 0 || this.access.getReadHoldCount() > 0) {
            throw new IllegalStateException("A WorldStorage cannot save or unload its own world");
        }
    }

    /**
     * @return false if the level is closing or closed, so must not schedule any more IO
     */
    public boolean canSave() {
        this.checkNotInCallback();
        return !this.closing;
    }

    /**
     * Runs the chunk part of a level save, and commits if it flushed.
     */
    public void saveChunks(final boolean flush, final Runnable saveChunks) {
        this.chunkSaving.lock();
        try {
            if (this.closing) {
                return;
            }
            saveChunks.run();
            if (flush) {
                this.save();
            }
        } finally {
            this.chunkSaving.unlock();
        }
    }

    /**
     * The commit point. Writes must have been flushed. The future is not waited for here.
     */
    CompletableFuture<@Nullable Void> save() {
        return this.save(false, false);
    }

    private CompletableFuture<@Nullable Void> save(final boolean closing, final boolean lastAttempt) {
        this.checkNotInCallback();
        final StorageSavedDataStorage savedData = this.savedData;
        if (savedData != null) {
            savedData.saveAndJoin(); // before locking, its writes take the locks
        }

        CompletableFuture<@Nullable Void> result;
        this.writeGate.writeLock().lock();
        try {
            if (this.closed) {
                return CompletableFuture.completedFuture(null);
            }
            try {
                if (this.failedWrites() != 0) {
                    this.access.writeLock().lock(); // no reads of a key while it is retried
                    try {
                        this.retryFailedWrites(closing);
                    } finally {
                        this.access.writeLock().unlock();
                    }
                }
                final int failing = this.failedChunkWrites.size() + this.failedDataWrites.size()
                    + (lastAttempt ? 0 : this.mergesWorthWaitingFor());
                result = failing == 0
                    ? Objects.requireNonNull(this.storage.save(), "WorldStorage#save returned null")
                    : CompletableFuture.failedFuture(new IOException("Not saving while " + failing + " writes are failing"));
            } catch (final Throwable thr) {
                result = CompletableFuture.failedFuture(thr);
            }
            this.lastSave = result;
            this.savesInProgress.add(result);
        } finally {
            this.writeGate.writeLock().unlock();
        }

        final CompletableFuture<@Nullable Void> saving = result;
        saving.whenComplete((ignored, thr) -> {
            this.savesInProgress.remove(saving);
            if (thr != null) {
                LOGGER.error("Failed to save world '{}' to its storage", this.id, thr);
            }
        });
        return saving;
    }

    // one chunk that can never be read must not stop the world from ever saving
    private int mergesWorthWaitingFor() {
        int count = 0;
        for (final ChunkId chunk : this.failedMerges.keySet()) {
            if (this.mergeAttempts.getOrDefault(chunk, 0) < MERGE_ATTEMPTS_BEFORE_SAVING_WITHOUT) {
                count++;
            }
        }
        return count;
    }

    // holding both write locks
    private void retryFailedWrites(final boolean closing) {
        final List<Retry> retries = new ArrayList<>();
        final List<Retry> merges = new ArrayList<>();
        this.failedDataWrites.forEach((key, data) -> retries.add(() -> {
            this.storage.writeData(key, data);
            this.failedDataWrites.remove(key);
        }));
        this.failedChunkWrites.forEach((chunk, data) -> {
            if (!this.failedMerges.containsKey(chunk)) { // else it is what gets merged into
                retries.add(() -> {
                    this.storage.writeChunk(chunk.type(), chunk.x(), chunk.z(), data.orElse(null));
                    this.failedChunkWrites.remove(chunk);
                });
            }
        });
        this.failedMerges.forEach((chunk, added) -> merges.add(() -> this.retryMerge(chunk, added)));
        // apart, as writes are needed to save and merges only wanted
        this.runRetries(retries, closing);
        this.runRetries(merges, closing);
    }

    private void runRetries(final List<Retry> retries, final boolean closing) {
        Collections.shuffle(retries); // so keys that always fail don't keep the rest from being tried
        int failures = 0;
        long timeFailing = 0L;
        long totalTimeFailing = 0L;
        for (final Retry retry : retries) {
            final long start = System.nanoTime();
            try {
                retry.run();
                failures = 0;
                timeFailing = 0L; // the backend works
            } catch (final Throwable thr) {
                LOGGER.error("Storage of world '{}' failed again to write: {}", this.id, thr.toString());
                failures++;
                final long took = System.nanoTime() - start;
                timeFailing += took;
                totalTimeFailing += took;
                if (closing
                    ? timeFailing >= CLOSING_FAILURE_BUDGET_NANOS || totalTimeFailing >= CLOSING_FAILURE_LIMIT_NANOS
                    : failures >= MAX_FAILED_RETRIES) {
                    return;
                }
            }
        }
    }

    private void retryMerge(final ChunkId chunk, final StoredData added) throws IOException {
        this.mergeAttempts.merge(chunk, 1, Integer::sum);
        final Optional<StoredData> unwritten = this.failedChunkWrites.get(chunk);
        final StoredData stored = unwritten != null ? unwritten.orElse(null) : this.storage.readChunk(chunk.type(), chunk.x(), chunk.z());
        final StoredData merged;
        try {
            merged = mergeEntities(chunk, stored, added);
        } catch (final IOException | RuntimeException ex) {
            // will never work, and must not block saving the rest of the world forever
            LOGGER.error("Stored entities of chunk {}, {} in world '{}' are unreadable. They are left untouched, and entities that moved there are lost", chunk.x(), chunk.z(), this.id, ex);
            this.incomplete = true;
            this.failedMerges.remove(chunk);
            return;
        }
        // merged is now just a write, which can fail and be retried like any other
        this.failedMerges.remove(chunk);
        this.mergeAttempts.remove(chunk);
        this.failedChunkWrites.put(chunk, Optional.of(merged));
        this.storage.writeChunk(chunk.type(), chunk.x(), chunk.z(), merged);
        this.failedChunkWrites.remove(chunk);
    }

    /**
     * Waits for the save that the last flushed save of the level started.
     */
    boolean awaitLastSave() {
        if (this.closing) {
            return true;
        }
        final CompletableFuture<@Nullable Void> last = this.lastSave;
        if (last == null || !this.await(last)) {
            return false;
        }
        if (!this.failedToSerialize()) {
            this.refusedUnloads = 0;
            return true;
        }
        // there can be nothing left to retry, so this can't refuse forever. Closing still reports it
        if (++this.refusedUnloads > MAX_REFUSED_UNLOADS) {
            return true;
        }
        LOGGER.error("Not unloading world '{}', as some of it could not be serialized. The rest of it has been saved", this.id);
        return false;
    }

    // closing

    /**
     * Claims closing, before the chunk system is halted.
     *
     * @return false if someone else is closing it, who then is the only one to touch the chunk system
     */
    public boolean beginClose() {
        this.checkNotInCallback();
        if (this.chunkSaving.isHeldByCurrentThread()) {
            throw new IllegalStateException("World '" + this.id + "' cannot be unloaded while it is saving");
        }
        this.chunkSaving.lock();
        try {
            if (this.closing) {
                return false;
            }
            this.closing = true;
            this.closer = Thread.currentThread();
            return true;
        } finally {
            this.chunkSaving.unlock();
        }
    }

    /**
     * Called once the chunk system has stopped, so every write has landed.
     */
    public synchronized void close(final boolean save) {
        this.close(save, true);
    }

    /**
     * @param written false if stopping the chunk system failed, so what is written may not belong together
     */
    public synchronized void close(final boolean save, final boolean written) {
        this.checkNotInCallback();
        if (this.closed) {
            return;
        }
        this.closing = true;
        boolean saved = !save;
        try {
            for (int attempt = 1; save && written && attempt <= FINAL_SAVE_ATTEMPTS; attempt++) {
                saved = this.await(this.save(true, attempt == FINAL_SAVE_ATTEMPTS));
                if (saved) {
                    break;
                }
                if (attempt < FINAL_SAVE_ATTEMPTS) {
                    Thread.sleep(FINAL_SAVE_RETRY_MILLIS); // nothing can be retried later, so wait out a blip
                }
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                if (save) {
                    if (this.incomplete || !this.failedMerges.isEmpty() || this.failedToSerialize()) {
                        saved = false;
                    }
                    if (!saved) {
                        LOGGER.error("World '{}' was closed WITHOUT its last changes being saved ({} failed writes)", this.id, this.failedWrites());
                    }
                }
                if (!this.neverSaved.isEmpty()) {
                    LOGGER.error("Discarding changes to {} parts of chunks in world '{}' that could not be loaded. What was stored there is unchanged", this.neverSaved.size(), this.id);
                }
                this.release();
            } finally {
                this.closeResult.complete(saved);
            }
        }
    }

    /**
     * For storage whose level has no chunk system to stop, or which someone else is closing.
     */
    void closeUnusedOrAwait() {
        if (this.beginClose()) {
            this.close(false);
        } else {
            this.awaitClose();
        }
    }

    private void release() {
        if (this.savedData != null) {
            this.savedData.awaitWrites();
        }
        this.writeGate.writeLock().lock();
        this.access.writeLock().lock();
        try {
            this.closed = true;
        } finally {
            this.access.writeLock().unlock();
            this.writeGate.writeLock().unlock();
        }
        try {
            for (final CompletableFuture<?> saving : this.savesInProgress) {
                this.await(saving);
            }
            this.storage.close();
        } catch (final Throwable thr) {
            LOGGER.error("Failed to close storage of world '{}'", this.id, thr);
        } finally {
            this.onClose.accept(this);
        }
    }

    /**
     * @return whether everything was saved, if it was to be saved
     */
    boolean awaitClose() {
        if (!this.closeResult.isDone() && this.closer == Thread.currentThread()) {
            return false; // unloaded again from inside of closing
        }
        this.await(this.closeResult);
        return this.closeResult.getNow(false);
    }

    private boolean await(final CompletableFuture<?> future) {
        return StorageWait.await(future, () -> LOGGER.warn(
            "Still waiting for storage of world '{}' to save. Storages must not need the main thread or the Bukkit scheduler to finish saving", this.id
        ));
    }

    @Override
    public String toString() {
        return "StoredWorld[" + this.id + "]";
    }
}
