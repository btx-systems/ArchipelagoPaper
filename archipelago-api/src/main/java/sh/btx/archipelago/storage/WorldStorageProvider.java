package sh.btx.archipelago.storage;

import java.io.IOException;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Chooses the storage of worlds as they load.
 * <p>
 * It is asked for the default worlds, and for worlds created without a
 * {@link org.bukkit.WorldCreator#storage(WorldStorage) storage}. The overworld is asked for before the server
 * exists, so implementations can't use {@link org.bukkit.Bukkit}.
 * <p>
 * Once a default world has been given storage, the server refuses to start without it, as it would otherwise
 * generate a new world. Once any other world has been, the server refuses to start without a provider. This is
 * recorded in {@code archipelago-storage.txt} in the level folder. It doesn't record which plugin provided them.
 *
 * @see StorageRegistrar#worlds(WorldStorageProvider)
 */
@ApiStatus.Experimental
@ApiStatus.OverrideOnly
@FunctionalInterface
@NullMarked
public interface WorldStorageProvider {

    /**
     * Opens the storage of a world.
     *
     * @param world the world's key, such as {@code minecraft:overworld}
     * @return a new storage, or null to use the built-in storage
     * @throws IOException if it could not be opened, which stops the world from loading
     */
    @Nullable WorldStorage open(Key world) throws IOException;
}
