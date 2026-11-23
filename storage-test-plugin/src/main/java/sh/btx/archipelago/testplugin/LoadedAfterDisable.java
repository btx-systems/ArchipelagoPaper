package sh.btx.archipelago.testplugin;

/**
 * Loaded for the first time while saving after onDisable, which fails if the classloader was closed by then.
 */
final class LoadedAfterDisable {

    static void report(final String world) {
        System.out.println("ST late class loaded while saving " + world);
    }

    private LoadedAfterDisable() {
    }
}
