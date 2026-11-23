package sh.btx.archipelago.storage;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.util.StrictJsonParser;
import org.bukkit.OfflinePlayer;
import org.bukkit.craftbukkit.CraftServer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The server's side of a {@link PlayerStorage}. Failing to load throws. What fails to save is kept, given to loads,
 * and retried.
 */
@NullMarked
public final class PlayerStorageBridge {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final Gson GSON = new Gson();

    @FunctionalInterface
    private interface Loader<T> {
        @Nullable T load(UUID id) throws IOException;
    }

    @FunctionalInterface
    private interface Saver<T> {
        void save(UUID id, T value) throws IOException;
    }

    /**
     * One of the three kinds of player data.
     */
    static final class Store<T> {

        private static final int STRIPES = 64;
        private static final long CLOSING_FAILURE_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30L);
        private static final long CLOSING_FAILURE_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(120L); // even if some work

        private final String name;
        private final Loader<T> loader;
        private final Saver<T> saver;
        private final Map<UUID, T> unsaved = new ConcurrentHashMap<>();
        private final ReentrantLock[] locks = new ReentrantLock[STRIPES];
        private volatile boolean closed;

        private Store(final String name, final Loader<T> loader, final Saver<T> saver) {
            this.name = name;
            this.loader = loader;
            this.saver = saver;
            for (int i = 0; i < STRIPES; i++) {
                this.locks[i] = new ReentrantLock();
            }
        }

        private ReentrantLock lock(final UUID id) {
            return this.locks[Math.floorMod(id.hashCode(), STRIPES)];
        }

        @Nullable T load(final UUID id) {
            final ReentrantLock lock = this.lock(id);
            lock.lock();
            try {
                final T unsaved = this.unsaved.get(id);
                if (unsaved != null) {
                    return unsaved;
                }
                if (this.closed) {
                    throw new IllegalStateException("Player storage is closed");
                }
                return this.loader.load(id);
            } catch (final Throwable thr) {
                throw new StorageException("Could not load " + this.name + " of player " + id, thr);
            } finally {
                lock.unlock();
            }
        }

        void save(final UUID id, final T value) {
            final ReentrantLock lock = this.lock(id);
            lock.lock();
            try {
                if (this.closed) {
                    LOGGER.error("Could not save {} of player {}: player storage is closed", this.name, id);
                    return;
                }
                this.saver.save(id, value);
                this.unsaved.remove(id);
            } catch (final Throwable thr) {
                LOGGER.error("Could not save {} of player {}. It is kept in memory and will be retried", this.name, id, thr);
                this.unsaved.put(id, value);
            } finally {
                lock.unlock();
            }
        }

        /**
         * @param closing tries every one, as there is no later, unless failing takes too long
         */
        void retry(final boolean closing) {
            int failures = 0;
            long timeFailing = 0L;
            long totalTimeFailing = 0L;
            final List<UUID> ids = new ArrayList<>(this.unsaved.keySet());
            Collections.shuffle(ids); // so players that always fail don't keep the rest from being tried
            for (final UUID id : ids) {
                final ReentrantLock lock = this.lock(id);
                if (closing) {
                    lock.lock();
                } else if (!lock.tryLock()) {
                    continue; // being saved or loaded
                }
                final long start = System.nanoTime();
                try {
                    final T value = this.unsaved.get(id);
                    if (value != null && !this.closed) {
                        this.saver.save(id, value);
                        this.unsaved.remove(id);
                        failures = 0;
                        timeFailing = 0L; // the backend works
                    }
                } catch (final Throwable thr) {
                    LOGGER.error("Failed again to save {} of player {}: {}", this.name, id, thr.toString());
                    failures++;
                    final long took = System.nanoTime() - start;
                    timeFailing += took;
                    totalTimeFailing += took;
                    if (closing
                        ? timeFailing >= CLOSING_FAILURE_BUDGET_NANOS || totalTimeFailing >= CLOSING_FAILURE_LIMIT_NANOS
                        : failures >= StoredWorld.MAX_FAILED_RETRIES) {
                        return;
                    }
                } finally {
                    lock.unlock();
                }
            }
        }

        void close() {
            for (final ReentrantLock lock : this.locks) {
                lock.lock(); // lets calls in progress finish
            }
            this.closed = true;
            for (final ReentrantLock lock : this.locks) {
                lock.unlock();
            }
            if (!this.unsaved.isEmpty()) {
                LOGGER.error("Player storage closed WITHOUT saving {} of these players: {}", this.name, this.unsaved.keySet());
            }
        }
    }

    private final PlayerStorage storage;
    private final Store<StoredData> data;
    private final Store<String> statistics;
    private final Store<String> advancements;
    private boolean closed;

    PlayerStorageBridge(final PlayerStorage storage) {
        this.storage = storage;
        this.data = new Store<>("data", storage::loadData, storage::saveData);
        this.statistics = new Store<>("statistics", storage::loadStatistics, storage::saveStatistics);
        this.advancements = new Store<>("advancements", storage::loadAdvancements, storage::saveAdvancements);
    }

    @Nullable StoredData loadData(final UUID id) {
        return this.data.load(id);
    }

    void saveData(final UUID id, final StoredData value) {
        this.data.save(id, value);
    }

    @Nullable JsonObject loadStatistics(final UUID id) {
        return parse(this.statistics.load(id), "statistics", id);
    }

    void saveStatistics(final UUID id, final JsonElement json) {
        this.statistics.save(id, GSON.toJson(json));
    }

    public @Nullable JsonElement loadAdvancements(final UUID id) {
        return parse(this.advancements.load(id), "advancements", id);
    }

    public void saveAdvancements(final UUID id, final JsonElement json) {
        this.advancements.save(id, GSON.toJson(json));
    }

    private static @Nullable JsonObject parse(final @Nullable String json, final String name, final UUID id) {
        if (json == null) {
            return null;
        }
        try {
            final JsonObject parsed = StrictJsonParser.parse(json).getAsJsonObject();
            if (parsed.has("DataVersion")) {
                StoredNbt.checkVersion(parsed.get("DataVersion").getAsInt());
            }
            return parsed;
        } catch (final Exception ex) {
            throw new StorageException("Stored " + name + " of player " + id + " can't be used", ex);
        }
    }

    public OfflinePlayer[] offlinePlayers(final CraftServer server) {
        final Set<UUID> ids;
        try {
            ids = new HashSet<>(this.storage.listPlayers());
        } catch (final Throwable thr) {
            throw new StorageException("Could not list stored players", thr);
        }
        ids.addAll(this.data.unsaved.keySet());

        final Set<OfflinePlayer> players = new HashSet<>();
        for (final UUID id : ids) {
            players.add(server.getOfflinePlayer(id));
        }
        players.addAll(server.getOnlinePlayers());
        return players.toArray(new OfflinePlayer[0]);
    }

    void retryFailedSaves() {
        this.data.retry(false);
        this.statistics.retry(false);
        this.advancements.retry(false);
    }

    synchronized void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.data.retry(true);
        this.statistics.retry(true);
        this.advancements.retry(true);
        this.data.close();
        this.statistics.close();
        this.advancements.close();
        try {
            this.storage.close();
        } catch (final Throwable thr) {
            LOGGER.error("Failed to close player storage", thr);
        }
    }
}
