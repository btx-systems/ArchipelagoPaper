package sh.btx.archipelago.storage;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import sh.btx.archipelago.storage.WorldStorage.ChunkDataType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AllFeatures
@Timeout(30)
class StoredWorldTest {

    private static final Key KEY = Key.key("test", "data");
    private static final StoredData OLD = data("old");
    private static final StoredData NEW = data("new");
    private static final List<Supplier<Throwable>> FAILURES = List.of(
        () -> new IOException("io"), () -> new IllegalStateException("runtime"), () -> new NoClassDefFoundError("linkage")
    );

    private FakeWorldStorage backend;
    private StoredWorld world;
    private AtomicInteger released;

    private static StoredData data(final String value) {
        return StoredData.of(CompoundBinaryTag.builder().putString("value", value).build());
    }

    @BeforeEach
    void setUp() {
        this.backend = new FakeWorldStorage();
        this.released = new AtomicInteger();
        this.world = new StoredWorld(Identifier.fromNamespaceAndPath("test", "world"), this.backend, closed -> this.released.incrementAndGet());
    }

    @Test
    void everyKindOfReadFailureThrows() {
        for (final Supplier<Throwable> failure : FAILURES) {
            this.backend.readFailure = failure;
            assertThrows(IOException.class, () -> this.world.readChunk(ChunkDataType.CHUNK, 1, 2));
            assertThrows(IOException.class, () -> this.world.readData(KEY));
        }
    }

    @Test
    void everyKindOfFailedWriteIsKeptAndServed() throws IOException {
        for (final Supplier<Throwable> failure : FAILURES) {
            this.backend.writeFailure = failure;
            this.world.writeChunk(ChunkDataType.CHUNK, 1, 2, NEW, false);
            this.world.writeData(KEY, NEW);
            assertEquals(NEW, this.world.readChunk(ChunkDataType.CHUNK, 1, 2));
            assertEquals(NEW, this.world.readData(KEY));
        }
        assertTrue(this.backend.chunks.isEmpty());
    }

    @Test
    void failedDeleteIsServedAsAbsent() throws IOException {
        this.world.writeChunk(ChunkDataType.ENTITIES, 1, 2, OLD, false);
        this.backend.writeFailure = FAILURES.getFirst();
        this.world.writeChunk(ChunkDataType.ENTITIES, 1, 2, null, false);
        assertNull(this.world.readChunk(ChunkDataType.ENTITIES, 1, 2));

        this.backend.writeFailure = null;
        this.world.save().join();
        assertTrue(this.backend.chunks.isEmpty());
    }

    @Test
    void noSaveWhileWritesFail() {
        this.backend.writeFailure = FAILURES.getFirst();
        this.world.writeChunk(ChunkDataType.POI, 0, 0, NEW, false);
        assertTrue(this.world.save().isCompletedExceptionally());
        assertEquals(0, this.backend.saves.get());
        assertFalse(this.world.awaitLastSave());

        this.backend.writeFailure = null;
        this.world.save().join();
        assertEquals(1, this.backend.saves.get());
        assertEquals(NEW, this.backend.chunks.get(FakeWorldStorage.id(ChunkDataType.POI, 0, 0)));
        assertTrue(this.world.awaitLastSave());
    }

    @Test
    void failingDataAlsoBlocksSaving() {
        this.backend.writeFailure = FAILURES.getFirst();
        this.world.writeData(KEY, NEW);
        assertTrue(this.world.save().isCompletedExceptionally());

        this.backend.writeFailure = null;
        this.world.save().join();
        assertEquals(NEW, this.backend.data.get(KEY));
    }

    @Test
    void newerWriteBeatsRetry() {
        this.backend.writeFailure = FAILURES.getFirst();
        this.world.writeChunk(ChunkDataType.CHUNK, 0, 0, OLD, false);
        this.backend.writeFailure = null;
        this.world.writeChunk(ChunkDataType.CHUNK, 0, 0, NEW, false);
        this.world.save().join();
        assertEquals(NEW, this.backend.chunks.get(FakeWorldStorage.id(ChunkDataType.CHUNK, 0, 0)));
    }

    @Test
    void retriesGiveUpWhenTheBackendIsDown() {
        this.backend.writeFailure = FAILURES.getFirst();
        for (int x = 0; x < 100; x++) {
            this.world.writeChunk(ChunkDataType.CHUNK, x, 0, NEW, false);
        }
        this.backend.calls.clear();
        assertTrue(this.world.save().isCompletedExceptionally());
        assertEquals(StoredWorld.MAX_FAILED_RETRIES, this.backend.calls.size());
    }

    private void scheduleAndWrite(final ChunkDataType type, final int x, final int z, final StoredData data) {
        final boolean notToBeStored = this.world.writeScheduled(type, x, z);
        this.world.writeChunk(type, x, z, data, notToBeStored);
    }

    private StoredData stored(final ChunkDataType type, final int x, final int z) {
        return this.backend.chunks.get(FakeWorldStorage.id(type, x, z));
    }

    @Test
    void unreadableChunkBlocksAllOfItsTypes() throws IOException {
        for (final ChunkDataType type : ChunkDataType.values()) {
            this.scheduleAndWrite(type, 3, 4, OLD);
        }
        this.world.quarantine(ChunkDataType.CHUNK, 3, 4);
        for (final ChunkDataType type : ChunkDataType.values()) {
            this.scheduleAndWrite(type, 3, 4, NEW);
            assertEquals(OLD, this.stored(type, 3, 4));
        }
        this.scheduleAndWrite(ChunkDataType.CHUNK, 3, 5, NEW);
        assertEquals(NEW, this.stored(ChunkDataType.CHUNK, 3, 5));
    }

    @Test
    void unreadableEntitiesOnlyBlockEntities() {
        this.scheduleAndWrite(ChunkDataType.ENTITIES, 3, 4, OLD);
        this.world.quarantine(ChunkDataType.ENTITIES, 3, 4);
        this.scheduleAndWrite(ChunkDataType.ENTITIES, 3, 4, null);
        this.scheduleAndWrite(ChunkDataType.CHUNK, 3, 4, NEW);
        assertEquals(OLD, this.stored(ChunkDataType.ENTITIES, 3, 4));
        assertEquals(NEW, this.stored(ChunkDataType.CHUNK, 3, 4));
    }

    @Test
    void quarantinedChangesAreReadBackSoNothingDuplicates() throws IOException {
        this.scheduleAndWrite(ChunkDataType.CHUNK, 0, 0, OLD); // a full chest
        this.world.quarantine(ChunkDataType.CHUNK, 0, 0);
        assertTrue(this.world.hidesStored(ChunkDataType.CHUNK, 0, 0)); // the chest never shows up where edits aren't saved
        assertEquals(OLD, this.world.readChunk(ChunkDataType.CHUNK, 0, 0)); // but reads are honest
        this.scheduleAndWrite(ChunkDataType.CHUNK, 0, 0, NEW); // emptied
        assertEquals(NEW, this.world.readChunk(ChunkDataType.CHUNK, 0, 0));
        this.scheduleAndWrite(ChunkDataType.CHUNK, 0, 0, null);
        assertNull(this.world.readChunk(ChunkDataType.CHUNK, 0, 0));

        this.world.close(true);
        assertEquals(OLD, this.stored(ChunkDataType.CHUNK, 0, 0));
    }

    @Test
    void writeFromBeforeQuarantineIsStillStored() {
        this.world.writeScheduled(ChunkDataType.ENTITIES, 0, 0);
        this.world.quarantine(ChunkDataType.CHUNK, 0, 0); // while it is queued
        this.world.writeChunk(ChunkDataType.ENTITIES, 0, 0, NEW, false);
        assertEquals(NEW, this.stored(ChunkDataType.ENTITIES, 0, 0));
    }

    @Test
    void earlierQueuedWriteIsStoredEvenIfALaterOneIsNot() {
        final boolean first = this.world.writeScheduled(ChunkDataType.POI, 0, 0);
        this.world.quarantine(ChunkDataType.CHUNK, 0, 0);
        final boolean second = this.world.writeScheduled(ChunkDataType.POI, 0, 0); // before the first ran
        this.world.writeChunk(ChunkDataType.POI, 0, 0, OLD, first);
        this.world.writeChunk(ChunkDataType.POI, 0, 0, NEW, second);
        assertEquals(OLD, this.stored(ChunkDataType.POI, 0, 0));
    }

    @Test
    void storedEntitiesAreHiddenInUnreadableChunks() {
        assertFalse(this.world.hidesEntities(5, 5));
        this.world.quarantine(ChunkDataType.CHUNK, 5, 5);
        assertTrue(this.world.hidesEntities(5, 5));
        this.world.writeScheduled(ChunkDataType.ENTITIES, 5, 5);
        assertFalse(this.world.hidesEntities(5, 5)); // they are the session's own by then
    }

    @Test
    void mergeThatCanNeverBeReadDoesNotBlockTheWorld() {
        this.world.retainEntities(1, 1, entities("pig"));
        this.backend.readFailure = FAILURES.getFirst();
        for (int i = 1; i < StoredWorld.MERGE_ATTEMPTS_BEFORE_SAVING_WITHOUT; i++) {
            assertTrue(this.world.save().isCompletedExceptionally());
        }
        this.world.save().join();
        assertEquals(1, this.backend.saves.get());
        this.world.close(true);
        assertFalse(this.world.awaitClose()); // and says what it left out
    }

    @Test
    void mergeChecksThePosition() throws IOException {
        final CompoundTag elsewhere = entities("cow");
        elsewhere.store("Position", ChunkPos.CODEC, new ChunkPos(7, 7));
        final StoredData stored = StoredNbt.encode(elsewhere);
        this.backend.chunks.put(FakeWorldStorage.id(ChunkDataType.ENTITIES, 1, 1), stored);
        this.world.retainEntities(1, 1, entities("pig"));
        this.world.save().join();
        assertEquals(stored, this.stored(ChunkDataType.ENTITIES, 1, 1));
    }

    @Test
    void whatCantBeSerializedKeepsTheWorldLoadedButSavesTheRest() {
        this.world.notEncoded(ChunkDataType.CHUNK, 2, 2);
        this.world.save().join();
        assertEquals(1, this.backend.saves.get());
        assertFalse(this.world.awaitLastSave());

        this.world.writeChunk(ChunkDataType.CHUNK, 2, 2, NEW, false);
        this.world.save().join();
        assertTrue(this.world.awaitLastSave());
    }

    @Test
    void anOlderQueuedWriteDoesNotClearANewerFailure() {
        final boolean older = this.world.writeScheduled(ChunkDataType.CHUNK, 2, 2);
        this.world.notCaptured(ChunkDataType.CHUNK, 2, 2);
        this.world.writeChunk(ChunkDataType.CHUNK, 2, 2, OLD, older);
        this.world.save().join();
        assertFalse(this.world.awaitLastSave());

        this.scheduleAndWrite(ChunkDataType.CHUNK, 2, 2, NEW);
        this.world.save().join();
        assertTrue(this.world.awaitLastSave());
    }

    @Test
    void refusingToUnloadEnds() {
        this.world.notEncoded(ChunkDataType.CHUNK, 2, 2);
        for (int i = 0; i < StoredWorld.MAX_REFUSED_UNLOADS; i++) {
            this.world.save().join();
            assertFalse(this.world.awaitLastSave());
        }
        this.world.save().join();
        assertTrue(this.world.awaitLastSave());
        this.world.close(true);
        assertFalse(this.world.awaitClose());
    }

    @Test
    void closingThatWentWrongIsNotReportedAsSaved() {
        this.world.close(true, false);
        assertEquals(0, this.backend.saves.get());
        assertFalse(this.world.awaitClose());
    }

    private static CompoundTag entities(final String... names) {
        final ListTag list = new ListTag();
        for (final String name : names) {
            final CompoundTag entity = new CompoundTag();
            entity.putString("id", "minecraft:" + name);
            list.add(entity);
        }
        final CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        tag.put("Entities", list);
        return tag;
    }

    @Test
    void entitiesThatCouldNotBeMergedAreMergedBeforeSaving() throws IOException {
        this.scheduleAndWrite(ChunkDataType.ENTITIES, 1, 1, StoredNbt.encode(entities("cow")));
        this.world.retainEntities(1, 1, entities("pig"));
        this.world.retainEntities(1, 1, entities("bat"));

        this.backend.readFailure = FAILURES.getFirst();
        assertTrue(this.world.save().isCompletedExceptionally());
        assertEquals(0, this.backend.saves.get());

        this.backend.readFailure = null;
        assertEquals(entities("cow", "pig", "bat"), StoredNbt.decode(this.world.readChunk(ChunkDataType.ENTITIES, 1, 1)));
        this.world.save().join();
        assertEquals(entities("cow", "pig", "bat"), StoredNbt.decode(this.stored(ChunkDataType.ENTITIES, 1, 1)));
    }

    @Test
    void unreadableMergeTargetDoesNotBlockSavingForever() {
        final StoredData garbage = StoredData.copyOf(new byte[]{10, 0});
        this.scheduleAndWrite(ChunkDataType.ENTITIES, 1, 1, garbage);
        this.world.retainEntities(1, 1, entities("pig"));
        this.world.save().join();
        assertEquals(garbage, this.stored(ChunkDataType.ENTITIES, 1, 1));
        this.world.close(true);
        assertFalse(this.world.awaitClose()); // reported
    }

    @Test
    void readsWorkDuringSave() {
        this.backend.saveResult = () -> {
            // a save that causes a chunk to load waits for an IO thread, which reads
            CompletableFuture.runAsync(() -> {
                try {
                    this.world.readChunk(ChunkDataType.CHUNK, 9, 9);
                } catch (final IOException ex) {
                    throw new IllegalStateException(ex);
                }
            }).orTimeout(5, TimeUnit.SECONDS).join();
            return CompletableFuture.completedFuture(null);
        };
        this.world.save().join(); // would fail with the timeout
    }

    @Test
    void failingKeysDoNotStarveTheRest() {
        this.backend.writeFailure = FAILURES.getFirst();
        for (int x = 0; x < 40; x++) {
            this.world.writeChunk(ChunkDataType.CHUNK, x, 0, NEW, false);
        }
        this.backend.writeFailure = null;
        this.backend.rejectX = x -> x < 8;
        this.world.close(true);
        assertEquals(32, this.backend.chunks.size());
    }

    @Test
    void onlyOneCanClose() throws InterruptedException {
        assertTrue(this.world.beginClose());
        assertFalse(this.world.beginClose());
        assertFalse(this.world.canSave());
        this.world.saveChunks(true, () -> {
            throw new AssertionError("saved into a halting chunk system");
        });
        final AtomicReference<Boolean> seen = new AtomicReference<>();
        final Thread waiter = Thread.ofPlatform().start(() -> seen.set(this.world.awaitClose()));
        waiter.join(300);
        assertTrue(waiter.isAlive());
        this.world.close(true);
        waiter.join();
        assertEquals(Boolean.TRUE, seen.get());
    }

    @Test
    void closingWaitsForAChunkSave() throws InterruptedException {
        final CountDownLatch saving = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final Thread saver = Thread.ofPlatform().start(() -> this.world.saveChunks(false, () -> {
            saving.countDown();
            try {
                finish.await();
            } catch (final InterruptedException ex) {
                throw new AssertionError(ex);
            }
        }));
        assertTrue(saving.await(5, TimeUnit.SECONDS));
        final Thread closer = Thread.ofPlatform().start(() -> this.world.beginClose());
        closer.join(300);
        assertTrue(closer.isAlive());
        finish.countDown();
        saver.join();
        closer.join();
        assertTrue(this.world.isClosing());
    }

    @Test
    void saveThatThrowsOrReturnsNullFails() {
        this.backend.saveResult = () -> null;
        assertTrue(this.world.save().isCompletedExceptionally());
        this.backend.saveResult = () -> {
            throw new NoClassDefFoundError("linkage");
        };
        assertTrue(this.world.save().isCompletedExceptionally());
    }

    @Test
    void closeWithoutSavingNeverSaves() {
        this.world.writeChunk(ChunkDataType.CHUNK, 0, 0, NEW, false);
        this.world.close(false);
        assertEquals(0, this.backend.saves.get());
        assertEquals(1, this.backend.closes.get());
        assertTrue(this.world.awaitClose());
        assertEquals(1, this.released.get());
    }

    @Test
    void closeSavesThenCloses() {
        this.world.close(true);
        assertEquals(List.of("save", "close"), this.backend.calls);
        assertTrue(this.world.awaitClose());
    }

    @Test
    void finalSaveRetriesABlip() {
        final AtomicInteger attempts = new AtomicInteger();
        this.backend.saveResult = () -> attempts.incrementAndGet() == 1
            ? CompletableFuture.failedFuture(new IOException("blip"))
            : CompletableFuture.completedFuture(null);
        this.world.close(true);
        assertEquals(2, attempts.get());
        assertTrue(this.world.awaitClose());
    }

    @Test
    void failedFinalSaveIsReportedAndStillCloses() {
        this.backend.saveResult = () -> CompletableFuture.failedFuture(new IOException("down"));
        this.world.close(true);
        assertFalse(this.world.awaitClose());
        assertEquals(1, this.backend.closes.get());
        assertEquals(1, this.released.get());
    }

    @Test
    void nothingReachesTheStorageAfterClose() {
        this.world.close(false);
        this.backend.calls.clear();
        this.world.writeChunk(ChunkDataType.CHUNK, 0, 0, NEW, false);
        this.world.writeData(KEY, NEW);
        assertThrows(IOException.class, () -> this.world.readChunk(ChunkDataType.CHUNK, 0, 0));
        assertThrows(IOException.class, () -> this.world.readData(KEY));
        this.world.save().join();
        this.world.close(true);
        assertEquals(List.of(), this.backend.calls);
        assertEquals(1, this.released.get());
    }

    @Test
    void closeWaitsForSavesInProgress() throws InterruptedException {
        final CompletableFuture<Void> saving = new CompletableFuture<>();
        this.backend.saveResult = () -> saving;
        this.world.save();
        final Thread closer = Thread.ofPlatform().start(() -> this.world.close(false));
        closer.join(300);
        assertTrue(closer.isAlive());
        assertEquals(0, this.backend.closes.get());
        saving.complete(null);
        closer.join();
        assertEquals(1, this.backend.closes.get());
    }

    @Test
    void saveDoesNotWaitForItsFuture() {
        this.backend.saveResult = CompletableFuture::new;
        assertFalse(this.world.save().isDone());
    }

    @Test
    void saveWaitsForWritesInProgress() throws InterruptedException {
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        this.backend.onWrite = () -> {
            writing.countDown();
            try {
                finish.await();
            } catch (final InterruptedException ex) {
                throw new AssertionError(ex);
            }
        };
        final Thread writer = Thread.ofPlatform().start(() -> this.world.writeChunk(ChunkDataType.CHUNK, 0, 0, NEW, false));
        assertTrue(writing.await(5, TimeUnit.SECONDS));
        final Thread saver = Thread.ofPlatform().start(() -> this.world.save());
        saver.join(300);
        assertTrue(saver.isAlive());
        assertEquals(0, this.backend.saves.get());
        finish.countDown();
        writer.join();
        saver.join();
        assertEquals(1, this.backend.saves.get());
    }

    @Test
    void callbacksCannotSaveOrCloseTheirWorld() {
        final AtomicReference<Throwable> saving = new AtomicReference<>();
        final AtomicReference<Throwable> closing = new AtomicReference<>();
        this.backend.onWrite = () -> {
            try {
                this.world.save();
            } catch (final IllegalStateException ex) {
                saving.set(ex);
            }
            try {
                this.world.close(true);
            } catch (final IllegalStateException ex) {
                closing.set(ex);
            }
        };
        this.world.writeChunk(ChunkDataType.CHUNK, 0, 0, NEW, false);
        assertTrue(saving.get() != null && closing.get() != null);
    }
}
