package sh.btx.archipelago.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import org.jspecify.annotations.Nullable;

final class FakeWorldStorage implements WorldStorage {

    final Map<String, StoredData> chunks = new ConcurrentHashMap<>();
    final Map<Key, StoredData> data = new ConcurrentHashMap<>();
    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<String> saved = new ArrayList<>();
    final AtomicInteger saves = new AtomicInteger();
    final AtomicInteger closes = new AtomicInteger();

    @Nullable Supplier<Throwable> readFailure;
    @Nullable Supplier<Throwable> writeFailure;
    @Nullable Runnable onWrite;
    java.util.function.IntPredicate rejectX = x -> false;
    Supplier<CompletableFuture<Void>> saveResult = () -> CompletableFuture.completedFuture(null);

    static String id(final ChunkDataType type, final int x, final int z) {
        return type + " " + x + " " + z;
    }

    private static void fail(final @Nullable Supplier<Throwable> failure) throws IOException {
        if (failure == null) {
            return;
        }
        switch (failure.get()) {
            case final IOException ex -> throw ex;
            case final RuntimeException ex -> throw ex;
            case final Error ex -> throw ex;
            case final Throwable ex -> throw new AssertionError(ex);
        }
    }

    @Override
    public @Nullable StoredData readChunk(final ChunkDataType type, final int x, final int z) throws IOException {
        this.calls.add("readChunk");
        fail(this.readFailure);
        return this.chunks.get(id(type, x, z));
    }

    @Override
    public void writeChunk(final ChunkDataType type, final int x, final int z, final @Nullable StoredData value) throws IOException {
        this.calls.add("writeChunk");
        if (this.onWrite != null) {
            this.onWrite.run();
        }
        fail(this.writeFailure);
        if (this.rejectX.test(x)) {
            throw new IOException("rejected");
        }
        if (value == null) {
            this.chunks.remove(id(type, x, z));
        } else {
            this.chunks.put(id(type, x, z), value);
        }
    }

    @Override
    public @Nullable StoredData readData(final Key id) throws IOException {
        this.calls.add("readData");
        fail(this.readFailure);
        return this.data.get(id);
    }

    @Override
    public void writeData(final Key id, final StoredData value) throws IOException {
        this.calls.add("writeData");
        if (this.onWrite != null) {
            this.onWrite.run();
        }
        fail(this.writeFailure);
        this.data.put(id, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<@Nullable Void> save() {
        this.calls.add("save");
        this.saves.incrementAndGet();
        return (CompletableFuture<@Nullable Void>) (CompletableFuture<?>) this.saveResult.get();
    }

    @Override
    public void close() {
        this.calls.add("close");
        this.closes.incrementAndGet();
    }
}
