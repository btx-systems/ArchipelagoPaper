package sh.btx.archipelago.storage;

import ca.spottedleaf.dataconverter.minecraft.MCDataConverter;
import ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry;
import ca.spottedleaf.dataconverter.minecraft.util.Version;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.TagValueOutput;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;

@NullMarked
final class StoragePlayerDataStorage extends PlayerDataStorage {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    private final PlayerStorageBridge players;

    StoragePlayerDataStorage(final LevelStorageSource.LevelStorageAccess access, final DataFixer fixer, final PlayerStorageBridge players) {
        super(access, fixer);
        this.players = players;
    }

    @Override
    public void save(final Player player) {
        if (org.spigotmc.SpigotConfig.disablePlayerDataSaving) {
            return;
        }
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(player.problemPath(), LOGGER)) {
            final TagValueOutput output = TagValueOutput.createWithContext(reporter, player.registryAccess());
            player.saveWithoutId(output);
            this.players.saveData(player.getUUID(), StoredNbt.encode(output.buildResult()));
        } catch (final Exception ex) {
            LOGGER.error("Failed to encode player data for {}", player.getPlainTextName(), ex);
        }
    }

    // no fallback to the offline UUID as with files, that would rename the stored player
    @Override
    public Optional<CompoundTag> load(final NameAndId nameAndId) {
        final StoredData stored = this.players.loadData(nameAndId.id());
        if (stored == null) {
            return Optional.empty();
        }
        try {
            final CompoundTag tag = StoredNbt.decode(stored);
            return Optional.of(MCDataConverter.convertTag(
                MCTypeRegistry.PLAYER, DataFixTypes.PLAYER, tag, NbtUtils.getDataVersion(tag), Version.getCurrentVersion()
            ));
        } catch (final IOException | RuntimeException ex) {
            throw new StorageException("Stored data of player " + nameAndId.id() + " can't be used", ex);
        }
    }
}
