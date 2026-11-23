package sh.btx.archipelago.storage;

import com.google.common.base.Preconditions;
import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.registrar.PaperRegistrar;
import java.util.function.BiConsumer;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
final class PaperStorageRegistrar implements StorageRegistrar, PaperRegistrar<BootstrapContext> {

    private final BiConsumer<WorldStorageProvider, String> onWorlds;
    private final BiConsumer<PlayerStorage, String> onPlayers;
    private final BiConsumer<MapStorage, String> onMaps;
    private @Nullable BootstrapContext owner;
    private @Nullable String worldsOwner;
    private @Nullable String playersOwner;
    private @Nullable String mapsOwner;
    private boolean valid = true;

    PaperStorageRegistrar(
        final BiConsumer<WorldStorageProvider, String> onWorlds, final BiConsumer<PlayerStorage, String> onPlayers, final BiConsumer<MapStorage, String> onMaps
    ) {
        this.onWorlds = onWorlds;
        this.onPlayers = onPlayers;
        this.onMaps = onMaps;
    }

    @Override
    public void setCurrentContext(final @Nullable BootstrapContext owner) {
        this.owner = owner;
    }

    @Override
    public void invalidate() {
        this.valid = false;
    }

    private String currentOwner() {
        Preconditions.checkState(this.valid && this.owner != null, "Storage can only be registered during the storage event");
        return this.owner.getPluginMeta().getName();
    }

    @Override
    public void worlds(final WorldStorageProvider provider) {
        Preconditions.checkArgument(provider != null, "provider cannot be null");
        final String plugin = this.currentOwner();
        Preconditions.checkState(this.worldsOwner == null, "%s cannot provide world storage, %s already does", plugin, this.worldsOwner);
        this.worldsOwner = plugin;
        this.onWorlds.accept(provider, plugin);
    }

    @Override
    public void players(final PlayerStorage storage) {
        Preconditions.checkArgument(storage != null, "storage cannot be null");
        final String plugin = this.currentOwner();
        Preconditions.checkState(this.playersOwner == null, "%s cannot provide player storage, %s already does", plugin, this.playersOwner);
        this.playersOwner = plugin;
        this.onPlayers.accept(storage, plugin);
    }

    @Override
    public void maps(final MapStorage storage) {
        Preconditions.checkArgument(storage != null, "storage cannot be null");
        final String plugin = this.currentOwner();
        Preconditions.checkState(this.mapsOwner == null, "%s cannot provide map storage, %s already does", plugin, this.mapsOwner);
        this.mapsOwner = plugin;
        this.onMaps.accept(storage, plugin);
    }
}
