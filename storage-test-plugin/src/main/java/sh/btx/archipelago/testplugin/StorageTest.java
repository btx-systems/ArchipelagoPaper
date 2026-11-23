package sh.btx.archipelago.testplugin;

import java.nio.file.Path;

/**
 * State shared by the bootstrapper and plugin.
 */
final class StorageTest {

    static final String PROPERTY = "archipelago.storageTest";

    static final boolean ENABLED = Boolean.getBoolean(PROPERTY);
    static final long SLOW_WRITE_MILLIS = Long.getLong(PROPERTY + ".slowWrite", 0L);

    static volatile Path directory;
    static volatile boolean pluginDisabled;

    /**
     * Where islands, players and maps are kept. Servers given the same one share them, like a cluster that moves
     * islands between servers.
     */
    static Path shared() {
        final String shared = System.getProperty(PROPERTY + ".shared", "");
        return shared.isEmpty() ? directory : Path.of(shared);
    }

    static Path islandFile(final String name) {
        return shared().resolve("islands").resolve(name + ".island");
    }

    static Path mapsDirectory() {
        return shared().resolve("maps");
    }

    static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private StorageTest() {
    }
}
