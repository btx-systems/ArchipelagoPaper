package sh.btx.archipelago.storage;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Stores everything that belongs to one world. A world that uses one has no folder and no files.
 * <p>
 * An instance belongs to one loaded world. The server calls {@link #close()} when it is done with it, and doesn't use
 * it again. That includes when creating the world fails after the server started to use the storage, but not when
 * the arguments are rejected before that, such as for a key that is already loaded. To load the world again, use a
 * new instance.
 *
 * <h2>Writing and saving</h2>
 * The server writes whenever it autosaves or unloads chunks. Reads must return the latest write, whether it has been
 * saved or not. {@link #save()} is the point where everything written so far belongs together. An implementation can
 * either
 * <ul>
 *     <li>write through, like the built-in storage: store every write, and leave {@link #save()} as it is, or</li>
 *     <li>stage, like a format that is one file for the world: keep writes in memory, and store all of them in
 *     {@link #save()}.</li>
 * </ul>
 * Autosave does <b>not</b> call {@link #save()}. With staged writes, call
 * {@link org.bukkit.World#save(boolean) World#save(true)} as often as the world should be saved.
 *
 * <h2>Common uses</h2>
 * <ul>
 *     <li><b>Discarding changes:</b> stage writes and use
 *     {@link org.bukkit.Bukkit#unloadWorld(org.bukkit.World, boolean) unloadWorld(world, false)}, which closes
 *     without saving.</li>
 *     <li><b>Read-only worlds:</b> keep writes in memory and don't store them. Don't drop them, chunks are read
 *     again when they load again. Turning off autosave is not enough, the server saves every world when it
 *     stops.</li>
 *     <li><b>Clones:</b> copy all data except {@link WorldDataKeys#METADATA}, which contains the world's UUID.
 *     Two loaded worlds cannot have the same one. The UUIDs of entities stay the same as the template's.</li>
 * </ul>
 *
 * <h2>Failures</h2>
 * If writing fails, the server keeps the data and tries again before it saves, and it doesn't save while any write
 * is failing. If reading a chunk fails, or it can't be parsed, what is stored for it is not replaced or used: until
 * the world is unloaded, the chunk is generated, and changes to it are kept in memory only and then discarded. If
 * adding entities to a chunk that isn't loaded keeps failing, the rest is saved after a few tries. Like vanilla,
 * single blocks, items and entities that don't exist in this version are dropped from what does parse. Failing to
 * read other data stops the world from loading.
 * <p>
 * Something that can't be serialized doesn't stop the rest from being saved. It makes unloading with saving return
 * false, a few times at most with the world staying loaded. That has already saved the rest, so unloading without
 * saving afterwards doesn't discard it.
 * <p>
 * {@link org.bukkit.Bukkit#unloadWorld(org.bukkit.World, boolean)} returns false when the world can't be saved. If
 * that is found out before anything is stopped, the world stays loaded, although
 * {@link org.bukkit.event.world.WorldUnloadEvent} was called. If only the last save fails, which adds what can only
 * be saved while unloading, the world is gone, and {@link org.bukkit.Bukkit#getWorld(net.kyori.adventure.key.Key)}
 * returns null.
 *
 * <h2>Threads</h2>
 * Any method except {@link #save()} and {@link #close()} can be called from any thread, at the same time as others,
 * so use thread-safe collections. Don't use worlds from any of them.
 *
 * @see org.bukkit.WorldCreator#storage(WorldStorage)
 * @see WorldStorageProvider
 */
@ApiStatus.Experimental
@ApiStatus.OverrideOnly
@NullMarked
public interface WorldStorage {

    /**
     * The kinds of data that each chunk has. They are read and written separately.
     */
    enum ChunkDataType {
        /**
         * Blocks, biomes, light, heightmaps, block entities, scheduled ticks, structures and the chunk's PDC.
         */
        CHUNK,
        /**
         * Entities other than players.
         */
        ENTITIES,
        /**
         * Points of interest, such as beds, job sites and nether portals. These are not computed again for
         * existing chunks, so they have to be stored.
         */
        POI
    }

    /**
     * Reads a chunk's data.
     * <p>
     * Called from several threads at the same time, but never at the same time as another call for the same chunk
     * and type.
     *
     * @param type the kind of data
     * @param chunkX the chunk's x coordinate
     * @param chunkZ the chunk's z coordinate
     * @return the latest data written, or null if there is none. A chunk without data is generated.
     * @throws IOException if it could not be read
     */
    @Nullable StoredData readChunk(ChunkDataType type, int chunkX, int chunkZ) throws IOException;

    /**
     * Writes a chunk's data.
     * <p>
     * Called from several threads at the same time, but never at the same time as another call for the same chunk
     * and type.
     *
     * @param type the kind of data
     * @param chunkX the chunk's x coordinate
     * @param chunkZ the chunk's z coordinate
     * @param data the data, or null to delete it, which can happen for data that doesn't exist
     * @throws IOException if it could not be written
     */
    void writeChunk(ChunkDataType type, int chunkX, int chunkZ, @Nullable StoredData data) throws IOException;

    /**
     * Reads data of the world, such as its game rules, border or persistent data container.
     * <p>
     * Usually called from the main thread while the world loads, so loading the world's data before creating it
     * avoids lag.
     *
     * @param id what is being read. Any key is possible, {@link WorldDataKeys} has some that plugins may need.
     * @return the latest data written, or null if there is none
     * @throws IOException if it could not be read
     */
    @Nullable StoredData readData(Key id) throws IOException;

    /**
     * Writes data of the world.
     * <p>
     * Called from any thread, also at the same time as {@link #readData(Key)} and chunk calls.
     *
     * @param id what is being written. Store any key, the set is not fixed.
     * @param data the data
     * @throws IOException if it could not be written
     */
    void writeData(Key id, StoredData data) throws IOException;

    /**
     * Saves everything that has been written so far.
     * <p>
     * This is called when all writes of the save have finished, and no write runs during it. Reads can. It's on the
     * main thread, unless the server is being stopped by a signal or the watchdog. Capture what to store before
     * returning, from the storage's own data and not from the world, then complete the future when it is stored.
     * Doing slow work like compressing or uploading on another thread keeps it from lagging the server.
     * <p>
     * It's called for {@link org.bukkit.World#save(boolean) World#save(true)}, {@code save-all flush} and when the
     * server has started, all of which don't wait for the future. It's also called when the world is unloaded with
     * saving and when the server stops, which do wait. It can be called again before the last future completes, and
     * then the later save has to win. Unloading saves twice: first to find out whether it can, then finally.
     *
     * @return completes when it is stored, or exceptionally if it could not be
     */
    default CompletableFuture<@Nullable Void> save() {
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Releases the storage. This must not save: the server also closes storages whose changes are being discarded.
     * All futures returned by {@link #save()} have completed when this is called, and nothing is called after it.
     *
     * @throws IOException if it could not be closed cleanly
     */
    default void close() throws IOException {
    }
}
