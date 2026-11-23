package sh.btx.archipelago.storage;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AllFeatures
class PlayerStorageBridgeTest {

    private static final class Backend implements PlayerStorage {

        final Map<UUID, StoredData> data = new HashMap<>();
        final Map<UUID, String> statistics = new HashMap<>();
        final Map<UUID, String> advancements = new HashMap<>();
        boolean failReads;
        boolean failWrites;
        boolean closed;
        java.util.function.Predicate<UUID> rejects = id -> false;

        private void read() throws IOException {
            if (this.failReads) {
                throw new IOException("down");
            }
        }

        private void write() throws IOException {
            if (this.failWrites) {
                throw new IOException("down");
            }
        }

        @Override
        public @Nullable StoredData loadData(final UUID id) throws IOException {
            this.read();
            return this.data.get(id);
        }

        @Override
        public void saveData(final UUID id, final StoredData value) throws IOException {
            this.write();
            if (this.rejects.test(id)) {
                throw new IOException("rejected");
            }
            this.data.put(id, value);
        }

        @Override
        public @Nullable String loadStatistics(final UUID id) throws IOException {
            this.read();
            return this.statistics.get(id);
        }

        @Override
        public void saveStatistics(final UUID id, final String json) throws IOException {
            this.write();
            this.statistics.put(id, json);
        }

        @Override
        public @Nullable String loadAdvancements(final UUID id) throws IOException {
            this.read();
            return this.advancements.get(id);
        }

        @Override
        public void saveAdvancements(final UUID id, final String json) throws IOException {
            this.write();
            this.advancements.put(id, json);
        }

        @Override
        public Set<UUID> listPlayers() {
            return this.data.keySet();
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }

    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final StoredData value = StoredData.of(CompoundBinaryTag.builder().putInt("level", 42).build());
    private Backend backend;
    private PlayerStorageBridge bridge;

    @BeforeEach
    void setUp() {
        this.backend = new Backend();
        this.bridge = new PlayerStorageBridge(this.backend);
    }

    private static JsonObject json() {
        final JsonObject json = new JsonObject();
        json.addProperty("DataVersion", 1);
        return json;
    }

    @Test
    void failingToLoadIsNotANewPlayer() {
        assertNull(this.bridge.loadData(this.first));
        this.backend.failReads = true;
        assertThrows(StorageException.class, () -> this.bridge.loadData(this.first));
        assertThrows(StorageException.class, () -> this.bridge.loadStatistics(this.first));
        assertThrows(StorageException.class, () -> this.bridge.loadAdvancements(this.first));
    }

    @Test
    void failedSavesAreServedToLoads() {
        this.backend.failWrites = true;
        this.bridge.saveData(this.first, this.value);
        this.bridge.saveStatistics(this.first, json());
        this.bridge.saveAdvancements(this.first, json());
        this.backend.failReads = true; // not even asked
        assertEquals(this.value, this.bridge.loadData(this.first));
        assertEquals(json(), this.bridge.loadStatistics(this.first));
        assertEquals(json(), this.bridge.loadAdvancements(this.first));
    }

    @Test
    void failedSavesAreRetried() {
        this.backend.failWrites = true;
        this.bridge.saveData(this.first, this.value);
        this.bridge.saveStatistics(this.first, json());
        this.backend.failWrites = false;
        this.bridge.saveData(this.second, this.value);
        assertEquals(Set.of(this.second), this.backend.data.keySet()); // a save costs one call, not one per failure
        this.bridge.retryFailedSaves();
        assertEquals(Set.of(this.first, this.second), this.backend.data.keySet());
        assertEquals(Set.of(this.first), this.backend.statistics.keySet());
    }

    @Test
    void closingTriesEveryPlayer() {
        this.backend.failWrites = true;
        for (int i = 0; i < 100; i++) {
            final UUID id = new UUID(i, i);
            this.bridge.saveData(id, this.value);
        }
        this.backend.failWrites = false;
        this.backend.rejects = id -> id.getMostSignificantBits() < 40;
        this.bridge.close();
        assertEquals(60, this.backend.data.size());
    }

    @Test
    void closingRetriesFirst() {
        this.backend.failWrites = true;
        this.bridge.saveAdvancements(this.first, json());
        this.backend.failWrites = false;
        this.bridge.close();
        assertTrue(this.backend.closed);
        assertTrue(this.backend.advancements.containsKey(this.first));
        assertThrows(StorageException.class, () -> this.bridge.loadData(this.second));
    }

    @Test
    void unusableJsonIsRefused() {
        for (final String bad : new String[]{"", "[]", "{", "null", "{\"DataVersion\":999999}"}) {
            this.backend.statistics.put(this.first, bad);
            assertThrows(StorageException.class, () -> this.bridge.loadStatistics(this.first), bad);
        }
    }
}
