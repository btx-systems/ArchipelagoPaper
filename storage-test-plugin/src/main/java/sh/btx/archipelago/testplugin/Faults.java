package sh.btx.archipelago.testplugin;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import sh.btx.archipelago.storage.StoredData;

/**
 * Makes the next calls of an operation fail.
 */
@NullMarked
final class Faults {

    private record Fault(String kind, AtomicInteger skip, AtomicInteger remaining) {
    }

    private static final Map<String, Fault> FAULTS = new ConcurrentHashMap<>();

    static void arm(final String operation, final String kind, final int count) {
        arm(operation, kind, count, 0);
    }

    static void arm(final String operation, final String kind, final int count, final int skip) {
        if (count <= 0) {
            FAULTS.remove(operation);
        } else {
            FAULTS.put(operation, new Fault(kind, new AtomicInteger(skip), new AtomicInteger(count)));
        }
    }

    // op:kind:count,op:kind:count
    static void armAll(final String spec) {
        for (final String each : spec.split(",")) {
            final String[] parts = each.split(":");
            if (parts.length == 3) {
                arm(parts[0], parts[1], Integer.parseInt(parts[2]));
            }
        }
    }

    static void clear() {
        FAULTS.clear();
    }

    /**
     * @return the kind of fault for this call, if there is one
     */
    static @Nullable String take(final String... operations) {
        for (final String operation : operations) {
            final Fault fault = FAULTS.get(operation);
            if (fault != null && fault.skip().getAndDecrement() <= 0 && fault.remaining().getAndDecrement() > 0) {
                System.out.println("ST fault " + operation + " " + fault.kind());
                return fault.kind();
            }
        }
        return null;
    }

    static void fail(final @Nullable String kind) throws IOException {
        switch (kind == null ? "" : kind) {
            case "io" -> throw new IOException("injected");
            case "runtime" -> throw new IllegalStateException("injected");
            case "linkage" -> throw new NoClassDefFoundError("injected");
            case "delay" -> StorageTest.sleep(300);
            default -> {
            }
        }
    }

    static @Nullable StoredData mangle(final @Nullable String kind, final @Nullable StoredData data) throws IOException {
        if (kind == null || data == null) {
            return data;
        }
        return switch (kind) {
            case "corrupt" -> {
                final byte[] bytes = data.bytes();
                yield StoredData.copyOf(java.util.Arrays.copyOf(bytes, Math.max(4, bytes.length / 2)));
            }
            case "newer" -> StoredData.of(data.toCompound().putInt("DataVersion", 999_999));
            case "position" -> {
                final CompoundBinaryTag tag = data.toCompound();
                yield StoredData.of(tag.contains("Position")
                    ? tag.putIntArray("Position", new int[]{12345, 12345})
                    : tag.putInt("xPos", tag.getInt("xPos") + 1));
            }
            default -> data;
        };
    }

    private Faults() {
    }
}
