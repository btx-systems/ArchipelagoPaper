package sh.btx.archipelago.storage;

import ca.spottedleaf.concurrentutil.completable.Completable;
import ca.spottedleaf.concurrentutil.executor.thread.BalancedPrioritisedThreadPool;
import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionFileType;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import io.papermc.paper.configuration.WorldConfiguration;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import sh.btx.archipelago.storage.WorldStorage.ChunkDataType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Races between loading, merging and writing, on the real MoonriseRegionFileIO with a mocked level.
 */
@AllFeatures
@Timeout(30)
class StorageChunkIoTest {

    private static final String ID = FakeWorldStorage.id(ChunkDataType.ENTITIES, 0, 0);

    private FakeWorldStorage backend;
    private StoredWorld world;
    private ServerLevel level;
    private StorageDataController controller;
    private BalancedPrioritisedThreadPool ioPool;
    private BalancedPrioritisedThreadPool compressionPool;
    private BalancedPrioritisedThreadPool.OrderedStreamGroup.Queue io;

    private static BalancedPrioritisedThreadPool pool() {
        return new BalancedPrioritisedThreadPool(1_000_000L, run -> {
            final Thread thread = new Thread(run);
            thread.setDaemon(true);
            return thread;
        });
    }

    @BeforeEach
    void setUp() {
        this.backend = new FakeWorldStorage();
        this.world = new StoredWorld(Identifier.fromNamespaceAndPath("test", "world"), this.backend, closed -> {});

        this.ioPool = pool();
        this.compressionPool = pool();
        this.io = this.ioPool.createOrderedStreamGroup().createExecutor();
        final var compression = this.compressionPool.createOrderedStreamGroup().createExecutor();
        this.ioPool.adjustThreadCount(1);
        this.compressionPool.adjustThreadCount(2);

        final var vanilla = new MoonriseRegionFileIO.RegionDataController(RegionFileType.ENTITY_DATA, null, null) {
            @Override
            public RegionFileStorage getCache() {
                return null;
            }

            @Override
            public WriteData startWrite(final int x, final int z, final CompoundTag tag) {
                throw new AssertionError();
            }

            @Override
            public void finishWrite(final int x, final int z, final WriteData data) {
                throw new AssertionError();
            }

            @Override
            public ReadData readData(final int x, final int z) {
                throw new AssertionError();
            }

            @Override
            public CompoundTag finishRead(final int x, final int z, final ReadData data) {
                throw new AssertionError();
            }
        };
        this.controller = new StorageDataController(this.world, vanilla, this.io, compression);

        this.level = Mockito.mock(ServerLevel.class);
        Mockito.when(this.level.moonrise$getEntityChunkDataController()).thenReturn(this.controller);
        final WorldConfiguration config = Mockito.mock(WorldConfiguration.class);
        config.chunks = Mockito.mock(WorldConfiguration.Chunks.class);
        Mockito.when(this.level.paperConfig()).thenReturn(config);
    }

    @AfterEach
    void tearDown() {
        this.ioPool.halt(false);
        this.compressionPool.halt(false);
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

    private CountDownLatch holdIo() throws InterruptedException {
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        this.io.queueTask(() -> {
            held.countDown();
            try {
                release.await();
            } catch (final InterruptedException ex) {
                throw new AssertionError(ex);
            }
        });
        assertTrue(held.await(5, TimeUnit.SECONDS));
        return release;
    }

    private void write(final CompoundTag tag) {
        MoonriseRegionFileIO.scheduleSave(this.level, 0, 0, tag, RegionFileType.ENTITY_DATA);
    }

    // what NewChunkHolder#saveEntities does for transient chunks
    private void mergeTransient(final CompoundTag save) {
        final boolean mergeIntoNothing = this.world.hidesStored(ChunkDataType.ENTITIES, 0, 0);
        final Completable<CompoundTag> from = MoonriseRegionFileIO.loadDataAsync(
            this.level, 0, 0, RegionFileType.ENTITY_DATA, false, Priority.NORMAL
        );
        final Completable<CompoundTag> merged = from.handle((stored, thr) -> {
            if (thr != null) {
                throw new IllegalStateException(thr);
            }
            if (!mergeIntoNothing) {
                ChunkEntitySlices.copyEntities(stored, save);
            }
            return save;
        });
        MoonriseRegionFileIO.scheduleSave(this.level, 0, 0, merged, null, RegionFileType.ENTITY_DATA, Priority.NORMAL);
    }

    private void flush() {
        MoonriseRegionFileIO.flush(this.level, RegionFileType.ENTITY_DATA);
    }

    private CompoundTag stored() throws IOException {
        return StoredNbt.decode(this.backend.chunks.get(ID));
    }

    @Test
    void quarantineWhileAMergeIsQueuedKeepsStoredEntities() throws Exception {
        this.backend.chunks.put(ID, StoredNbt.encode(entities("cow", "pig")));
        final CountDownLatch release = this.holdIo();
        this.mergeTransient(entities("bat"));
        this.world.quarantine(ChunkDataType.CHUNK, 0, 0);
        release.countDown();
        this.flush();
        assertEquals(entities("cow", "pig", "bat"), this.stored());
    }

    @Test
    void mergingIntoAQuarantinedChunkDoesNotExposeAPendingOriginal() throws Exception {
        final CountDownLatch release = this.holdIo();
        this.write(entities("cow"));
        this.world.quarantine(ChunkDataType.CHUNK, 0, 0);
        this.mergeTransient(entities("bat"));
        release.countDown();
        this.flush();
        assertEquals(entities("cow"), this.stored());
        assertEquals(entities("bat"), StoredNbt.decode(this.world.readChunk(ChunkDataType.ENTITIES, 0, 0)));
    }

    @Test
    void writeThatIsNotStoredDoesNotSwallowOneThatIs() throws Exception {
        final CountDownLatch release = this.holdIo();
        this.write(entities("cow"));
        this.write(entities("pig")); // supersedes it while queued
        this.world.quarantine(ChunkDataType.CHUNK, 0, 0);
        this.write(entities("bat"));
        this.write(entities("fox"));
        release.countDown();
        this.flush();
        assertEquals(entities("pig"), this.stored());
        assertEquals(entities("fox"), StoredNbt.decode(this.world.readChunk(ChunkDataType.ENTITIES, 0, 0)));
    }
}
