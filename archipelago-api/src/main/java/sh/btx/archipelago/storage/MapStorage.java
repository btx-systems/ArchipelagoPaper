package sh.btx.archipelago.storage;

import java.io.IOException;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Stores the contents of maps for the whole server, and hands out their IDs. No map files are written when one is
 * registered.
 * <p>
 * A map item only holds its ID. Its picture, banner and item frame markers, center, scale and world are stored here,
 * under the ID, in the format of vanilla's {@code map_<id>.dat}. The world is the key in {@code data.dimension}.
 * <p>
 * Items travel with players and stored worlds, so a group of servers that share players or worlds has to share one
 * map storage too, or the same ID would mean different maps.
 *
 * <h2>Which server saves a map</h2>
 * A map is saved by the server that has its world loaded:
 * <ul>
 *     <li>when it is created, on any server,</li>
 *     <li>when the server saves, such as by autosave and {@code save-all},</li>
 *     <li>when its world is unloaded with saving, which then waits for it and returns false if it can't be saved,
 *     so that the next server to load the world can read it,</li>
 *     <li>and when the server stops.</li>
 * </ul>
 * Changes made on a server that doesn't have the map's world loaded, which can only be item frame and banner markers,
 * are not saved. When a world is loaded, its maps are read again, so that it shows them as the server that had it
 * last saved them. Maps of worlds that aren't loaded are read again about every minute while they are in use.
 * <p>
 * Maps in the level folder are not used. To keep the maps that players already have, copy {@code data/map_*.dat}
 * into the storage, and hand out IDs after the one in {@code data/maps/last_id.dat}.
 *
 * <h2>Threads</h2>
 * Methods can be called from any thread, {@link #loadMap(int)} usually from the main thread when a map is first used.
 * Calls for the same map never run at the same time, and neither do calls of {@link #nextMapId()}.
 *
 * @see StorageRegistrar#maps(MapStorage)
 */
@ApiStatus.Experimental
@ApiStatus.OverrideOnly
@NullMarked
public interface MapStorage {

    /**
     * Hands out the ID of a new map.
     * <p>
     * The server asks for a few ahead, so not every ID is used, and asks while the main thread waits only when it
     * has none left.
     *
     * @return an ID that has never been handed out by any server that shares this storage
     * @throws IOException if there is none, which stops the map from being made
     */
    int nextMapId() throws IOException;

    /**
     * Loads a map.
     *
     * @param mapId the map
     * @return the latest data saved, or null if there is no map with the ID
     * @throws IOException if it could not be loaded. The map shows nothing, is tried again later, and is not
     *     replaced.
     */
    @Nullable StoredData loadMap(int mapId) throws IOException;

    /**
     * Saves a map.
     *
     * @param mapId the map
     * @param data the data
     * @throws IOException if it could not be saved. The server keeps the data and tries again later.
     */
    void saveMap(int mapId, StoredData data) throws IOException;

    /**
     * Releases the storage when the server stops, after the last save.
     *
     * @throws IOException if it could not be closed cleanly
     */
    default void close() throws IOException {
    }
}
