package sh.btx.archipelago.storage;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.IntArrayBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.AllFeatures;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@AllFeatures
@Timeout(30)
class StoredMapsTest {

    private static final ResourceKey<Level> ISLAND = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("islands", "a"));
    private static final ResourceKey<Level> OTHER = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("islands", "b"));

    /**
     * Shared by "servers", like one database.
     */
    private static final class Backend implements MapStorage {

        final Map<Integer, StoredData> maps = new ConcurrentHashMap<>();
        final AtomicInteger nextId = new AtomicInteger();
        final AtomicInteger loads = new AtomicInteger();
        final AtomicInteger saves = new AtomicInteger();
        final Set<Integer> busy = ConcurrentHashMap.newKeySet();
        final List<String> overlaps = new CopyOnWriteArrayList<>();
        volatile boolean failReads;
        volatile boolean failWrites;
        volatile boolean failIds;
        volatile boolean closed;

        private void enter(final int id) {
            if (!this.busy.add(id)) {
                this.overlaps.add("map " + id);
            }
        }

        @Override
        public int nextMapId() throws IOException {
            if (this.failIds) {
                throw new IOException("down");
            }
            return this.nextId.getAndIncrement();
        }

        @Override
        public @Nullable StoredData loadMap(final int mapId) throws IOException {
            this.enter(mapId);
            try {
                this.loads.incrementAndGet();
                if (this.failReads) {
                    throw new IOException("down");
                }
                return this.maps.get(mapId);
            } finally {
                this.busy.remove(mapId);
            }
        }

        @Override
        public void saveMap(final int mapId, final StoredData data) throws IOException {
            this.enter(mapId);
            try {
                if (this.failWrites) {
                    throw new IOException("down");
                }
                this.saves.incrementAndGet();
                this.maps.put(mapId, data);
            } finally {
                this.busy.remove(mapId);
            }
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }

    private final Backend backend = new Backend();
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final Set<ResourceKey<Level>> loadedHere = ConcurrentHashMap.newKeySet();
    private final Set<ResourceKey<Level>> loadedThere = ConcurrentHashMap.newKeySet();
    private final AtomicInteger initialized = new AtomicInteger();
    private StoredMaps here;
    private StoredMaps there;
    private @Nullable Object previousServer;

    private StoredMaps server(final Set<ResourceKey<Level>> loaded) {
        return new StoredMaps(this.backend, loaded::contains, RegistryHelper::registryAccess, data -> this.initialized.incrementAndGet(), this.now::get);
    }

    @BeforeEach
    void setUp() throws Exception {
        // the map codec asks the server which worlds are loaded, to add their UUIDs
        final Field field = MinecraftServer.class.getDeclaredField("SERVER");
        field.setAccessible(true);
        this.previousServer = field.get(null);
        field.set(null, mock(MinecraftServer.class));
        this.here = this.server(this.loadedHere);
        this.there = this.server(this.loadedThere);
        this.loadedHere.add(ISLAND);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.here.close();
        this.there.close();
        final Field field = MinecraftServer.class.getDeclaredField("SERVER");
        field.setAccessible(true);
        field.set(null, this.previousServer);
        assertEquals(List.of(), this.backend.overlaps, "calls for the same map overlapped");
    }

    private void advance(final long seconds) {
        this.now.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    private static MapItemSavedData fresh(final ResourceKey<Level> dimension) {
        return MapItemSavedData.createFresh(0, 0, (byte) 0, true, false, dimension);
    }

    private MapId create(final StoredMaps maps, final ResourceKey<Level> dimension) {
        final MapId id = maps.nextId();
        maps.set(id, fresh(dimension));
        return id;
    }

    private void awaitStored(final StoredMaps maps) {
        maps.save(true);
    }

    private CompoundBinaryTag stored(final MapId id) throws IOException {
        final StoredData data = this.backend.maps.get(id.id());
        assertNotNull(data, "map " + id.id() + " is not stored");
        return data.toCompound();
    }

    private static byte storedColor(final CompoundBinaryTag map, final int index) {
        return map.getCompound("data").getByteArray("colors")[index];
    }

    @Test
    void newMapsAreStoredRightAway() throws IOException {
        final MapId id = this.create(this.here, OTHER); // even for a world that isn't loaded here
        this.awaitStored(this.here);

        final CompoundBinaryTag map = this.stored(id);
        assertEquals("islands:b", map.getCompound("data").getString("dimension"));
        assertEquals(SharedConstants.getCurrentVersion().dataVersion().version(), map.getInt("DataVersion"));
        assertEquals(1, this.backend.saves.get());
    }

    @Test
    void idsAreReservedAheadAndUniqueAcrossServers() throws Exception {
        final Set<Integer> ids = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            assertTrue(ids.add(this.create(i % 2 == 0 ? this.here : this.there, ISLAND).id()), "an ID was handed out twice");
        }
        this.awaitStored(this.here);
        this.awaitStored(this.there);
        assertTrue(this.backend.nextId.get() <= 40 + 2 * StoredMaps.RESERVED_IDS, "reserves too many: " + this.backend.nextId.get());
    }

    @Test
    void anIdThatIsAlreadyUsedIsRefused() {
        this.there.set(new MapId(0), fresh(ISLAND));
        this.awaitStored(this.there);
        final StoredMaps fresh = this.server(this.loadedHere);
        try {
            // a broken storage that hands out an ID again
            assertNotNull(fresh.get(new MapId(0)));
            assertThrows(StorageException.class, fresh::nextId);
        } finally {
            fresh.close();
        }
    }

    @Test
    void noIdMeansNoMapAndTheEmptyMapIsKept() {
        this.backend.failIds = true;
        final StoredMaps fresh = this.server(this.loadedHere);
        try {
            assertFalse(fresh.reserveId());
            assertThrows(StorageException.class, fresh::nextId);
            this.backend.failIds = false;
            assertTrue(fresh.reserveId());
            final int reserved = this.backend.nextId.get() - 1;
            assertEquals(reserved, fresh.nextId().id(), "the reserved ID is used next");
        } finally {
            fresh.close();
        }
    }

    @Test
    void onlyChangedMapsOfLoadedWorldsAreSaved() throws IOException {
        final MapId own = this.create(this.here, ISLAND);
        final MapId foreign = this.create(this.here, OTHER);
        this.awaitStored(this.here);
        final int saves = this.backend.saves.get();

        this.here.save(true);
        assertEquals(saves, this.backend.saves.get(), "nothing changed");

        Objects.get(this.here, own).setColor(1, 0, (byte) 7);
        Objects.get(this.here, foreign).setColor(1, 0, (byte) 9);
        this.here.save(true);
        assertEquals(saves + 1, this.backend.saves.get());
        assertEquals(7, storedColor(this.stored(own), 1));
        assertEquals(0, storedColor(this.stored(foreign), 1), "a map is saved by the server that has its world");
    }

    @Test
    void readsAreCachedAndMissingMapsAreTriedAgain() throws IOException {
        final MapId id = new MapId(5);
        assertNull(this.here.get(id));
        assertNull(this.here.get(id));
        assertEquals(1, this.backend.loads.get());

        this.there.set(id, fresh(ISLAND)); // created by another server, with the ID it got
        this.awaitStored(this.there);
        this.advance(11);
        final MapItemSavedData data = this.here.get(id);
        assertNotNull(data);
        assertSame(data, this.here.get(id));
        assertEquals(2, this.backend.loads.get());
        assertEquals(1, this.initialized.get(), "the event is called when it is loaded");
    }

    @Test
    void aMapThatCantBeReadIsNeverReplaced() throws IOException {
        final MapId id = this.create(this.there, ISLAND);
        this.awaitStored(this.there);
        final StoredData before = this.backend.maps.get(id.id());

        this.backend.failReads = true;
        assertNull(this.here.get(id));
        this.backend.failReads = false;
        assertNull(this.here.get(id), "not tried again right away");
        this.here.save(true);
        assertEquals(before, this.backend.maps.get(id.id()));

        this.advance(61);
        assertNotNull(this.here.get(id));
    }

    @Test
    void newerDataIsRefused() throws IOException {
        this.backend.maps.put(3, StoredData.of(CompoundBinaryTag.builder().putInt("DataVersion", 999_999).put("data", CompoundBinaryTag.empty()).build()));
        assertNull(this.here.get(new MapId(3)));
        this.here.save(true);
        assertEquals(999_999, this.backend.maps.get(3).toCompound().getInt("DataVersion"));
    }

    @Test
    void failedSavesAreKeptAndKeepTheWorldLoaded() throws IOException {
        final MapId id = this.create(this.here, ISLAND);
        this.awaitStored(this.here);
        this.backend.failWrites = true;
        Objects.get(this.here, id).setColor(2, 0, (byte) 4);
        this.here.save(true);
        assertTrue(this.here.hasUnsaved());
        assertFalse(this.here.saveWorld(ISLAND), "the next server would show the map as it was");
        assertTrue(this.here.saveWorld(OTHER), "other worlds are not held up");
        assertEquals(4, Objects.get(this.here, id).colors[2], "still served");

        this.backend.failWrites = false;
        assertTrue(this.here.saveWorld(ISLAND));
        assertFalse(this.here.hasUnsaved());
        assertEquals(4, storedColor(this.stored(id), 2));
    }

    @Test
    void aWorldThatMovesBackComesWithItsNewerMaps() throws IOException {
        // A had the island and its map, then it moved to B, which changed the map, then it came back to A
        final MapId id = this.create(this.here, ISLAND);
        final MapItemSavedData onA = Objects.get(this.here, id);
        onA.setColor(0, 0, (byte) 1);
        assertTrue(this.here.saveWorld(ISLAND));
        this.loadedHere.remove(ISLAND);

        this.loadedThere.add(ISLAND);
        this.there.worldLoaded(ISLAND);
        final MapItemSavedData onB = Objects.get(this.there, id);
        assertEquals(1, onB.colors[0]);
        onB.setColor(0, 0, (byte) 2);
        onB.centerX = 256;
        assertTrue(this.there.saveWorld(ISLAND));
        this.loadedThere.remove(ISLAND);

        this.loadedHere.add(ISLAND);
        this.here.worldLoaded(ISLAND);
        assertSame(onA, Objects.get(this.here, id), "plugins and players hold the map");
        assertEquals(2, onA.colors[0]);
        assertEquals(256, onA.centerX);
        assertFalse(onA.isDirty());

        onA.setColor(1, 0, (byte) 3);
        assertTrue(this.here.saveWorld(ISLAND));
        final CompoundBinaryTag map = this.stored(id);
        assertEquals(2, storedColor(map, 0), "what B saved is not replaced");
        assertEquals(3, storedColor(map, 1));
    }

    @Test
    void mapsOfWorldsElsewhereAreReadAgain() {
        this.loadedThere.add(OTHER);
        final MapId id = this.create(this.there, OTHER);
        this.awaitStored(this.there);
        final MapItemSavedData onA = Objects.get(this.here, id);

        Objects.get(this.there, id).setColor(5, 0, (byte) 6);
        this.there.save(true);
        assertEquals(0, onA.colors[5]);

        this.advance(61);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (onA.colors[5] != 6 && System.nanoTime() < deadline) {
            assertSame(onA, Objects.get(this.here, id)); // starts reading again, then takes what was read
            StorageTestTime.sleep(10);
        }
        assertEquals(6, onA.colors[5]);
    }

    @Test
    void readingAgainFailsSafely() {
        final MapId id = this.create(this.here, ISLAND);
        this.awaitStored(this.here);
        this.backend.failReads = true;
        this.here.worldLoaded(ISLAND);
        assertNull(this.here.get(id), "an old copy would be saved over newer data");
        this.backend.failReads = false;
        this.advance(61);
        assertNotNull(this.here.get(id));
    }

    @Test
    void unsavedMapsAreNotReadAgain() {
        final MapId id = this.create(this.here, ISLAND);
        this.awaitStored(this.here);
        this.backend.failWrites = true;
        final MapItemSavedData data = Objects.get(this.here, id);
        data.setColor(0, 0, (byte) 8);
        this.here.save(true);
        this.here.worldLoaded(ISLAND);
        assertSame(data, Objects.get(this.here, id));
        assertEquals(8, data.colors[0]);
        this.backend.failWrites = false;
    }

    @Test
    void bannersComeWithTheMap() throws IOException {
        final MapId id = this.create(this.there, ISLAND);
        this.awaitStored(this.there);
        final MapItemSavedData onA = Objects.get(this.here, id);
        final CompoundBinaryTag map = this.stored(id);
        final CompoundBinaryTag banner = CompoundBinaryTag.builder()
            .put("pos", IntArrayBinaryTag.intArrayBinaryTag(10, 64, 10))
            .putString("color", "red")
            .build();
        this.backend.maps.put(id.id(), StoredData.of(map.put("data", map.getCompound("data").put("banners", ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, List.of(banner))))));

        this.here.worldLoaded(ISLAND);
        assertEquals(1, onA.getBanners().size());
        assertEquals(1, onA.decorations.size());

        this.backend.maps.put(id.id(), StoredData.of(map));
        this.here.worldLoaded(ISLAND);
        assertEquals(0, onA.getBanners().size());
        assertEquals(0, onA.decorations.size(), "removed banners leave no marker");
    }

    @Test
    void closingSavesWhatIsLeftAndClosesTheStorage() throws IOException {
        this.backend.failWrites = true;
        final MapId id = this.create(this.here, ISLAND);
        this.awaitStored(this.here);
        assertTrue(this.here.hasUnsaved());
        new Thread(() -> {
            StorageTestTime.sleep(1500);
            this.backend.failWrites = false;
        }).start();
        this.here.close();
        assertTrue(this.backend.closed);
        assertNotNull(this.backend.maps.get(id.id()), "retried while closing");
        assertNull(this.here.get(new MapId(99)), "nothing is read after closing");
    }

    private static final class Objects {
        static MapItemSavedData get(final StoredMaps maps, final MapId id) {
            final MapItemSavedData data = maps.get(id);
            assertNotNull(data, "map " + id.id());
            return data;
        }
    }

    private static final class StorageTestTime {
        static void sleep(final long millis) {
            try {
                Thread.sleep(millis);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void colorsAreCopiedWhenSaved() throws IOException {
        final MapId id = this.create(this.here, ISLAND);
        final MapItemSavedData data = Objects.get(this.here, id);
        data.setColor(0, 0, (byte) 1);
        this.here.save(false);
        data.colors[0] = 2; // after it was captured, maybe before it is written, and not marked as changed
        this.here.save(true);
        final byte[] expected = new byte[16384];
        expected[0] = 1;
        assertArrayEquals(expected, this.stored(id).getCompound("data").getByteArray("colors"));
    }
}
