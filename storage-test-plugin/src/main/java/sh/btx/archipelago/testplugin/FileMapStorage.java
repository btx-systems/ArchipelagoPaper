package sh.btx.archipelago.testplugin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import sh.btx.archipelago.storage.MapStorage;
import sh.btx.archipelago.storage.StoredData;

/**
 * One file per map, in a folder that several servers can share. IDs come from a counter file, locked while it is
 * read and written, so servers that share the folder never hand out the same one.
 */
@NullMarked
final class FileMapStorage implements MapStorage {

    private final Path directory;
    private volatile boolean closed;

    FileMapStorage(final Path directory) {
        this.directory = directory;
    }

    static Path file(final Path directory, final int mapId) {
        return directory.resolve(mapId + ".map");
    }

    @Override
    public int nextMapId() throws IOException {
        this.checkOpen("nextMapId");
        Faults.fail(Faults.take("map.id"));
        Files.createDirectories(this.directory);
        try (FileChannel channel = FileChannel.open(this.directory.resolve("next-id"), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            final ByteBuffer read = ByteBuffer.allocate(Integer.BYTES);
            final int id = channel.read(read, 0L) == Integer.BYTES ? read.getInt(0) : 0;
            channel.write(ByteBuffer.allocate(Integer.BYTES).putInt(0, id + 1), 0L);
            channel.force(true);
            return id;
        }
    }

    @Override
    public @Nullable StoredData loadMap(final int mapId) throws IOException {
        this.checkOpen("loadMap");
        final String fault = Faults.take("map.load");
        Faults.fail(fault);
        final Path file = file(this.directory, mapId);
        return Files.exists(file) ? Faults.mangle(fault, StoredData.copyOf(Files.readAllBytes(file))) : null;
    }

    @Override
    public void saveMap(final int mapId, final StoredData data) throws IOException {
        this.checkOpen("saveMap");
        Faults.fail(Faults.take("map.save"));
        Files.createDirectories(this.directory);
        final Path temp = Files.createTempFile(this.directory, "saving", ".tmp");
        Files.write(temp, data.bytes());
        Files.move(temp, file(this.directory, mapId), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.println("ST map saved " + mapId);
    }

    private void checkOpen(final String call) {
        if (this.closed) {
            System.out.println("ST BUG " + call + " after maps closed");
        }
    }

    @Override
    public void close() {
        this.closed = true;
        System.out.println("ST maps closed");
    }
}
