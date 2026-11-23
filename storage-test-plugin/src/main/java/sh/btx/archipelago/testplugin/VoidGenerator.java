package sh.btx.archipelago.testplugin;

import java.util.List;
import java.util.Random;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

final class VoidGenerator extends ChunkGenerator {

    static volatile boolean crash;
    static volatile boolean failBiomeProvider;
    static volatile boolean failPopulators;
    static volatile Runnable whileCreating;

    @Override
    public List<org.bukkit.generator.BlockPopulator> getDefaultPopulators(final org.bukkit.World world) {
        if (failPopulators) {
            throw new IllegalStateException("injected populators failure");
        }
        return List.of();
    }

    @Override
    public void generateNoise(final WorldInfo world, final Random random, final int chunkX, final int chunkZ, final ChunkData chunk) {
        if (crash) {
            throw new IllegalStateException("injected generator crash");
        }
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(final WorldInfo world) {
        if (failBiomeProvider) {
            throw new IllegalStateException("injected biome provider failure");
        }
        final Runnable action = whileCreating;
        if (action != null) {
            whileCreating = null;
            action.run();
        }
        return new BiomeProvider() {
            @Override
            public Biome getBiome(final WorldInfo info, final int x, final int y, final int z) {
                return Biome.THE_VOID;
            }

            @Override
            public List<Biome> getBiomes(final WorldInfo info) {
                return List.of(Biome.THE_VOID);
            }
        };
    }
}
