package sh.btx.archipelago.storage;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class StorageWait {

    private static final long WARN_AFTER_SECONDS = 10L;

    /**
     * Waits for as long as it takes, as giving up would lose data.
     *
     * @return whether it completed normally
     */
    static boolean await(final CompletableFuture<?> future, final Runnable warnSlow) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    future.get(WARN_AFTER_SECONDS, TimeUnit.SECONDS);
                    return true;
                } catch (final TimeoutException ex) {
                    warnSlow.run();
                } catch (final InterruptedException ex) {
                    interrupted = true;
                } catch (final ExecutionException | CancellationException ex) {
                    return false;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private StorageWait() {
    }
}
