package sh.btx.archipelago.storage;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.bukkit.support.environment.AllFeatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@AllFeatures
class StorageFilesTest {

    @TempDir
    Path folder;

    @Test
    void markerPersistsAndCanBeEdited() throws IOException {
        final StorageMarker marker = new StorageMarker(this.folder);
        marker.add("minecraft:overworld");
        marker.add(StorageMarker.PLAYERS);

        final StorageMarker read = new StorageMarker(this.folder);
        assertTrue(read.contains(StorageMarker.PLAYERS));
        assertEquals(Set.of("minecraft:overworld"), read.worlds());

        Files.writeString(read.file(), "# players\n\n  minecraft:overworld  \n");
        final StorageMarker edited = new StorageMarker(this.folder);
        assertFalse(edited.contains(StorageMarker.PLAYERS));
        assertEquals(Set.of("minecraft:overworld"), edited.worlds());
    }

    @Test
    void noMarkerUntilSomethingIsStored() throws IOException {
        assertTrue(new StorageMarker(this.folder).worlds().isEmpty());
        assertEquals(0, this.folder.toFile().list().length);
    }

    @Test
    void worldConfigIsKeptInStorageAndOnlyWrittenWhenChanged() throws IOException {
        final FakeWorldStorage backend = new FakeWorldStorage();
        final StoredWorld world = new StoredWorld(Identifier.fromNamespaceAndPath("test", "world"), backend, closed -> {});
        final Path file = this.folder.resolve("paper-world.yml");

        YamlConfigurationLoader.Builder builder = YamlConfigurationLoader.builder().path(file);
        assertTrue(StorageWorldConfig.redirect(builder, world));
        YamlConfigurationLoader loader = builder.build();
        final CommentedConfigurationNode node = loader.load();
        node.node("seed").set(1234L);
        loader.save(node);
        assertEquals(1, backend.calls.stream().filter("writeData"::equals).count());

        builder = YamlConfigurationLoader.builder().path(file);
        assertFalse(StorageWorldConfig.redirect(builder, world));
        loader = builder.build();
        final CommentedConfigurationNode again = loader.load();
        assertEquals(1234L, again.node("seed").getLong());
        loader.save(again);
        assertEquals(1, backend.calls.stream().filter("writeData"::equals).count());
        assertFalse(Files.exists(file));
    }

    @Test
    void onlyOneProviderOfEach() {
        final PaperStorageRegistrar registrar = new PaperStorageRegistrar((provider, plugin) -> {}, (players, plugin) -> {}, (maps, plugin) -> {});
        assertThrows(IllegalStateException.class, () -> registrar.worlds(key -> null)); // outside of the event

        final BootstrapContext owner = Mockito.mock(BootstrapContext.class, Mockito.withSettings().defaultAnswer(Answers.RETURNS_DEEP_STUBS));
        Mockito.when(owner.getPluginMeta().getName()).thenReturn("Test");
        registrar.setCurrentContext(owner);
        registrar.worlds(key -> null);
        assertThrows(IllegalStateException.class, () -> registrar.worlds(key -> null));
        registrar.maps(Mockito.mock(MapStorage.class));
        assertThrows(IllegalStateException.class, () -> registrar.maps(Mockito.mock(MapStorage.class)));

        registrar.invalidate();
        assertThrows(IllegalStateException.class, () -> registrar.players(Mockito.mock(PlayerStorage.class)));
    }
}
