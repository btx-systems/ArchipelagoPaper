package sh.btx.archipelago.testplugin;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.math.Position;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.advancement.Advancement;
import org.bukkit.block.Barrel;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import sh.btx.archipelago.storage.WorldStorage.ChunkDataType;

/**
 * Console commands for testing. Each prints lines starting with "ST", which test scripts wait for.
 */
final class TestCommand implements BasicCommand {

    // chunks in different regions, and on both sides of 0
    private static final int[][] SPOTS = {{0, 0}, {-1, -1}, {5, 9}, {40, -40}, {-100, 100}};
    private static final int FLOOR = 64;
    private static final NamespacedKey ADVANCEMENT = NamespacedKey.minecraft("story/mine_stone");

    private final StorageTestPlugin plugin;
    private final NamespacedKey token;
    private final NamespacedKey counter;
    private final Map<String, IslandStorage> storages = new HashMap<>();

    TestCommand(final StorageTestPlugin plugin) {
        this.plugin = plugin;
        this.token = new NamespacedKey(plugin, "token");
        this.counter = new NamespacedKey(plugin, "counter");
    }

    @Override
    public String permission() {
        return "archipelago.storagetest";
    }

    @Override
    public void execute(final CommandSourceStack source, final String[] args) {
        try {
            final String result = this.run(args);
            System.out.println("ST ok " + String.join(" ", args) + (result.isEmpty() ? "" : " -> " + result));
        } catch (final Throwable thr) {
            final List<String> causes = new ArrayList<>();
            for (Throwable cause = thr; cause != null; cause = cause.getCause()) {
                causes.add(cause.toString().replace('\n', ' '));
            }
            System.out.println("ST error " + String.join(" ", args) + " -> " + String.join(" <- ", causes));
        }
    }

    private static NamespacedKey key(final String name) {
        return name.contains(":") ? Objects.requireNonNull(NamespacedKey.fromString(name)) : new NamespacedKey("st", name);
    }

    private static World world(final String name) {
        return Objects.requireNonNull(Bukkit.getWorld(key(name)), "no world " + name);
    }

    private WorldCreator creator(final String name) {
        return WorldCreator.ofKey(key(name))
            .generator(new VoidGenerator())
            .generateStructures(false)
            .forcedSpawnPosition(Position.block(0, FLOOR + 1, 0), 0F, 0F);
    }

    private World create(final String name, final IslandStorage storage) {
        final World world = Objects.requireNonNull(this.creator(name).storage(storage).createWorld(), "createWorld returned null");
        this.storages.put(name, storage);
        return world;
    }

    private String run(final String[] args) throws Exception {
        final String name = args.length > 1 ? args[1] : "";
        switch (args[0]) {
            case "create" -> {
                final long start = System.nanoTime();
                final World world = this.create(name, IslandStorage.load(name, args.length > 2 && args[2].equals("readonly"), true));
                return "uid=" + world.getUID() + " ms=" + (System.nanoTime() - start) / 1_000_000;
            }
            case "create-normal" -> { // vanilla generation
                final IslandStorage storage = IslandStorage.load(name, false, false);
                final World world = Objects.requireNonNull(WorldCreator.ofKey(key(name)).seed(1234L).storage(storage).createWorld());
                this.storages.put(name, storage);
                return "uid=" + world.getUID() + " seed=" + world.getSeed() + " spawn=" + world.getSpawnLocation().toVector();
            }
            case "create-provided" -> {
                return "uid=" + Objects.requireNonNull(this.creator("provided:" + name).createWorld()).getUID();
            }
            case "create-vanilla" -> {
                return "uid=" + Objects.requireNonNull(this.creator(name).createWorld()).getUID();
            }
            case "clone" -> {
                final long start = System.nanoTime();
                final IslandStorage live = this.storages.get(name);
                final Island template = live != null && !live.closed ? live.snapshot() : Island.read(StorageTest.islandFile(name));
                final World world = this.create(args[2], IslandStorage.copy(template, args[2], args.length > 3 && args[3].equals("keep-id")));
                return "uid=" + world.getUID() + " ms=" + (System.nanoTime() - start) / 1_000_000;
            }
            case "unload" -> {
                return "result=" + Bukkit.unloadWorld(world(name), Boolean.parseBoolean(args[2]));
            }
            case "save" -> {
                final long start = System.nanoTime();
                world(name).save(Boolean.parseBoolean(args[2]));
                return "ms=" + (System.nanoTime() - start) / 1_000_000;
            }
            case "set" -> {
                this.populate(world(name), args[2]);
                return "";
            }
            case "check" -> {
                return this.check(world(name), args[2], this.storages.get(name));
            }
            case "mark" -> { // one chunk
                final Chunk chunk = world(name).getChunkAt(Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                chunk.getBlock(8, FLOOR, 8).setType(Objects.requireNonNull(Material.matchMaterial(args[4])), false);
                return "";
            }
            case "read" -> {
                final Chunk chunk = world(name).getChunkAt(Integer.parseInt(args[2]), Integer.parseInt(args[3]));
                return "block=" + chunk.getBlock(8, FLOOR, 8).getType() + " entities=" + Arrays.stream(chunk.getEntities()).filter(ArmorStand.class::isInstance).count();
            }
            case "spawn-unloaded" -> { // ends up in a transient entity chunk
                final World world = world(name);
                final int x = Integer.parseInt(args[2]);
                final int z = Integer.parseInt(args[3]);
                final ArmorStand stand = world.createEntity(new Location(world, (x << 4) + 4.5, FLOOR + 1, (z << 4) + 4.5), ArmorStand.class);
                stand.setGravity(false);
                stand.setPersistent(true);
                world.addEntity(stand);
                return "loaded=" + world.isChunkLoaded(x, z);
            }
            case "unload-chunk" -> {
                return "result=" + world(name).unloadChunk(Integer.parseInt(args[2]), Integer.parseInt(args[3]), true);
            }
            case "chunk-loaded" -> {
                return "result=" + world(name).isChunkLoaded(Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            }
            case "water" -> { // a scheduled tick that hasn't run when the world unloads
                final World world = world(name);
                final Chunk chunk = world.getChunkAt(20, 20);
                for (int x = 6; x <= 10; x++) {
                    for (int z = 6; z <= 10; z++) {
                        chunk.getBlock(x, FLOOR, z).setType(Material.STONE, false);
                    }
                }
                chunk.getBlock(8, FLOOR + 1, 8).setType(Material.WATER, true);
                return "unloaded=" + Bukkit.unloadWorld(world, true);
            }
            case "water-load" -> {
                world(name).getChunkAt(20, 20).addPluginChunkTicket(this.plugin);
                return "";
            }
            case "water-check" -> {
                return "flowed=" + (world(name).getChunkAt(20, 20).getBlock(9, FLOOR + 1, 8).getType() == Material.WATER);
            }
            case "uid" -> {
                final World world = world(name);
                return "uid=" + world.getUID() + " seed=" + world.getSeed() + " spawn=" + world.getSpawnLocation().toVector();
            }
            case "fault" -> {
                Faults.arm(args[1], args[2], Integer.parseInt(args[3]), args.length > 4 ? Integer.parseInt(args[4]) : 0);
                return "";
            }
            case "faults-clear" -> {
                Faults.clear();
                return "";
            }
            case "nested-create" -> {
                final boolean withStorage = args[2].equals("storage");
                final IslandStorage inner = IslandStorage.load("nested_inner", false, true);
                final List<String> failure = new ArrayList<>();
                VoidGenerator.whileCreating = () -> {
                    try {
                        final WorldCreator creator = this.creator(name);
                        (withStorage ? creator.storage(inner) : creator).createWorld();
                    } catch (final IllegalStateException ex) {
                        failure.add(ex.getMessage());
                    }
                };
                final IslandStorage outer = IslandStorage.load(name, false, true);
                this.create(name, outer);
                inner.close();
                return "outer-open=" + !outer.closed + " inner=" + failure;
            }
            case "fail-after-added" -> {
                final IslandStorage storage = IslandStorage.load(name, false, true);
                VoidGenerator.failPopulators = true;
                try {
                    this.create(name, storage);
                    return "created";
                } catch (final RuntimeException ex) {
                    return "failed closed=" + storage.closed + " loaded=" + (Bukkit.getWorld(key(name)) != null);
                } finally {
                    VoidGenerator.failPopulators = false;
                }
            }
            case "save-touches-world" -> {
                final World world = world(name);
                final IslandStorage storage = this.storages.get(name);
                storage.onSave = switch (args[2]) {
                    case "read" -> () -> world.getBlockAt(16 * (700 + (int) (System.nanoTime() % 1000)), FLOOR, 16 * 700).getType();
                    case "save" -> () -> world.save(true);
                    default -> () -> Bukkit.unloadWorld(world, true);
                };
                world.getPersistentDataContainer().set(this.token, PersistentDataType.STRING, "dirty " + System.nanoTime());
                world.getChunkAt(0, 0).getBlock(1, FLOOR, 1).setType(Material.EMERALD_BLOCK, false);
                try {
                    world.save(true);
                } finally {
                    storage.onSave = null;
                }
                return "returned loaded=" + (Bukkit.getWorld(key(name)) == world);
            }
            case "unload-in-save-event" -> {
                final World world = world(name);
                final boolean recreate = args[2].equals("recreate");
                final boolean viaUnload = args[3].equals("unload");
                world.getChunkAt(0, 0).getBlock(1, FLOOR, 1).setType(Material.GOLD_BLOCK, false);
                final org.bukkit.event.Listener listener = new org.bukkit.event.Listener() {
                };
                final boolean[] ran = {false};
                Bukkit.getPluginManager().registerEvent(org.bukkit.event.world.WorldSaveEvent.class, listener, org.bukkit.event.EventPriority.NORMAL, (ignored, event) -> {
                    if (ran[0] || !((org.bukkit.event.world.WorldSaveEvent) event).getWorld().equals(world)) {
                        return;
                    }
                    ran[0] = true;
                    Bukkit.unloadWorld(world, true);
                    if (recreate) {
                        try {
                            this.create(name, IslandStorage.load(name, false, true));
                        } catch (final java.io.IOException ex) {
                            throw new IllegalStateException(ex);
                        }
                    }
                }, this.plugin);
                try {
                    if (viaUnload) {
                        Bukkit.unloadWorld(world, true);
                    } else {
                        world.save(true);
                    }
                } finally {
                    org.bukkit.event.HandlerList.unregisterAll(listener);
                }
                final World now = Bukkit.getWorld(key(name));
                return "returned loaded=" + (now != null) + (now == null ? "" : " block=" + now.getChunkAt(0, 0).getBlock(1, FLOOR, 1).getType());
            }
            case "has-metadata" -> {
                final IslandStorage storage = this.storages.get(name);
                return "stored=" + storage.snapshot().data().containsKey(sh.btx.archipelago.storage.WorldDataKeys.METADATA);
            }
            case "age-entities" -> { // as an island from 1.21.4 would have them
                final Island island = Island.read(StorageTest.islandFile(name));
                final var doubles = net.kyori.adventure.nbt.BinaryTagTypes.DOUBLE;
                final var entity = net.kyori.adventure.nbt.CompoundBinaryTag.builder()
                    .putString("id", "minecraft:text_display")
                    .putString("text", "\"[Rare]\"")
                    .putString("CustomName", "\"[Rare]\"")
                    .putIntArray("UUID", new int[]{11, 22, 33, 44})
                    .put("Pos", net.kyori.adventure.nbt.ListBinaryTag.listBinaryTag(doubles, List.of(
                        net.kyori.adventure.nbt.DoubleBinaryTag.doubleBinaryTag(40 * 16 + 4.5),
                        net.kyori.adventure.nbt.DoubleBinaryTag.doubleBinaryTag(70),
                        net.kyori.adventure.nbt.DoubleBinaryTag.doubleBinaryTag(-40 * 16 + 4.5)
                    )))
                    .build();
                island.chunks().put(new Island.ChunkRef(ChunkDataType.ENTITIES, 40, -40), sh.btx.archipelago.storage.StoredData.of(
                    net.kyori.adventure.nbt.CompoundBinaryTag.builder()
                        .putInt("DataVersion", 4189)
                        .putIntArray("Position", new int[]{40, -40})
                        .put("Entities", net.kyori.adventure.nbt.ListBinaryTag.listBinaryTag(net.kyori.adventure.nbt.BinaryTagTypes.COMPOUND, List.of(entity)))
                        .build()
                ));
                island.write(StorageTest.islandFile(name), false, 0L);
                return "";
            }
            case "aged-text" -> {
                final List<String> found = new ArrayList<>();
                for (final var entity : world(name).getChunkAt(40, -40).getEntities()) {
                    if (entity instanceof final org.bukkit.entity.TextDisplay display) {
                        found.add("text=" + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(display.text())
                            + " name=" + net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Objects.requireNonNull(display.customName())));
                    }
                }
                return found.toString();
            }
            case "guards" -> {
                return this.guards();
            }
            case "cycle" -> {
                this.cycle(name, Integer.parseInt(args[2]));
                return "started";
            }
            case "loop" -> {
                this.loop(name);
                return "started";
            }
            case "counter" -> {
                final World world = world(name);
                final StringBuilder result = new StringBuilder("world=").append(this.count(world.getPersistentDataContainer().get(this.counter, PersistentDataType.LONG)));
                for (final int[] spot : SPOTS) {
                    result.append(" chunk=").append(this.count(world.getChunkAt(spot[0], spot[1]).getPersistentDataContainer().get(this.counter, PersistentDataType.LONG)));
                }
                return result.toString();
            }
            case "fill" -> { // something to time
                final World world = world(name);
                final int radius = Integer.parseInt(args[2]);
                final Material[] palette = {Material.STONE, Material.DIRT, Material.OAK_LOG, Material.SAND, Material.GLASS};
                for (int x = -radius; x < radius; x++) {
                    for (int z = -radius; z < radius; z++) {
                        final Chunk chunk = world.getChunkAt(x, z);
                        for (int i = 0; i < 16 * 16 * 4; i++) {
                            chunk.getBlock(i & 15, FLOOR + (i >> 8), (i >> 4) & 15).setType(palette[(i * 31 + x * 7 + z * 13 & 0x7fffffff) % palette.length], false);
                        }
                    }
                }
                return "";
            }
            case "late-disable" -> {
                Bukkit.getPluginManager().disablePlugin(this.plugin);
                return "";
            }
            case "reload" -> {
                Bukkit.reload();
                return "";
            }
            case "freeze" -> {
                System.out.println("ST freezing");
                StorageTest.sleep(Long.parseLong(name));
                return "";
            }
            case "crash" -> {
                VoidGenerator.crash = true;
                world(name).getChunkAtAsync(3000, 3000);
                return "crashing";
            }
            case "player-set" -> {
                return this.setPlayer(Objects.requireNonNull(Bukkit.getPlayerExact(name), "not online"), args[2], args.length > 3 ? args[3] : null);
            }
            case "player" -> {
                return this.describe(Objects.requireNonNull(Bukkit.getPlayerExact(name), "not online"));
            }
            case "offline" -> {
                return this.describeOffline(UUID.fromString(name));
            }
            case "offline-stat" -> {
                Bukkit.getOfflinePlayer(UUID.fromString(name)).incrementStatistic(Statistic.ANIMALS_BRED, Integer.parseInt(args[2]));
                return "";
            }
            default -> throw new IllegalArgumentException("unknown " + args[0]);
        }
    }

    private long count(final Long stored) {
        return stored == null ? 0L : stored;
    }

    private void populate(final World world, final String value) {
        world.getPersistentDataContainer().set(this.token, PersistentDataType.STRING, value);
        world.setGameRule(GameRules.KEEP_INVENTORY, true);
        world.setGameRule(GameRules.RANDOM_TICK_SPEED, 17);
        world.getWorldBorder().setSize(1234.0);
        world.setDifficulty(Difficulty.HARD);
        world.setSpawnLocation(7, 70, 9);
        for (final int[] spot : SPOTS) {
            final Chunk chunk = world.getChunkAt(spot[0], spot[1]);
            chunk.getPersistentDataContainer().set(this.token, PersistentDataType.STRING, value);
            chunk.getBlock(8, FLOOR, 8).setType(Material.DIAMOND_BLOCK);
            chunk.getBlock(8, FLOOR + 1, 8).setType(Material.BELL);
            chunk.getBlock(3, FLOOR, 3).setType(Material.SEA_LANTERN);
            chunk.getBlock(9, FLOOR, 8).setType(Material.BARREL);
            final Barrel barrel = (Barrel) chunk.getBlock(9, FLOOR, 8).getState(false);
            final ItemStack item = ItemStack.of(Material.STICK, 13);
            item.editMeta(meta -> meta.customName(Component.text(value)));
            barrel.getInventory().setItem(4, item);

            Arrays.stream(chunk.getEntities()).filter(ArmorStand.class::isInstance).forEach(org.bukkit.entity.Entity::remove);
            world.spawn(chunk.getBlock(12, FLOOR + 1, 12).getLocation().add(0.5, 0, 0.5), ArmorStand.class, stand -> {
                stand.setGravity(false);
                stand.setPersistent(true);
                stand.customName(Component.text(value));
            });
        }
    }

    private String check(final World world, final String value, final IslandStorage storage) {
        final List<String> wrong = new ArrayList<>();
        expect(wrong, "world pdc", value, world.getPersistentDataContainer().get(this.token, PersistentDataType.STRING));
        expect(wrong, "keep_inventory", true, world.getGameRuleValue(GameRules.KEEP_INVENTORY));
        expect(wrong, "random_tick_speed", 17, world.getGameRuleValue(GameRules.RANDOM_TICK_SPEED));
        expect(wrong, "border", 1234.0, world.getWorldBorder().getSize());
        expect(wrong, "difficulty", Difficulty.HARD, world.getDifficulty());
        final Location spawn = world.getSpawnLocation();
        expect(wrong, "spawn", "7,70,9", spawn.getBlockX() + "," + spawn.getBlockY() + "," + spawn.getBlockZ());
        for (final int[] spot : SPOTS) {
            final String at = spot[0] + "," + spot[1] + " ";
            final Chunk chunk = world.getChunkAt(spot[0], spot[1]);
            expect(wrong, at + "pdc", value, chunk.getPersistentDataContainer().get(this.token, PersistentDataType.STRING));
            expect(wrong, at + "block", Material.DIAMOND_BLOCK, chunk.getBlock(8, FLOOR, 8).getType());
            expect(wrong, at + "bell", Material.BELL, chunk.getBlock(8, FLOOR + 1, 8).getType());
            if (chunk.getBlock(9, FLOOR, 8).getState(false) instanceof final Barrel barrel) {
                final ItemStack item = barrel.getInventory().getItem(4);
                expect(wrong, at + "item", 13, item == null ? 0 : item.getAmount());
                expect(wrong, at + "item name", Component.text(value), item == null ? null : item.getItemMeta().customName());
            } else {
                wrong.add(at + "barrel missing");
            }
            final List<ArmorStand> stands = Arrays.stream(chunk.getEntities()).filter(ArmorStand.class::isInstance).map(ArmorStand.class::cast).toList();
            expect(wrong, at + "entities", 1, stands.size());
            if (!stands.isEmpty()) {
                expect(wrong, at + "entity name", Component.text(value), stands.getFirst().customName());
            }
            if (storage != null) {
                final var poi = storage.peek(ChunkDataType.POI, spot[0], spot[1]);
                expect(wrong, at + "poi", true, poi != null && new String(poi.bytes(), StandardCharsets.ISO_8859_1).contains("minecraft:meeting"));
            }
        }
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("wrong: " + wrong);
        }
        return "all match";
    }

    private static void expect(final List<String> wrong, final String what, final Object expected, final Object actual) {
        if (!Objects.equals(expected, actual)) {
            wrong.add(what + "=" + actual + " not " + expected);
        }
    }

    @FunctionalInterface
    private interface Attempt {
        void run() throws Exception;
    }

    private static void mustFail(final List<String> wrong, final String what, final Attempt attempt) {
        try {
            attempt.run();
            wrong.add(what + " didn't fail");
        } catch (final Exception ex) {
            System.out.println("ST guard " + what + ": " + ex);
        }
    }

    private String guards() throws Exception {
        final List<String> wrong = new ArrayList<>();
        final IslandStorage first = IslandStorage.load("guard_a", false, true);
        final World a = this.create("guard_a", first);
        a.save(true);

        final IslandStorage sameId = IslandStorage.copy(first.snapshot(), "guard_b", true);
        mustFail(wrong, "duplicate uuid", () -> this.create("guard_b", sameId));
        expect(wrong, "duplicate uuid closed", true, sameId.closed);
        expect(wrong, "duplicate uuid not loaded", null, Bukkit.getWorld(key("guard_b")));

        mustFail(wrong, "instance used twice", () -> this.create("guard_c", first));
        expect(wrong, "used instance still open", false, first.closed);

        final IslandStorage other = IslandStorage.load("guard_other", false, true);
        mustFail(wrong, "existing world, other storage", () -> this.create("guard_a", other));
        expect(wrong, "rejected storage left to caller", false, other.closed);
        other.close();
        expect(wrong, "existing world, same storage", a, this.creator("guard_a").storage(first).createWorld());

        final IslandStorage unreadable = IslandStorage.load("guard_d", false, true);
        Faults.arm("readData", "io", 1);
        mustFail(wrong, "unreadable data", () -> this.create("guard_d", unreadable));
        expect(wrong, "unreadable closed", true, unreadable.closed);

        final IslandStorage badGenerator = IslandStorage.load("guard_e", false, true);
        VoidGenerator.failBiomeProvider = true;
        try {
            mustFail(wrong, "failing generator", () -> this.create("guard_e", badGenerator));
        } finally {
            VoidGenerator.failBiomeProvider = false;
        }
        expect(wrong, "failing generator closed", true, badGenerator.closed);
        this.create("guard_e", IslandStorage.load("guard_e", false, true)); // the key is usable again

        first.onSave = () -> a.save(true);
        a.save(true); // is logged, and must not hang or recurse
        first.onSave = null;

        expect(wrong, "unload", true, Bukkit.unloadWorld(a, true));
        expect(wrong, "unload closed", true, first.closed);
        final World replacement = this.create("guard_a", IslandStorage.load("guard_a", false, true));
        expect(wrong, "stale unload refused", false, Bukkit.unloadWorld(a, false));
        expect(wrong, "replacement still loaded", replacement.getUID(), Objects.requireNonNull(Bukkit.getWorld(key("guard_a"))).getUID());
        a.save(true);
        a.save();

        Bukkit.unloadWorld(replacement, false);
        Bukkit.unloadWorld(world("guard_e"), false);
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("wrong: " + wrong);
        }
        return "all guards hold";
    }

    private void cycle(final String name, final int times) {
        new BukkitRunnable() {
            private int done;

            @Override
            public void run() {
                try {
                    final World world = world(name);
                    final long stored = TestCommand.this.count(world.getPersistentDataContainer().get(TestCommand.this.counter, PersistentDataType.LONG));
                    if (stored != this.done) {
                        throw new IllegalStateException("world has " + stored + " not " + this.done);
                    }
                    for (final int[] spot : SPOTS) {
                        final Chunk chunk = world.getChunkAt(spot[0], spot[1]);
                        final long inChunk = TestCommand.this.count(chunk.getPersistentDataContainer().get(TestCommand.this.counter, PersistentDataType.LONG));
                        if (inChunk != this.done) {
                            throw new IllegalStateException("chunk " + Arrays.toString(spot) + " has " + inChunk + " not " + this.done);
                        }
                        chunk.getPersistentDataContainer().set(TestCommand.this.counter, PersistentDataType.LONG, (long) this.done + 1);
                    }
                    world.getPersistentDataContainer().set(TestCommand.this.counter, PersistentDataType.LONG, (long) this.done + 1);
                    for (int i = 0; i < 24; i++) {
                        world.getChunkAtAsync(200 + this.done * 3 + i % 6, 200 + i / 6); // unload while these load
                    }
                    if (!Bukkit.unloadWorld(world, true)) {
                        throw new IllegalStateException("didn't unload");
                    }
                    TestCommand.this.create(name, IslandStorage.load(name, false, true)); // the same tick
                    if (++this.done == times) {
                        this.cancel();
                        System.out.println("ST cycle done " + this.done);
                    }
                } catch (final Throwable thr) {
                    this.cancel();
                    System.out.println("ST cycle failed at " + this.done + ": " + thr);
                }
            }
        }.runTaskTimer(this.plugin, 1L, 1L);
    }

    private void loop(final String name) {
        final World world = world(name);
        final IslandStorage storage = Objects.requireNonNull(this.storages.get(name));
        new BukkitRunnable() {
            private long value = TestCommand.this.count(world.getPersistentDataContainer().get(TestCommand.this.counter, PersistentDataType.LONG));

            @Override
            public void run() {
                this.value++;
                world.getPersistentDataContainer().set(TestCommand.this.counter, PersistentDataType.LONG, this.value);
                for (final int[] spot : SPOTS) {
                    world.getChunkAt(spot[0], spot[1]).getPersistentDataContainer().set(TestCommand.this.counter, PersistentDataType.LONG, this.value);
                }
                if (this.value % 3 == 0) {
                    storage.checkpoint = this.value;
                    world.save(true);
                }
            }
        }.runTaskTimer(this.plugin, 1L, 1L);
    }

    private String setPlayer(final Player player, final String value, final String worldName) {
        player.getInventory().setItem(0, ItemStack.of(Material.NETHERITE_INGOT, 17));
        player.getInventory().setItem(30, ItemStack.of(Material.DIRT, 3));
        player.setLevel(42);
        player.setStatistic(Statistic.ANIMALS_BRED, 4321);
        player.getPersistentDataContainer().set(this.token, PersistentDataType.STRING, value);
        final Advancement advancement = Objects.requireNonNull(Bukkit.getAdvancement(ADVANCEMENT));
        final var progress = player.getAdvancementProgress(advancement);
        List.copyOf(progress.getRemainingCriteria()).forEach(progress::awardCriteria);
        player.setAllowFlight(true);
        player.setFlying(true);
        final World world = worldName == null ? player.getWorld() : world(worldName);
        return "teleported=" + player.teleport(new Location(world, 33.5, 120, -21.5));
    }

    private String describe(final Player player) {
        final ItemStack first = player.getInventory().getItem(0);
        final ItemStack other = player.getInventory().getItem(30);
        return "first=" + (first == null ? 0 : first.getAmount())
            + " other=" + (other == null ? 0 : other.getAmount())
            + " level=" + player.getLevel()
            + " bred=" + player.getStatistic(Statistic.ANIMALS_BRED)
            + " advancement=" + player.getAdvancementProgress(Objects.requireNonNull(Bukkit.getAdvancement(ADVANCEMENT))).isDone()
            + " token=" + player.getPersistentDataContainer().get(this.token, PersistentDataType.STRING)
            + " world=" + player.getWorld().key().asString()
            + " x=" + player.getLocation().getX() + " z=" + player.getLocation().getZ()
            + " first-played=" + (player.getFirstPlayed() > 1_600_000_000_000L)
            + " played-before=" + player.hasPlayedBefore();
    }

    private String describeOffline(final UUID id) {
        final OfflinePlayer player = Bukkit.getOfflinePlayer(id);
        final Location location = player.getLocation();
        return "online=" + player.isOnline()
            + " played-before=" + player.hasPlayedBefore()
            + " bred=" + player.getStatistic(Statistic.ANIMALS_BRED)
            + " token=" + player.getPersistentDataContainer().get(this.token, PersistentDataType.STRING)
            + " x=" + (location == null ? null : location.getX())
            + " first-played=" + (player.getFirstPlayed() > 1_600_000_000_000L)
            + " listed=" + Arrays.stream(Bukkit.getOfflinePlayers()).anyMatch(each -> each.getUniqueId().equals(id));
    }
}
