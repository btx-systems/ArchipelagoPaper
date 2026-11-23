package sh.btx.archipelago.testplugin;

import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.NamespacedKey;
import org.bukkit.WorldCreator;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

public final class StorageTestPlugin extends JavaPlugin {

    @Override
    public void onLoad() {
        if (StorageTest.ENABLED && Boolean.getBoolean(StorageTest.PROPERTY + ".earlyCreate")) {
            try {
                WorldCreator.ofKey(NamespacedKey.minecraft("overworld")).createWorld();
                System.out.println("ST early create worked");
            } catch (final IllegalStateException ex) {
                System.out.println("ST early create refused");
            }
        }
    }

    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public void onEnable() {
        if (!StorageTest.ENABLED) {
            return;
        }
        StorageTest.pluginDisabled = false;
        StorageTest.directory = this.getDataPath();
        final TestCommand command = new TestCommand(this);
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar().register("st", command));

        // each of these makes the server load players in another place or on another thread
        final var events = switch (System.getProperty(StorageTest.PROPERTY + ".loginEvent", "")) {
            case "login" -> PlayerLoginEvent.class;
            case "spawn" -> PlayerSpawnLocationEvent.class;
            case "configure" -> AsyncPlayerConnectionConfigureEvent.class;
            default -> null;
        };
        if (events != null) {
            this.getServer().getPluginManager().registerEvent(events, new Listener() {
            }, EventPriority.MONITOR, (listener, event) -> {
            }, this);
        }
        System.out.println("ST enabled");
    }

    @Override
    public void onDisable() {
        StorageTest.pluginDisabled = true;
    }
}
