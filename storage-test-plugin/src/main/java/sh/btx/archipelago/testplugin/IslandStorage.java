package sh.btx.archipelago.testplugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.key.Key;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import sh.btx.archipelago.storage.StoredData;
import sh.btx.archipelago.storage.WorldDataKeys;
import sh.btx.archipelago.storage.WorldStorage;

/**
 * A world that lives in memory and is saved as one {@link Island} file. Everything needed is in the API.
 * <p>
 * It also checks the contract, and prints "ST BUG" when the server breaks it.
 */
@NullMarked
final class IslandStorage implements WorldStorage {

    private final String name;
    private final @Nullable Path file; // null = read-only
    private final boolean skipAir;
    private final Map<Island.ChunkRef, StoredData> chunks;
    private final Map<Key, StoredData> data;

    private final ExecutorService committer;
    private final Set<Island.ChunkRef> busy = ConcurrentHashMap.newKeySet();
    private final AtomicInteger writing = new AtomicInteger();
    volatile boolean closed;
    volatile long checkpoint = -1L; // printed once it is stored
    @Nullable Runnable onSave;

    private IslandStorage(final String name, final @Nullable Path file, final boolean skipAir, final Island island) {
        this.name = name;
        this.file = file;
        this.skipAir = skipAir;
        this.chunks = new ConcurrentHashMap<>(island.chunks());
        this.data = new ConcurrentHashMap<>(island.data());
        // one thread, so that a later save always wins. Not the Bukkit scheduler, as that stops before the last save
        this.committer = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("Island saver " + name).factory());
    }

    static IslandStorage load(final String name, final boolean readOnly, final boolean skipAir) throws IOException {
        final Path file = StorageTest.islandFile(name);
        final Island island = Files.exists(file) ? Island.read(file) : Island.empty();
        return new IslandStorage(name, readOnly ? null : file, skipAir, island);
    }

    static IslandStorage copy(final Island template, final String name, final boolean keepId) {
        final Island island = template.copy();
        if (!keepId) {
            island.data().remove(WorldDataKeys.METADATA); // gives the clone its own UUID
        }
        return new IslandStorage(name, StorageTest.islandFile(name), true, island);
    }

    Island snapshot() {
        return new Island(this.chunks, this.data).copy();
    }

    @Nullable StoredData peek(final ChunkDataType type, final int x, final int z) {
        return this.chunks.get(new Island.ChunkRef(type, x, z));
    }

    private void expectOpen(final String operation) {
        if (this.closed) {
            System.out.println("ST BUG " + operation + " after close of " + this.name);
        }
    }

    @Override
    public @Nullable StoredData readChunk(final ChunkDataType type, final int x, final int z) throws IOException {
        this.expectOpen("readChunk");
        final Island.ChunkRef ref = new Island.ChunkRef(type, x, z);
        if (!this.busy.add(ref)) {
            System.out.println("ST BUG concurrent readChunk of " + ref + " in " + this.name);
        }
        try {
            final StoredData stored = this.chunks.get(ref);
            if (stored == null) {
                return null; // faults are about losing data that exists
            }
            final String fault = Faults.take("readChunk." + type + "." + x + "." + z, "readChunk." + type, "readChunk");
            Faults.fail(fault);
            return Faults.mangle(fault, stored);
        } finally {
            this.busy.remove(ref);
        }
    }

    @Override
    public void writeChunk(final ChunkDataType type, final int x, final int z, final @Nullable StoredData stored) throws IOException {
        this.expectOpen("writeChunk");
        final Island.ChunkRef ref = new Island.ChunkRef(type, x, z);
        if (!this.busy.add(ref)) {
            System.out.println("ST BUG concurrent writeChunk of " + ref + " in " + this.name);
        }
        this.writing.incrementAndGet();
        try {
            Faults.fail(Faults.take("writeChunk." + type + "." + x + "." + z, "writeChunk." + type, "writeChunk"));
            if (stored == null) {
                this.chunks.remove(ref);
            } else {
                this.chunks.put(ref, stored);
            }
        } finally {
            this.writing.decrementAndGet();
            this.busy.remove(ref);
        }
    }

    @Override
    public @Nullable StoredData readData(final Key id) throws IOException {
        this.expectOpen("readData");
        final String fault = Faults.take("readData." + id.value(), "readData");
        Faults.fail(fault);
        return Faults.mangle(fault, this.data.get(id));
    }

    @Override
    public void writeData(final Key id, final StoredData stored) throws IOException {
        this.expectOpen("writeData");
        this.writing.incrementAndGet();
        try {
            Faults.fail(Faults.take("writeData." + id.value(), "writeData"));
            this.data.put(id, stored);
        } finally {
            this.writing.decrementAndGet();
        }
    }

    @Override
    public CompletableFuture<@Nullable Void> save() {
        this.expectOpen("save");
        if (this.writing.get() != 0) {
            System.out.println("ST BUG save during writes in " + this.name);
        }
        if (StorageTest.pluginDisabled) {
            LoadedAfterDisable.report(this.name);
        }
        if (this.onSave != null) {
            this.onSave.run();
        }
        final String fault = Faults.take("save." + this.name, "save");
        switch (fault == null ? "" : fault) {
            case "runtime" -> throw new IllegalStateException("injected");
            case "linkage" -> throw new NoClassDefFoundError("injected");
            case "io" -> {
                return CompletableFuture.failedFuture(new IOException("injected"));
            }
            case "null" -> {
                return null;
            }
            default -> {
            }
        }
        final Path file = this.file;
        if (file == null) {
            return CompletableFuture.completedFuture(null); // read-only
        }
        final Island captured = this.snapshot();
        final long checkpoint = this.checkpoint;
        return CompletableFuture.runAsync(() -> {
            try {
                if ("delay".equals(fault)) {
                    StorageTest.sleep(2000);
                }
                final long start = System.nanoTime();
                final int written = captured.write(file, this.skipAir, StorageTest.SLOW_WRITE_MILLIS);
                System.out.println("ST committed " + this.name + " chunks=" + written + " ms=" + (System.nanoTime() - start) / 1_000_000);
                if (checkpoint >= 0) {
                    System.out.println("ST ack " + this.name + " " + checkpoint);
                }
            } catch (final IOException ex) {
                throw new CompletionException(ex);
            }
        }, this.committer);
    }

    @Override
    public void close() throws IOException {
        this.expectOpen("close");
        this.closed = true;
        this.committer.shutdown();
        try {
            if (!this.committer.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IOException("Saves of " + this.name + " did not finish");
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        System.out.println("ST closed " + this.name);
        Faults.fail(Faults.take("close"));
    }
}
