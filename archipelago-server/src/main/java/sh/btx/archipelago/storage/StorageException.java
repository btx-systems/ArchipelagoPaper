package sh.btx.archipelago.storage;

import org.jspecify.annotations.NullMarked;

/**
 * Storage failed where vanilla can't throw checked exceptions. Never swallow this into "no data".
 */
@NullMarked
final class StorageException extends RuntimeException {

    StorageException(final String message) {
        super(message);
    }

    StorageException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
