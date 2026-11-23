package sh.btx.archipelago.storage;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@AllFeatures
class StoredDataTest {

    private static CompoundTag everything() {
        final CompoundTag tag = new CompoundTag();
        tag.putByte("byte", (byte) -7);
        tag.putShort("short", (short) -300);
        tag.putInt("int", Integer.MIN_VALUE);
        tag.putLong("long", Long.MAX_VALUE);
        tag.putFloat("float", 1.5F);
        tag.putDouble("double", -0.1D);
        tag.putString("string", "nul:\u0000 accent:é emoji:🏝");
        tag.putString("", "empty key");
        tag.putByteArray("bytes", new byte[]{-128, 0, 127});
        tag.putIntArray("ints", new int[]{1, -2, 3});
        tag.putLongArray("longs", new long[]{1L, -2L, Long.MIN_VALUE});
        tag.put("empty list", new ListTag());
        tag.put("empty compound", new CompoundTag());

        final ListTag mixed = new ListTag();
        mixed.add(IntTag.valueOf(4));
        mixed.add(StringTag.valueOf("four"));
        mixed.add(new CompoundTag());
        tag.put("mixed", mixed);

        final ListTag nested = new ListTag();
        nested.add(mixed.copy());
        tag.put("nested", nested);
        return tag;
    }

    @Test
    void minecraftToAdventureAndBackLosesNothing() throws IOException {
        final CompoundTag original = everything();
        final CompoundBinaryTag parsed = StoredNbt.encode(original).toCompound();
        assertEquals(original, StoredNbt.decode(StoredData.of(parsed)));
    }

    @Test
    void mixedListsCanBeUnwrapped() throws IOException {
        final var list = StoredNbt.encode(everything()).toCompound().getList("mixed").unwrapHeterogeneity();
        assertEquals(List.of(BinaryTagTypes.INT, BinaryTagTypes.STRING, BinaryTagTypes.COMPOUND), List.of(list.get(0).type(), list.get(1).type(), list.get(2).type()));
    }

    @Test
    void largeDataHasNoSizeLimit() throws IOException {
        final CompoundTag tag = new CompoundTag();
        tag.putByteArray("large", new byte[8 * 1024 * 1024]); // adventure's default reader stops at 2MiB
        assertEquals(tag, StoredNbt.decode(StoredData.of(StoredNbt.encode(tag).toCompound())));
    }

    @Test
    void isImmutable() throws IOException {
        final byte[] source = StoredNbt.encode(everything()).bytes();
        final byte[] expected = source.clone();
        final StoredData data = StoredData.copyOf(source);
        Arrays.fill(source, (byte) 0);
        Arrays.fill(data.bytes(), (byte) 0);
        assertArrayEquals(expected, data.bytes());
    }

    @Test
    void onlyAcceptsCompounds() {
        assertThrows(IllegalArgumentException.class, () -> StoredData.copyOf(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> StoredData.copyOf(new byte[]{0x1f, (byte) 0x8b, 8, 0})); // gzip
    }

    @Test
    void truncationAnywhereIsAnIOException() throws IOException {
        final byte[] bytes = StoredNbt.encode(everything()).bytes();
        for (int length = 1; length < bytes.length; length++) {
            final StoredData truncated = StoredData.copyOf(Arrays.copyOf(bytes, length));
            assertThrows(IOException.class, () -> StoredNbt.decode(truncated), "length " + length);
        }
    }

    @Test
    void newerDataIsRefused() throws IOException {
        final int current = SharedConstants.getCurrentVersion().dataVersion().version();
        final CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", current);
        assertEquals(tag, StoredNbt.decode(StoredNbt.encode(tag)));
        tag.putInt("DataVersion", current + 1);
        final StoredData newer = StoredNbt.encode(tag);
        assertThrows(IOException.class, () -> StoredNbt.decode(newer));
    }

    @Test
    void invalidNbtIsAnIOExceptionInTheApiToo() {
        for (final byte[] bad : new byte[][]{
            {10, 0, 0, 99, 0, 0}, // unknown type
            {10, 0, 0, 9, 0, 0, 1, -1, -1, -1, -1, 0}, // negative length
            {10, 0, 0},
        }) {
            assertThrows(IOException.class, () -> StoredData.copyOf(bad).toCompound(), Arrays.toString(bad));
        }
    }
}
