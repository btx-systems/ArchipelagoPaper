package sh.btx.archipelago.storage;

import com.google.common.base.Preconditions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * An immutable NBT compound, as given to and returned by storages.
 * <p>
 * The contents are the ones vanilla saves, including {@code DataVersion}. The server upgrades the contents of old data
 * when it reads it, so a storage can keep the bytes as they are. Data from a newer version is refused. When Minecraft
 * changes which data exists, like moving some between keys, stored data has to be moved by the storage.
 */
@ApiStatus.Experimental
@NullMarked
public final class StoredData {

    private static final byte COMPOUND_TAG_ID = 10;

    private final byte[] nbt;

    private StoredData(final byte[] nbt) {
        this.nbt = nbt;
    }

    /**
     * Creates data from bytes, as returned by {@link #bytes()}.
     *
     * @param nbt an uncompressed NBT compound with a named root, which is the format of NBT files before compression
     * @return the data
     * @throws IllegalArgumentException if the bytes don't start with a compound
     */
    public static StoredData copyOf(final byte[] nbt) {
        Preconditions.checkArgument(nbt != null, "nbt cannot be null");
        Preconditions.checkArgument(nbt.length > 0 && nbt[0] == COMPOUND_TAG_ID, "Expected an uncompressed NBT compound");
        return new StoredData(nbt.clone());
    }

    /**
     * Creates data from a compound.
     *
     * @param tag the compound
     * @return the data
     * @throws UncheckedIOException if it can't be encoded, like a string over 64KiB
     */
    public static StoredData of(final CompoundBinaryTag tag) {
        Preconditions.checkArgument(tag != null, "tag cannot be null");
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            BinaryTagIO.writer().write(tag, out);
        } catch (final IOException ex) {
            throw new UncheckedIOException("Could not encode NBT", ex); // strings over 64KiB
        }
        return new StoredData(out.toByteArray());
    }

    /**
     * Gets the data as bytes.
     *
     * @return a copy of the uncompressed NBT, with a named root
     */
    public byte[] bytes() {
        return this.nbt.clone();
    }

    /**
     * Gets the size of the data.
     *
     * @return the length of {@link #bytes()}
     */
    public int size() {
        return this.nbt.length;
    }

    /**
     * Parses the data, to read or edit it.
     * <p>
     * Minecraft saves lists of mixed types as lists of compounds. These are returned as they are saved, use
     * {@link net.kyori.adventure.nbt.ListBinaryTag#unwrapHeterogeneity()} to get the values.
     *
     * @return the compound
     * @throws IOException if the data is not valid NBT
     */
    public CompoundBinaryTag toCompound() throws IOException {
        try {
            return BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(this.nbt));
        } catch (final RuntimeException | StackOverflowError ex) { // unknown types, negative lengths, too much depth
            throw new IOException("Not valid NBT", ex);
        }
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        return other instanceof final StoredData data && Arrays.equals(this.nbt, data.nbt);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.nbt);
    }

    @Override
    public String toString() {
        return "StoredData[" + this.nbt.length + " bytes]";
    }
}
