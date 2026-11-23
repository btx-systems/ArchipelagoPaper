package sh.btx.archipelago.storage;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Stores player data, statistics and advancements for the whole server. No player files are written when one is
 * registered.
 * <p>
 * Methods are called on whichever thread needs the data, which is usually the main thread. They can be called from
 * several threads at the same time, but never at the same time for the same player and kind of data.
 * <p>
 * <b>Loads must return the latest save.</b> Slow backends can load ahead, for example in
 * {@link org.bukkit.event.player.AsyncPlayerPreLoginEvent}, but the player can still be online then: when logging in
 * again, their old session is saved <i>after</i> that event. A save must replace anything loaded ahead, or the items
 * in it are duplicated.
 * <p>
 * Saves should be stored when they return. If one fails, the server keeps the data, gives it to anything that loads
 * it and tries again later. If loading fails while a player joins, they are disconnected rather than given new data,
 * and methods of {@link org.bukkit.OfflinePlayer} that need the data throw.
 *
 * @see StorageRegistrar#players(PlayerStorage)
 */
@ApiStatus.Experimental
@ApiStatus.OverrideOnly
@NullMarked
public interface PlayerStorage {

    /**
     * Loads a player's data: inventory, location, health and everything else in their {@code .dat} file.
     *
     * @param playerId the player
     * @return the data, or null for a player that has never played
     * @throws IOException if it could not be loaded
     */
    @Nullable StoredData loadData(UUID playerId) throws IOException;

    /**
     * Saves a player's data.
     *
     * @param playerId the player
     * @param data the data
     * @throws IOException if it could not be saved
     */
    void saveData(UUID playerId, StoredData data) throws IOException;

    /**
     * Loads a player's statistics.
     *
     * @param playerId the player
     * @return JSON in the format of vanilla's files, or null if there are none
     * @throws IOException if they could not be loaded
     */
    @Nullable String loadStatistics(UUID playerId) throws IOException;

    /**
     * Saves a player's statistics.
     *
     * @param playerId the player
     * @param json JSON in the format of vanilla's files
     * @throws IOException if they could not be saved
     */
    void saveStatistics(UUID playerId, String json) throws IOException;

    /**
     * Loads a player's advancements.
     *
     * @param playerId the player
     * @return JSON in the format of vanilla's files, or null if there are none
     * @throws IOException if they could not be loaded
     */
    @Nullable String loadAdvancements(UUID playerId) throws IOException;

    /**
     * Saves a player's advancements.
     *
     * @param playerId the player
     * @param json JSON in the format of vanilla's files
     * @throws IOException if they could not be saved
     */
    void saveAdvancements(UUID playerId, String json) throws IOException;

    /**
     * Lists the players that have data, for {@link org.bukkit.Server#getOfflinePlayers()}.
     *
     * @return the players
     * @throws IOException if they could not be listed
     */
    Set<UUID> listPlayers() throws IOException;

    /**
     * Releases the storage when the server stops, after the last save.
     *
     * @throws IOException if it could not be closed cleanly
     */
    default void close() throws IOException {
    }
}
