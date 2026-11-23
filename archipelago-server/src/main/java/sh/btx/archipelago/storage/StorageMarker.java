package sh.btx.archipelago.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.NullMarked;

/**
 * Records in the level folder what is stored by plugins, so that a server whose storage plugin is missing or broken
 * refuses to start, instead of generating new worlds and players.
 */
@NullMarked
final class StorageMarker {

    static final String FILE_NAME = "archipelago-storage.txt";
    static final String PLAYERS = "players";
    static final String MAPS = "maps";
    // one entry for all, there can be any number of short-lived ones
    static final String OTHER_WORLDS = "other-worlds";

    private static final List<String> HEADER = List.of(
        "# These are kept in storage provided by plugins, so are not in this folder.",
        "# The server refuses to start if a plugin doesn't provide them.",
        "# To go back to built-in storage for one, delete its line. It starts out as whatever is in this folder."
    );

    private final Path file;
    private final Set<String> entries = new TreeSet<>();

    StorageMarker(final Path levelDirectory) throws IOException {
        this.file = levelDirectory.resolve(FILE_NAME);
        if (Files.exists(this.file)) {
            for (final String line : Files.readAllLines(this.file)) {
                final String entry = line.trim();
                if (!entry.isEmpty() && !entry.startsWith("#")) {
                    this.entries.add(entry);
                }
            }
        }
    }

    Path file() {
        return this.file;
    }

    synchronized boolean contains(final String entry) {
        return this.entries.contains(entry);
    }

    synchronized Set<String> worlds() {
        final Set<String> worlds = new TreeSet<>(this.entries);
        worlds.remove(PLAYERS);
        worlds.remove(MAPS);
        return Collections.unmodifiableSet(worlds);
    }

    synchronized void add(final String entry) throws IOException {
        if (this.entries.contains(entry)) {
            return;
        }
        final Set<String> updated = new TreeSet<>(this.entries);
        updated.add(entry);
        final List<String> lines = new ArrayList<>(HEADER);
        lines.addAll(updated);
        final Path temp = this.file.resolveSibling(FILE_NAME + ".tmp");
        Files.write(temp, lines);
        Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        this.entries.add(entry);
    }
}
