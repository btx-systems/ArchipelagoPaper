package sh.btx.archipelago.testplugin;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Set;

/**
 * Enabled with {@code -Darchipelago.storageTest=true}. Then also:
 * <ul>
 *     <li>{@code -Darchipelago.storageTest.worlds=overworld,the_nether,the_end} stores those default worlds</li>
 *     <li>{@code -Darchipelago.storageTest.players=true} stores players</li>
 *     <li>{@code -Darchipelago.storageTest.faults=open:io:1,save:delay:3} makes calls fail</li>
 * </ul>
 */
public final class StorageTestBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(final BootstrapContext context) {
        if (!StorageTest.ENABLED) {
            return;
        }
        StorageTest.directory = context.getDataDirectory();
        Faults.armAll(System.getProperty(StorageTest.PROPERTY + ".faults", ""));
        if (Faults.take("bootstrap") != null) {
            throw new IllegalStateException("injected bootstrap failure");
        }

        final String worldList = System.getProperty(StorageTest.PROPERTY + ".worlds", "");
        final boolean players = Boolean.getBoolean(StorageTest.PROPERTY + ".players");
        if (worldList.isEmpty() && !players) {
            return;
        }
        final Set<String> worlds = Set.of(worldList.split(","));
        context.getLifecycleManager().registerEventHandler(LifecycleEvents.STORAGE, event -> {
            if (Faults.take("handler") != null) {
                throw new IllegalStateException("injected handler failure");
            }
            if (!worldList.isEmpty()) {
                event.registrar().worlds(key -> {
                    final boolean defaultWorld = key.namespace().equals("minecraft") && worlds.contains(key.value());
                    if (!defaultWorld && !key.namespace().equals("provided")) {
                        return null;
                    }
                    Faults.fail(Faults.take("open." + key.value(), "open"));
                    System.out.println("ST opened " + key.asString());
                    return IslandStorage.load(key.namespace() + "_" + key.value(), false, !defaultWorld);
                });
            }
            if (players) {
                event.registrar().players(new FilePlayerStorage(context.getDataDirectory().resolve("players")));
            }
        });
    }
}
