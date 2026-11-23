package sh.btx.archipelago.storage;

import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.executor.queue.AreaDependentQueue;
import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Sends one type of a world's chunk IO to its storage, in place of region files. Being a controller keeps everything
 * the chunk system does above this: reads of pending writes, priorities, cancellation and flushing.
 */
@NullMarked
final class StorageDataController extends MoonriseRegionFileIO.RegionDataController {

    private final StoredWorld world;
    private final MoonriseRegionFileIO.RegionDataController vanilla;
    private final WorldStorage.ChunkDataType dataType;
    private final AreaDependentQueue queue;

    StorageDataController(
        final StoredWorld world,
        final MoonriseRegionFileIO.RegionDataController vanilla,
        final PrioritisedExecutor ioExecutor,
        final PrioritisedExecutor compressionExecutor
    ) {
        super(vanilla.type, ioExecutor, compressionExecutor);
        this.world = world;
        this.vanilla = vanilla;
        this.dataType = dataType(vanilla.type);
        this.queue = new AreaDependentQueue(ioExecutor, 5); // 5 is only the granularity of its locks
    }

    static WorldStorage.ChunkDataType dataType(final MoonriseRegionFileIO.RegionFileType type) {
        return switch (type) {
            case CHUNK_DATA -> WorldStorage.ChunkDataType.CHUNK;
            case ENTITY_DATA -> WorldStorage.ChunkDataType.ENTITIES;
            case POI_DATA -> WorldStorage.ChunkDataType.POI;
        };
    }

    // region files need one task per region at a time, storages only one per chunk
    @Override
    public PrioritisedExecutor.PrioritisedTask createRegionIoTask(final int chunkX, final int chunkZ, final Runnable run, final Priority priority) {
        return this.queue.createTask(chunkX, chunkZ, 0, run, priority);
    }

    // only used to flush and close. It opens and creates nothing as long as it isn't read or written
    @Override
    public RegionFileStorage getCache() {
        return this.vanilla.getCache();
    }

    @Override
    public boolean writeScheduled(final int chunkX, final int chunkZ) {
        return this.world.writeScheduled(this.dataType, chunkX, chunkZ);
    }

    @Override
    public void serializationFailed(final int chunkX, final int chunkZ, final Throwable throwable) {
        if (!StorageHooks.wasRetained(throwable)) {
            this.world.notEncoded(this.dataType, chunkX, chunkZ);
        }
    }

    @Override
    public WriteData startWrite(final int chunkX, final int chunkZ, final @Nullable CompoundTag compound) throws IOException {
        return this.startWrite(chunkX, chunkZ, compound, false);
    }

    @Override
    public WriteData startWrite(final int chunkX, final int chunkZ, final @Nullable CompoundTag compound, final boolean notToBeStored) throws IOException {
        final StoredData data;
        if (compound == null) {
            data = null;
        } else {
            try {
                checkPosition(this.dataType, chunkX, chunkZ, compound);
                data = StoredNbt.encode(compound);
            } catch (final IOException ex) {
                this.world.notEncoded(this.dataType, chunkX, chunkZ, notToBeStored);
                throw ex;
            } catch (final Throwable thr) { // only IOExceptions keep the data in memory
                this.world.notEncoded(this.dataType, chunkX, chunkZ, notToBeStored);
                throw new IOException("Could not encode " + this.dataType + " of chunk " + chunkX + ", " + chunkZ, thr);
            }
        }
        return new WriteData(
            compound,
            data == null ? WriteData.WriteResult.DELETE : WriteData.WriteResult.WRITE,
            null,
            regionFile -> this.world.writeChunk(this.dataType, chunkX, chunkZ, data, notToBeStored)
        );
    }

    @Override
    public void finishWrite(final int chunkX, final int chunkZ, final WriteData writeData) throws IOException {
        writeData.write().run(null);
    }

    @Override
    public ReadData readData(final int chunkX, final int chunkZ) throws IOException {
        final StoredData data = this.world.readChunk(this.dataType, chunkX, chunkZ);
        return data == null
            ? new ReadData(ReadData.ReadResult.NO_DATA, null, null, 0)
            : new ReadData(ReadData.ReadResult.HAS_DATA, new DataInputStream(new ByteArrayInputStream(data.bytes())), null, 0);
    }

    // must not return null, that means to read again
    @Override
    public CompoundTag finishRead(final int chunkX, final int chunkZ, final ReadData readData) throws IOException {
        try (DataInputStream in = readData.input()) {
            final CompoundTag tag = StoredNbt.decode(in);
            checkPosition(this.dataType, chunkX, chunkZ, tag);
            return this.dataType == WorldStorage.ChunkDataType.ENTITIES ? StoredWorld.upgradeEntities(tag) : tag;
        } catch (final IOException ex) {
            throw ex;
        } catch (final Throwable thr) {
            throw new IOException("Could not decode " + this.dataType + " of chunk " + chunkX + ", " + chunkZ, thr);
        }
    }

    static void checkPosition(final WorldStorage.ChunkDataType type, final int chunkX, final int chunkZ, final CompoundTag tag) throws IOException {
        final ChunkPos stored = switch (type) {
            case CHUNK -> SerializableChunkData.getChunkCoordinate(tag);
            case ENTITIES -> tag.read("Position", ChunkPos.CODEC).orElse(null);
            case POI -> null; // has no position
        };
        if (stored != null && (stored.x() != chunkX || stored.z() != chunkZ)) {
            throw new IOException(type + " data of chunk " + stored + " cannot be used for chunk " + chunkX + ", " + chunkZ);
        }
    }
}
