package sh.btx.archipelago.storage;

import io.papermc.paper.world.saveddata.PaperWorldMetadata;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import net.minecraft.world.level.saveddata.WeatherData;
import org.bukkit.support.RegistryHelper;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AllFeatures
@Timeout(30)
class StorageSavedDataStorageTest {

    @TempDir
    Path folder;

    private FakeWorldStorage backend;
    private StoredWorld world;

    @BeforeEach
    void setUp() {
        this.backend = new FakeWorldStorage();
        this.world = this.newWorld();
    }

    private StoredWorld newWorld() {
        return new StoredWorld(Identifier.fromNamespaceAndPath("test", "world"), this.backend, closed -> {});
    }

    private StorageSavedDataStorage open(final StoredWorld world) {
        return world.savedData(this.folder, RegistryHelper.registryAccess());
    }

    @Test
    void roundTripsWithoutTouchingTheFolder() {
        final UUID id = UUID.randomUUID();
        final StorageSavedDataStorage data = this.open(this.world);
        data.set(PaperWorldMetadata.TYPE, new PaperWorldMetadata(id));
        data.computeIfAbsent(WeatherData.TYPE);
        data.saveAndJoin();

        assertTrue(this.backend.data.containsKey(WorldDataKeys.METADATA));
        assertEquals(id, this.open(this.newWorld()).get(PaperWorldMetadata.TYPE).uuid());
        assertEquals(0, this.folder.toFile().list().length);
    }

    @Test
    void absentIsAbsent() {
        assertNull(this.open(this.world).get(PaperWorldMetadata.TYPE));
    }

    @Test
    void failingToReadIsNotAbsent() {
        final StorageSavedDataStorage data = this.open(this.world);
        this.backend.readFailure = () -> new IOException("down");
        assertThrows(StorageException.class, () -> data.get(PaperWorldMetadata.TYPE));
        assertThrows(StorageException.class, () -> data.computeIfAbsent(PaperWorldMetadata.TYPE)); // would be a new UUID
    }

    @Test
    void unparsableDataIsNotAbsent() throws IOException {
        this.backend.data.put(WorldDataKeys.METADATA, StoredNbt.encode(new CompoundTag()));
        assertThrows(StorageException.class, () -> this.open(this.world).get(PaperWorldMetadata.TYPE));

        final CompoundTag wrong = new CompoundTag();
        wrong.put("data", new CompoundTag());
        this.backend.data.put(WorldDataKeys.METADATA, StoredNbt.encode(wrong));
        assertThrows(StorageException.class, () -> this.open(this.newWorld()).get(PaperWorldMetadata.TYPE));
    }

    @Test
    void dataUsedWhileTickingIsReadUpFront() {
        this.backend.readFailure = () -> new IOException("down");
        assertThrows(StorageException.class, () -> this.open(this.world));

        this.backend.readFailure = null;
        final StorageSavedDataStorage data = this.open(this.newWorld());
        assertTrue(data.cache.containsKey(WorldBorder.TYPE) && data.cache.containsKey(WanderingTraderData.TYPE));
    }

    @Test
    void joinWaitsForWritesInProgressWhenNothingIsDirty() throws InterruptedException {
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        this.backend.onWrite = () -> {
            writing.countDown();
            try {
                finish.await();
            } catch (final InterruptedException ex) {
                throw new AssertionError(ex);
            }
        };
        final StorageSavedDataStorage data = this.open(this.world);
        data.computeIfAbsent(WeatherData.TYPE);
        data.scheduleSave();
        assertTrue(writing.await(5, TimeUnit.SECONDS));

        final Thread joiner = Thread.ofPlatform().start(data::saveAndJoin);
        joiner.join(300);
        assertTrue(joiner.isAlive());
        finish.countDown();
        joiner.join();
    }

    @Test
    void aFailedWriteDoesNotStopLaterOnes() {
        final StorageSavedDataStorage data = this.open(this.world);
        this.backend.writeFailure = () -> new NoClassDefFoundError("gone");
        data.computeIfAbsent(WeatherData.TYPE);
        data.saveAndJoin();

        this.backend.writeFailure = null;
        data.set(PaperWorldMetadata.TYPE, new PaperWorldMetadata(UUID.randomUUID()));
        data.saveAndJoin();
        assertTrue(this.backend.data.containsKey(WorldDataKeys.METADATA));

        this.world.save().join();
        assertTrue(this.backend.data.containsKey(StoredWorld.key(WeatherData.TYPE.id())));
    }

    @Test
    void closingDiscards() {
        final StorageSavedDataStorage data = this.open(this.world);
        data.computeIfAbsent(WeatherData.TYPE);
        data.close();
        this.world.close(false);
        assertTrue(this.backend.data.isEmpty());
    }

    @Test
    void savingIncludesDirtyData() {
        this.open(this.world).computeIfAbsent(WeatherData.TYPE);
        this.world.save().join();
        assertTrue(this.backend.calls.indexOf("writeData") < this.backend.calls.indexOf("save"));
    }

    @Test
    void partlyParsableIsRefused() throws IOException {
        final CompoundTag rules = new CompoundTag();
        rules.putBoolean("minecraft:keep_inventory", true);
        rules.putString("minecraft:random_tick_speed", "many");
        final CompoundTag tag = new CompoundTag();
        tag.put("data", rules);
        tag.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        final var type = net.minecraft.world.level.gamerules.GameRuleMap.TYPE;
        this.backend.data.put(StoredWorld.key(type.id()), StoredNbt.encode(tag));
        assertThrows(StorageException.class, () -> this.open(this.world).get(type)); // would erase keep_inventory
    }

    @Test
    void clocksOfRemovedDatapacksAreDropped() throws IOException {
        final CompoundTag clock = new CompoundTag();
        clock.putLong("total_ticks", 5L);
        final CompoundTag clocks = new CompoundTag();
        clocks.put("removed_pack:clock", clock);
        final CompoundTag tag = new CompoundTag();
        tag.put("data", clocks);
        tag.putInt("DataVersion", net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
        final var type = net.minecraft.world.clock.ServerClockManager.TYPE;
        this.backend.data.put(StoredWorld.key(type.id()), StoredNbt.encode(tag));
        org.junit.jupiter.api.Assertions.assertNotNull(this.open(this.world).get(type));
    }

    private static final class Unsavable extends net.minecraft.world.level.saveddata.SavedData {
    }

    @Test
    void anErrorWhileEncodingLosesNothingElse() {
        final com.mojang.serialization.Codec<Unsavable> codec = com.mojang.serialization.Codec.INT.xmap(value -> new Unsavable(), value -> {
            throw new StackOverflowError();
        });
        final var type = new net.minecraft.world.level.saveddata.SavedDataType<>(
            Identifier.fromNamespaceAndPath("test", "unsavable"), Unsavable::new, codec, net.minecraft.util.datafix.DataFixTypes.NONE
        );
        final StorageSavedDataStorage data = this.open(this.world);
        data.computeIfAbsent(type);
        data.set(PaperWorldMetadata.TYPE, new PaperWorldMetadata(UUID.randomUUID()));
        data.computeIfAbsent(WeatherData.TYPE);
        data.saveAndJoin();
        assertTrue(this.backend.data.containsKey(WorldDataKeys.METADATA));
        assertTrue(this.backend.data.containsKey(StoredWorld.key(WeatherData.TYPE.id())));
        assertTrue(data.failedToEncode());
        this.world.close(true);
        org.junit.jupiter.api.Assertions.assertFalse(this.world.awaitClose());
    }
}
