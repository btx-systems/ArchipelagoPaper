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

    static Path islandFile(final String name) {
        return directory.resolve("islands").resolve(name + ".island");
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
