package sh.btx.archipelago.storage;

import io.papermc.paper.plugin.lifecycle.event.registrar.Registrar;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

/**
 * Registers storage for default worlds, players and maps.
 *
 * @see io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents#STORAGE
 */
@ApiStatus.Experimental
@ApiStatus.NonExtendable
@NullMarked
public interface StorageRegistrar extends Registrar {

    /**
     * Sets what chooses the storage of worlds.
     *
     * @param provider the provider
     * @throws IllegalStateException if a plugin has already set one
     */
    void worlds(WorldStorageProvider provider);

    /**
     * Sets the storage for players.
     *
     * @param storage the storage
     * @throws IllegalStateException if a plugin has already set one
     */
    void players(PlayerStorage storage);

    /**
     * Sets the storage for maps.
     *
     * @param storage the storage
     * @throws IllegalStateException if a plugin has already set one
     */
    void maps(MapStorage storage);
}
