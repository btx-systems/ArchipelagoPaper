package sh.btx.archipelago.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;

@NullMarked
final class StorageStatsCounter extends ServerStatsCounter {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private final PlayerStorageBridge players;
    private final UUID id;

    StorageStatsCounter(final MinecraftServer server, final UUID id, final PlayerStorageBridge players) {
        super(server, server.getWorldPath(LevelResource.ROOT)); // it only reads regular files, so this reads nothing
        this.players = players;
        this.id = id;

        final JsonObject stored = players.loadStatistics(id);
        if (stored != null) {
            if (stored.has("stats") && !isWellFormed(stored.get("stats"))) { // parse() would drop what it can't read
                throw new StorageException("Stored statistics of player " + id + " are malformed");
            }
            this.parse(server.getFixerUpper(), stored);
            for (final Map.Entry<Identifier, Integer> forced : org.spigotmc.SpigotConfig.forcedStats.entrySet()) {
                final Identifier stat = Objects.requireNonNull(BuiltInRegistries.CUSTOM_STAT.getValue(forced.getKey()));
                this.stats.put(Stats.CUSTOM.get(stat), forced.getValue().intValue());
            }
        }
    }

    // type -> stat -> number. Like vanilla, stats of things that don't exist any more are allowed, and dropped
    private static boolean isWellFormed(final JsonElement stats) {
        return stats.isJsonObject() && stats.getAsJsonObject().asMap().values().stream().allMatch(
            type -> type.isJsonObject() && type.getAsJsonObject().asMap().values().stream().allMatch(
                value -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
            )
        );
    }

    @Override
    public void save() {
        if (org.spigotmc.SpigotConfig.disableStatSaving) {
            return;
        }
        try {
            this.players.saveStatistics(this.id, this.toJson());
        } catch (final RuntimeException ex) {
            LOGGER.error("Failed to encode statistics of player {}", this.id, ex);
        }
    }
}
