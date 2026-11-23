package sh.btx.archipelago.storage;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController.ReadData;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionFileType;
import java.io.IOException;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AllFeatures
class StorageDataControllerTest {

    private FakeWorldStorage backend;
    private StoredWorld world;

    @BeforeEach
    void setUp() {
        this.backend = new FakeWorldStorage();
        this.world = new StoredWorld(Identifier.fromNamespaceAndPath("test", "world"), this.backend, closed -> {});
    }

    private StorageDataController controller(final RegionFileType type) {
        return new StorageDataController(this.world, new MoonriseRegionFileIO.RegionDataController(type, null, null) {
            @Override
            public RegionFileStorage getCache() {
                throw new AssertionError();
            }

            @Override
            public WriteData startWrite(final int x, final int z, final CompoundTag tag) {
                throw new AssertionError("region files were used");
            }

            @Override
            public void finishWrite(final int x, final int z, final WriteData data) {
                throw new AssertionError("region files were used");
            }

            @Override
            public ReadData readData(final int x, final int z) {
                throw new AssertionError("region files were used");
            }

            @Override
            public CompoundTag finishRead(final int x, final int z, final ReadData data) {
                throw new AssertionError("region files were used");
            }
        }, null, null);
    }

    private static CompoundTag tag(final RegionFileType type, final int x, final int z) {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        switch (type) {
            case CHUNK_DATA -> {
                tag.putInt("xPos", x);
                tag.putInt("zPos", z);
            }
            case ENTITY_DATA -> tag.store("Position", ChunkPos.CODEC, new ChunkPos(x, z));
            case POI_DATA -> tag.put("Sections", new CompoundTag());
        }
        return tag;
    }

    private static CompoundTag read(final StorageDataController controller, final int x, final int z) throws IOException {
        final ReadData read = controller.readData(x, z);
        assertEquals(ReadData.ReadResult.HAS_DATA, read.result());
        return controller.finishRead(x, z, read);
    }

    @Test
    void everyTypeRoundTripsAndDeletes() throws IOException {
        for (final RegionFileType type : RegionFileType.values()) {
            final StorageDataController controller = this.controller(type);
            assertEquals(ReadData.ReadResult.NO_DATA, controller.readData(-5, 9).result());

            controller.finishWrite(-5, 9, controller.startWrite(-5, 9, tag(type, -5, 9)));
            assertEquals(tag(type, -5, 9), read(controller, -5, 9));

            controller.finishWrite(-5, 9, controller.startWrite(-5, 9, null));
            assertEquals(ReadData.ReadResult.NO_DATA, controller.readData(-5, 9).result());
        }
    }

    @Test
    void dataOfAnotherChunkIsNotUsed() throws IOException {
        for (final RegionFileType type : new RegionFileType[]{RegionFileType.CHUNK_DATA, RegionFileType.ENTITY_DATA}) {
            final StorageDataController controller = this.controller(type);
            this.backend.chunks.put(FakeWorldStorage.id(StorageDataController.dataType(type), 1, 1), StoredNbt.encode(tag(type, 2, 2)));
            assertThrows(IOException.class, () -> read(controller, 1, 1));
            assertThrows(IOException.class, () -> controller.startWrite(1, 1, tag(type, 2, 2)));
        }
    }

    @Test
    void newerDataIsRefusedBeforeItReachesTheParser() throws IOException {
        for (final RegionFileType type : RegionFileType.values()) {
            final CompoundTag tag = tag(type, 0, 0);
            tag.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version() + 1); // would System.exit
            this.backend.chunks.put(FakeWorldStorage.id(StorageDataController.dataType(type), 0, 0), StoredNbt.encode(tag));
            assertThrows(IOException.class, () -> read(this.controller(type), 0, 0));
        }
    }

    @Test
    void failedWriteFinishesNormallyAndIsServed() throws IOException {
        final StorageDataController controller = this.controller(RegionFileType.CHUNK_DATA);
        this.backend.writeFailure = () -> new IllegalStateException("down");
        controller.finishWrite(0, 0, controller.startWrite(0, 0, tag(RegionFileType.CHUNK_DATA, 0, 0))); // or flush would end with a failed task
        assertEquals(tag(RegionFileType.CHUNK_DATA, 0, 0), read(controller, 0, 0));
        assertTrue(this.backend.chunks.isEmpty());
    }

    @Test
    void oldEntitiesAreConvertedOnlyOnce() throws IOException {
        // from before text components were NBT. Converting twice parses what a player typed as a component
        final CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:text_display");
        entity.putString("text", "\"[Rare]\"");
        entity.putString("CustomName", "\"[Rare]\"");
        final net.minecraft.nbt.ListTag entities = new net.minecraft.nbt.ListTag();
        entities.add(entity);
        final CompoundTag old = new CompoundTag();
        old.putInt("DataVersion", 4189); // 1.21.4
        old.store("Position", ChunkPos.CODEC, new ChunkPos(0, 0));
        old.put("Entities", entities);

        this.backend.chunks.put(FakeWorldStorage.id(WorldStorage.ChunkDataType.ENTITIES, 0, 0), StoredNbt.encode(old));
        final CompoundTag loaded = read(this.controller(RegionFileType.ENTITY_DATA), 0, 0);

        assertEquals(convertAsTheLoadTaskDoes(old.copy()).get("Entities"), convertAsTheLoadTaskDoes(loaded).get("Entities"));
    }

    private static CompoundTag convertAsTheLoadTaskDoes(final CompoundTag tag) {
        return ca.spottedleaf.dataconverter.minecraft.MCDataConverter.convertTag(
            ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry.ENTITY_CHUNK, net.minecraft.util.datafix.DataFixTypes.ENTITY_CHUNK,
            tag, tag.getIntOr("DataVersion", -1), ca.spottedleaf.dataconverter.minecraft.util.Version.getCurrentVersion()
        );
    }
}
