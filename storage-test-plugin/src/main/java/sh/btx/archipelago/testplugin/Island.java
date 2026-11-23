package sh.btx.archipelago.testplugin;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import sh.btx.archipelago.storage.StoredData;
import sh.btx.archipelago.storage.WorldStorage.ChunkDataType;

/**
 * A world format that is one compressed file. Chunks are taken apart into sections, and chunks of only air are left
 * out, which takes reading and rebuilding the chunk NBT.
 *
 * @param chunks every chunk's data
 * @param data the world's other data
 */
record Island(Map<ChunkRef, StoredData> chunks, Map<Key, StoredData> data) {

    private static final int MAGIC = 0x49534C45; // ISLE
    private static final int FORMAT = 1;

    record ChunkRef(ChunkDataType type, int x, int z) {
    }

    private record Position(int x, int z) {
    }

    static Island empty() {
        return new Island(new HashMap<>(), new HashMap<>());
    }

    Island copy() {
        return new Island(new HashMap<>(this.chunks), new HashMap<>(this.data));
    }

    static Island read(final Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file); DataInputStream header = new DataInputStream(raw)) {
            if (header.readInt() != MAGIC || header.readInt() != FORMAT) {
                throw new IOException(file + " is not an island");
            }
            final Island island = empty();
            try (DataInputStream in = new DataInputStream(new GZIPInputStream(header))) {
                for (int i = in.readInt(); i > 0; i--) {
                    final int x = in.readInt();
                    final int z = in.readInt();
                    if (in.readBoolean()) {
                        final CompoundBinaryTag rest = readData(in).toCompound();
                        final List<BinaryTag> sections = new ArrayList<>();
                        for (int count = in.readInt(); count > 0; count--) {
                            sections.add(readData(in).toCompound());
                        }
                        final CompoundBinaryTag chunk = rest.put("sections", ListBinaryTag.listBinaryTag(BinaryTagTypes.COMPOUND, sections));
                        island.chunks.put(new ChunkRef(ChunkDataType.CHUNK, x, z), StoredData.of(chunk));
                    }
                    for (final ChunkDataType type : List.of(ChunkDataType.ENTITIES, ChunkDataType.POI)) {
                        if (in.readBoolean()) {
                            island.chunks.put(new ChunkRef(type, x, z), readData(in));
                        }
                    }
                }
                for (int i = in.readInt(); i > 0; i--) {
                    island.data.put(Key.key(in.readUTF()), readData(in));
                }
                if (in.read() != -1) { // reading to the end also checks gzip's CRC
                    throw new IOException(file + " has trailing data");
                }
            }
            return island;
        }
    }

    /**
     * Writes next to the file and moves it into place, so a crash leaves the old file or the new one.
     *
     * @return how many chunks were written
     */
    int write(final Path file, final boolean skipAir, final long slowWriteMillis) throws IOException {
        final Map<Position, Map<ChunkDataType, StoredData>> positions = new HashMap<>();
        this.chunks.forEach((ref, stored) -> positions.computeIfAbsent(new Position(ref.x(), ref.z()), ignored -> new HashMap<>()).put(ref.type(), stored));
        if (skipAir) {
            for (final var iterator = positions.values().iterator(); iterator.hasNext(); ) {
                if (isAir(iterator.next())) {
                    iterator.remove();
                }
            }
        }

        Files.createDirectories(file.getParent());
        final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            final OutputStream unclosed = new OutputStream() {
                @Override
                public void write(final int b) throws IOException {
                    this.write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(final byte[] bytes, final int offset, final int length) throws IOException {
                    final ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    if (slowWriteMillis > 0) {
                        StorageTest.sleep(slowWriteMillis); // so that kill tests hit the middle of writes
                    }
                }
            };
            final DataOutputStream header = new DataOutputStream(unclosed);
            header.writeInt(MAGIC);
            header.writeInt(FORMAT);
            header.flush();
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(unclosed))) {
                out.writeInt(positions.size());
                for (final Map.Entry<Position, Map<ChunkDataType, StoredData>> entry : positions.entrySet()) {
                    out.writeInt(entry.getKey().x());
                    out.writeInt(entry.getKey().z());
                    final StoredData chunkData = entry.getValue().get(ChunkDataType.CHUNK);
                    out.writeBoolean(chunkData != null);
                    if (chunkData != null) {
                        final CompoundBinaryTag chunk = chunkData.toCompound();
                        final ListBinaryTag sections = chunk.getList("sections", BinaryTagTypes.COMPOUND);
                        writeData(out, StoredData.of(chunk.remove("sections")));
                        out.writeInt(sections.size());
                        for (int i = 0; i < sections.size(); i++) {
                            writeData(out, StoredData.of(sections.getCompound(i)));
                        }
                    }
                    for (final ChunkDataType type : List.of(ChunkDataType.ENTITIES, ChunkDataType.POI)) {
                        final StoredData stored = entry.getValue().get(type);
                        out.writeBoolean(stored != null);
                        if (stored != null) {
                            writeData(out, stored);
                        }
                    }
                }
                out.writeInt(this.data.size());
                for (final Map.Entry<Key, StoredData> entry : this.data.entrySet()) {
                    out.writeUTF(entry.getKey().asString());
                    writeData(out, entry.getValue());
                }
            }
            channel.force(true);
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return positions.size();
    }

    // only for void worlds, which generate the same air again
    private static boolean isAir(final Map<ChunkDataType, StoredData> parts) throws IOException {
        final StoredData chunkData = parts.get(ChunkDataType.CHUNK);
        if (chunkData == null) {
            return false;
        }
        final StoredData entities = parts.get(ChunkDataType.ENTITIES);
        if (entities != null && !entities.toCompound().getList("Entities").isEmpty()) {
            return false;
        }
        final StoredData poi = parts.get(ChunkDataType.POI);
        if (poi != null) {
            final CompoundBinaryTag sections = poi.toCompound().getCompound("Sections");
            for (final String y : sections.keySet()) {
                if (!sections.getCompound(y).getList("Records").isEmpty()) {
                    return false;
                }
            }
        }
        final CompoundBinaryTag chunk = chunkData.toCompound();
        if (!chunk.getString("Status").equals("minecraft:full")
            || !chunk.getList("block_entities").isEmpty()
            || !chunk.getList("block_ticks").isEmpty()
            || !chunk.getList("fluid_ticks").isEmpty()
            || !chunk.getCompound("ChunkBukkitValues").isEmpty()) {
            return false;
        }
        final ListBinaryTag sections = chunk.getList("sections", BinaryTagTypes.COMPOUND);
        for (int i = 0; i < sections.size(); i++) {
            final CompoundBinaryTag section = sections.getCompound(i);
            if (!isOnly(section, "block_states", "minecraft:air") || !isOnly(section, "biomes", "minecraft:the_void")) {
                return false;
            }
        }
        return true;
    }

    private static boolean isOnly(final CompoundBinaryTag section, final String container, final String name) {
        if (!section.contains(container)) {
            return true; // light-only sections
        }
        final ListBinaryTag palette = section.getCompound(container).getList("palette");
        if (palette.size() != 1) {
            return false;
        }
        final BinaryTag only = palette.get(0);
        return only instanceof final StringBinaryTag string ? string.value().equals(name)
            : only instanceof final CompoundBinaryTag state && Objects.equals(state.getString("Name"), name);
    }

    private static StoredData readData(final DataInputStream in) throws IOException {
        final int length = in.readInt();
        final byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Truncated island");
        }
        return StoredData.copyOf(bytes);
    }

    private static void writeData(final DataOutputStream out, final StoredData data) throws IOException {
        final byte[] bytes = data.bytes();
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
