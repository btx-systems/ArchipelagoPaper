package sh.btx.archipelago.storage;

import ca.spottedleaf.concurrentutil.executor.thread.BalancedPrioritisedThreadPool;
import ca.spottedleaf.moonrise.common.util.MoonriseCommon;
import com.mojang.logging.LogUtils;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;

/**
 * Chunk IO of worlds with storage. Paper's own IO pool is one thread for the whole server by default, which a
 * storage that waits for a network would hold up for every world.
 */
@NullMarked
final class StorageThreads {

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final int THREADS = 4;

    private static final AtomicInteger NEXT_ID = new AtomicInteger();
    private static final BalancedPrioritisedThreadPool POOL = new BalancedPrioritisedThreadPool(MoonriseCommon.IO_QUEUE_HOLD_TIME, run -> {
        final Thread thread = new Thread(run, "Archipelago Storage I/O Worker #" + NEXT_ID.getAndIncrement());
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((failed, thr) -> LOGGER.error("Uncaught exception in thread {}", failed.getName(), thr));
        return thread;
    });
    static final BalancedPrioritisedThreadPool.OrderedStreamGroup GROUP = POOL.createOrderedStreamGroup();

    static {
        POOL.adjustThreadCount(THREADS);
    }

    private StorageThreads() {
    }
}
