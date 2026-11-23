package sh.btx.archipelago.storage;

import ca.spottedleaf.moonrise.patches.nbt_rw.FastNBTParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.jspecify.annotations.NullMarked;

/**
 * Converts between tags and what plugins are given. Tags are never shared with plugins: they are mutated and shared
 * within the server.
 */
@NullMarked
final class StoredNbt {

    static StoredData encode(final CompoundTag tag) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        }
        return StoredData.copyOf(bytes.toByteArray());
    }

    static CompoundTag decode(final StoredData data) throws IOException {
        return decode(new ByteArrayInputStream(data.bytes()));
    }

    static CompoundTag decode(final InputStream in) throws IOException {
        final CompoundTag tag;
        try {
            tag = FastNBTParser.nbtIoReadStream(in, NbtAccounter.unlimitedHeap());
        } catch (final IOException ex) {
            throw ex;
        } catch (final RuntimeException | StackOverflowError ex) {
            throw new IOException("Stored data is not valid NBT", ex);
        }
        // older data is upgraded, newer data can't be read without losing some of it, or exiting in the case of chunks
        checkVersion(tag.getIntOr("DataVersion", 0));
        return tag;
    }

    static void checkVersion(final int dataVersion) throws IOException {
        final int current = SharedConstants.getCurrentVersion().dataVersion().version();
        if (dataVersion > current) {
            throw new IOException("Stored data is from a newer version of Minecraft (" + dataVersion + " > " + current + ")");
        }
    }

    private StoredNbt() {
    }
}
