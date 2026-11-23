package sh.btx.archipelago.storage;

import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

/**
 * Some of the keys used with {@link WorldStorage#readData(Key)} and {@link WorldStorage#writeData(Key, StoredData)}.
 * This is not all of them, storages must accept any key.
 */
@ApiStatus.Experimental
@NullMarked
public final class WorldDataKeys {

    /**
     * The world's UUID. Leave this out when cloning a world, so that the clone gets its own.
     */
    public static final Key METADATA = Key.key("paper", "metadata");

    /**
     * The world's persistent data container.
     */
    public static final Key PERSISTENT_DATA_CONTAINER = Key.key("paper", "persistent_data_container");

    /**
     * Spawn, time, game mode, difficulty and whether the world has been initialized.
     */
    public static final Key LEVEL_OVERRIDES = Key.key("paper", "level_overrides");

    /**
     * The seed and generator settings.
     */
    public static final Key WORLD_GEN_SETTINGS = Key.key("minecraft", "world_gen_settings");

    /**
     * The contents of {@code paper-world.yml}, as UTF-8 in the byte array {@code yaml}. A {@code paper-world.yml}
     * that exists in the world's folder is used instead of this.
     */
    public static final Key PAPER_WORLD_CONFIG = Key.key("archipelago", "paper_world_config");

    private WorldDataKeys() {
    }
}
