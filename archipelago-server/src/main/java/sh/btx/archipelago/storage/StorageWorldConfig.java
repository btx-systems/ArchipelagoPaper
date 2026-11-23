package sh.btx.archipelago.storage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.key.Key;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import org.jspecify.annotations.NullMarked;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Keeps paper-world.yml in a world's storage. It can't just be the defaults, as Paper saves generated values in it,
 * like random feature seeds.
 */
@NullMarked
public final class StorageWorldConfig {

    private static final String YAML = "yaml";

    /**
     * @return whether the world has no config yet
     */
    public static boolean redirect(final YamlConfigurationLoader.Builder loader, final StoredWorld world) throws IOException {
        final Key key = WorldDataKeys.PAPER_WORLD_CONFIG;
        final StoredData stored = world.readData(key);
        final String yaml;
        if (stored == null) {
            yaml = "";
        } else {
            final byte[] bytes = StoredNbt.decode(stored).getByteArray(YAML)
                .orElseThrow(() -> new IOException("The stored world config has no '" + YAML + "' byte array"));
            yaml = new String(bytes, StandardCharsets.UTF_8);
        }
        final AtomicReference<String> saved = new AtomicReference<>(yaml);

        loader.source(() -> new BufferedReader(new StringReader(saved.get())));
        loader.sink(() -> {
            final StringWriter text = new StringWriter();
            return new BufferedWriter(text) {
                @Override
                public void close() throws IOException {
                    super.close();
                    final String updated = text.toString();
                    if (updated.equals(saved.getAndSet(updated))) {
                        return; // saved on every load and reload, usually without changes
                    }
                    final CompoundTag tag = new CompoundTag();
                    tag.putByteArray(YAML, updated.getBytes(StandardCharsets.UTF_8)); // NBT strings are limited to 64KiB
                    NbtUtils.addCurrentDataVersion(tag);
                    world.writeData(key, StoredNbt.encode(tag));
                }
            };
        });
        return stored == null;
    }

    private StorageWorldConfig() {
    }
}
