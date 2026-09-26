package fr.farmvivi.fluxcord.api.plugin;

import fr.farmvivi.fluxcord.api.command.PluginCommandAdapter;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.binary.PluginBinaryStorageAdapter;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The base class every real plugin extends.
 *
 * <p>Two things here are easy to get wrong and invisible when they are: the data folder has to exist
 * before a plugin writes its first file, and the short accessors have to return the *plugin-scoped* views
 * from the context rather than anything a plugin might hold itself — that scoping is the only thing
 * keeping plugins out of each other's data.
 */
class AbstractPluginTest {

    /** A plugin that does nothing but let the base class be observed. */
    private static final class TestPlugin extends AbstractPlugin {
        private void registerListener(Object listener) {
            addDiscordListeners(listener);
        }
    }

    @TempDir Path baseDir;

    private TestPlugin plugin;
    private PluginContext context;
    private DiscordAPI discordAPI;
    private Path dataFolder;

    @BeforeEach
    void setUp() {
        dataFolder = baseDir.resolve("plugins").resolve("test-plugin");
        discordAPI = mock(DiscordAPI.class);

        context = mock(PluginContext.class);
        when(context.getPluginId()).thenReturn("test-plugin");
        when(context.getPluginName()).thenReturn("Test Plugin");
        when(context.getPluginVersion()).thenReturn("3.0.0-TEST");
        when(context.getLogger()).thenReturn(LoggerFactory.getLogger("abstract-plugin-test"));
        when(context.getEventManager()).thenReturn(mock(EventManager.class));
        when(context.getDiscordAPI()).thenReturn(discordAPI);
        when(context.getDataFolder()).thenReturn(dataFolder.toString());

        plugin = new TestPlugin();
    }

    @Test
    void loadingCreatesTheDataFolderSoAPluginCanWriteStraightAway() {
        assertFalse(Files.exists(dataFolder));

        plugin.onLoad(context);

        assertTrue(Files.isDirectory(dataFolder), "including the missing parent directories");
    }

    @Test
    void loadingAnAlreadyExistingFolderIsFine() throws Exception {
        Files.createDirectories(dataFolder);

        assertDoesNotThrow(() -> plugin.onLoad(context));

        assertTrue(Files.isDirectory(dataFolder));
    }

    @Test
    void anUncreatableFolderIsReportedButDoesNotStopTheLoad() throws Exception {
        // A plugin whose folder cannot be created should still load: it may need no files at all.
        Path blocker = baseDir.resolve("blocked");
        Files.createFile(blocker);
        when(context.getDataFolder()).thenReturn(blocker.resolve("inside").toString());

        assertDoesNotThrow(() -> plugin.onLoad(context));

        assertEquals("test-plugin", plugin.getId(), "and the plugin is usable");
    }

    @Test
    void identityComesFromTheContextAndNotFromTheClass() {
        plugin.onLoad(context);

        assertEquals("test-plugin", plugin.getId());
        assertEquals("Test Plugin", plugin.getName());
        assertEquals("3.0.0-TEST", plugin.getVersion());
        assertSame(context, plugin.getContext());
        assertNotNull(plugin.getLogger());
    }

    @Test
    void theShortAccessorsReturnTheContextsScopedViews() {
        // If any of these returned an unscoped service, the plugin would silently share keys,
        // permissions or translations with every other plugin.
        PluginCommandAdapter commands = mock(PluginCommandAdapter.class);
        PluginPermissionAdapter permissions = mock(PluginPermissionAdapter.class);
        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        PluginDataStorageAdapter storage = mock(PluginDataStorageAdapter.class);
        PluginBinaryStorageAdapter binary = mock(PluginBinaryStorageAdapter.class);
        Configuration configuration = mock(Configuration.class);
        when(context.getCommands()).thenReturn(commands);
        when(context.getPermissions()).thenReturn(permissions);
        when(context.getLanguage()).thenReturn(language);
        when(context.getStorage()).thenReturn(storage);
        when(context.getBinaryStorage()).thenReturn(binary);
        when(context.getConfiguration()).thenReturn(configuration);
        plugin.onLoad(context);

        assertSame(commands, plugin.getCommands());
        assertSame(permissions, plugin.getPermissions());
        assertSame(language, plugin.getLanguage());
        assertSame(storage, plugin.getStorage());
        assertSame(binary, plugin.getBinaryStorage());
        assertSame(configuration, plugin.getConfiguration());
        assertEquals(dataFolder.toString(), plugin.getDataFolder());
    }

    @Test
    void aFreshPluginIsDiscoveredAndOnlyEnabledOnceItsLifecycleSaysSo() {
        assertEquals(PluginLifecycle.DISCOVERED, plugin.getLifecycle());
        assertFalse(plugin.isEnabled());

        plugin.setLifecycle(PluginLifecycle.ENABLING);
        assertFalse(plugin.isEnabled(), "enabling is not enabled");

        plugin.setLifecycle(PluginLifecycle.ENABLED);
        assertTrue(plugin.isEnabled());

        plugin.setLifecycle(PluginLifecycle.DISABLED);
        assertFalse(plugin.isEnabled());
    }

    @Test
    void everyLifecycleHookIsSafeToCallAndLogsItself() {
        // The default implementations are what a plugin overriding only onEnable relies on.
        plugin.onLoad(context);

        assertDoesNotThrow(() -> {
            plugin.onPreEnable();
            plugin.onEnable();
            plugin.onPostEnable();
            plugin.onPreDisable();
            plugin.onDisable();
            plugin.onPostDisable();
        });
    }

    @Test
    void discordListenersAreRegisteredOnBehalfOfThePlugin() {
        // Passing the plugin is what lets the core remove them again on disable or reload; a listener
        // registered anonymously outlives its plugin and throws on the next event.
        plugin.onLoad(context);
        ListenerAdapter listener = new ListenerAdapter() {
        };

        plugin.registerListener(listener);

        verify(discordAPI).addEventListeners(same(plugin), eq(listener));
    }

    @Test
    void aPluginHasNoMigrationClassUnlessItSaysSo() {
        assertNull(plugin.getMigrationClass(),
                "returning something here would make PluginConfiguration run a migration");
    }
}
