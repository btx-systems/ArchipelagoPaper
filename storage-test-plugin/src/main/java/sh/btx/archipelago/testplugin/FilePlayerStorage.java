package sh.btx.archipelago.testplugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import sh.btx.archipelago.storage.PlayerStorage;
import sh.btx.archipelago.storage.StoredData;

@NullMarked
final class FilePlayerStorage implements PlayerStorage {

    private final Path directory;
    private volatile boolean closed;

    FilePlayerStorage(final Path directory) {
        this.directory = directory;
    }

    private byte @Nullable [] read(final UUID id, final String kind) throws IOException {
        if (this.closed) {
            System.out.println("ST BUG load of " + kind + " after players closed");
        }
        final Path file = this.directory.resolve(id + "." + kind);
        final String fault = Faults.take("load." + kind, "load");
        Faults.fail(fault);
        if (!Files.exists(file)) {
            return null;
        }
        final byte[] bytes = Files.readAllBytes(file);
        if ("corrupt".equals(fault)) {
            return java.util.Arrays.copyOf(bytes, Math.max(4, bytes.length / 2));
        }
        if ("newer".equals(fault)) {
            return kind.equals("data")
                ? StoredData.of(StoredData.copyOf(bytes).toCompound().putInt("DataVersion", 999_999)).bytes()
                : "{\"DataVersion\":999999}".getBytes(StandardCharsets.UTF_8);
        }
        return bytes;
    }

    private void write(final UUID id, final String kind, final byte[] bytes) throws IOException {
        if (this.closed) {
            System.out.println("ST BUG save of " + kind + " after players closed");
        }
        Faults.fail(Faults.take("store." + kind, "store"));
        Files.createDirectories(this.directory);
        final Path temp = Files.createTempFile(this.directory, "saving", ".tmp");
        Files.write(temp, bytes);
        Files.move(temp, this.directory.resolve(id + "." + kind), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private @Nullable String readText(final UUID id, final String kind) throws IOException {
        final byte[] bytes = this.read(id, kind);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public @Nullable StoredData loadData(final UUID id) throws IOException {
        final byte[] bytes = this.read(id, "data");
        return bytes == null ? null : StoredData.copyOf(bytes);
    }

    @Override
    public void saveData(final UUID id, final StoredData data) throws IOException {
        this.write(id, "data", data.bytes());
    }

    @Override
    public @Nullable String loadStatistics(final UUID id) throws IOException {
        return this.readText(id, "stats");
    }

    @Override
    public void saveStatistics(final UUID id, final String json) throws IOException {
        this.write(id, "stats", json.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public @Nullable String loadAdvancements(final UUID id) throws IOException {
        return this.readText(id, "advancements");
    }

    @Override
    public void saveAdvancements(final UUID id, final String json) throws IOException {
        this.write(id, "advancements", json.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Set<UUID> listPlayers() throws IOException {
        Faults.fail(Faults.take("list"));
        final Set<UUID> ids = new HashSet<>();
        if (Files.isDirectory(this.directory)) {
            try (Stream<Path> files = Files.list(this.directory)) {
                files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".data"))
                    .forEach(name -> ids.add(UUID.fromString(name.substring(0, name.length() - ".data".length()))));
            }
        }
        return ids;
    }

    @Override
    public void close() {
        this.closed = true;
        System.out.println("ST players closed");
    }
}
