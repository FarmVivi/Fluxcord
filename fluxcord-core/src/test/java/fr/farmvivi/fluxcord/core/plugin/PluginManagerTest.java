package fr.farmvivi.fluxcord.core.plugin;

import fr.farmvivi.fluxcord.core.testing.StubPlugin;
import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.command.CommandService;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.event.EventHandler;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.plugin.PluginLifecycle;
import fr.farmvivi.fluxcord.api.plugin.events.PluginEnableEvent;
import fr.farmvivi.fluxcord.api.plugin.events.PluginLifecycleChangeEvent;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.core.storage.SimpleDataStorageManager;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageManager;
import fr.farmvivi.fluxcord.core.command.SimpleCommandRegistry;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import fr.farmvivi.fluxcord.core.language.SimpleLanguageManager;
import fr.farmvivi.fluxcord.core.permissions.SimplePermissionManager;
import fr.farmvivi.fluxcord.core.storage.file.FileDataStorage;
import fr.farmvivi.fluxcord.core.testing.PluginCalls;
import fr.farmvivi.fluxcord.core.testing.PluginJars;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Engine tests with real jars built on the fly ({@link PluginJars}) and a real fixture plugin that records
 * its lifecycle through {@link PluginCalls}. Services are real where they are pure (events, i18n, storage,
 * permissions, command registry) and mocked where they need Discord (DiscordAPI, audio).
 */
class PluginManagerTest {

    @TempDir Path root;
    private File pluginsFolder;
    private SimpleEventManager events;
    private SimplePermissionManager permissions;
    private SimpleCommandRegistry registry;
    private DiscordAPI discord;
    private PluginManager manager;
    private String previousPluginsDir;

    @BeforeEach
    void setUp() throws Exception {
        pluginsFolder = root.resolve("plugins").toFile();
        Files.createDirectories(pluginsFolder.toPath());
        // PluginConfiguration resolves plugins/<id>/config.yml from this property, not from the manager's folder
        previousPluginsDir = System.getProperty("plugins.dir");
        System.setProperty("plugins.dir", pluginsFolder.getAbsolutePath());
        System.clearProperty("fixture.fail");
        PluginCalls.reset();

        events = new SimpleEventManager();
        SimpleLanguageManager language = new SimpleLanguageManager(Locale.forLanguageTag("en-US"), events);
        DataStorageManager storage = new SimpleDataStorageManager(new FileDataStorage(root.resolve("data").toFile(), events, 0));
        permissions = new SimplePermissionManager(events, storage);
        registry = new SimpleCommandRegistry();
        CommandService commandService = mock(CommandService.class);
        when(commandService.getRegistry()).thenReturn(registry);

        discord = mock(DiscordAPI.class);
        when(discord.connect()).thenReturn(CompletableFuture.completedFuture(null));
        when(discord.disconnect()).thenReturn(CompletableFuture.completedFuture(null));

        manager = new PluginManager(pluginsFolder, events, discord, language, storage,
                mock(BinaryStorageManager.class), permissions, mock(AudioService.class), commandService);
    }

    @AfterEach
    void tearDown() {
        manager.close();
        events.shutdown();
        System.clearProperty("fixture.fail");
        if (previousPluginsDir == null) {
            System.clearProperty("plugins.dir");
        } else {
            System.setProperty("plugins.dir", previousPluginsDir);
        }
    }

    private void boot() {
        manager.loadPlugins();
        manager.preEnablePlugins();
        manager.enablePlugins();
        manager.postEnablePlugins();
    }

    // --- boot -----------------------------------------------------------------------------------

    @Test
    void loadsAndEnablesAPluginThroughEveryPhase() throws Exception {
        PluginJars.plugin(pluginsFolder.toPath(), "alpha");

        boot();

        Plugin alpha = manager.getPlugin("alpha");
        assertNotNull(alpha);
        assertTrue(manager.isPluginLoaded("alpha"));
        assertEquals(PluginLifecycle.ENABLED, alpha.getLifecycle());
        assertEquals("alpha plugin", alpha.getName());
        assertEquals("1.0", alpha.getVersion());
        assertEquals(List.of("onLoad", "onPreEnable", "onEnable", "onPostEnable"), PluginCalls.of("alpha"));
        assertTrue(new File(pluginsFolder, "alpha").isDirectory(), "data folder created");
        assertSame(alpha.getClass().getClassLoader().getClass(), PluginClassLoader.class);
    }

    @Test
    void contextHandsOutPluginScopedViews() throws Exception {
        PluginJars.plugin(pluginsFolder.toPath(), "alpha");
        boot();
        AbstractPlugin alpha = (AbstractPlugin) manager.getPlugin("alpha");
        PluginContext context = alpha.getContext();

        assertSame(context.getStorage(), alpha.getStorage(), "AbstractPlugin uses the context's views (P5)");
        assertSame(context.getCommands(), alpha.getCommands());
        assertSame(context.getPermissions(), alpha.getPermissions());
        assertSame(context.getLanguage(), alpha.getLanguage());
        assertSame(context.getBinaryStorage(), alpha.getBinaryStorage());
        assertEquals("alpha.", context.getStorage().getGuildStorage("g").getPrefix());
    }

    @Test
    void pluginStringsAreReachableThroughTheContextWhateverTheIdCase() throws Exception {
        String yml = "id: MixedCase\nname: m\nversion: 1.0\nmain: " + com.example.fixture.FixturePlugin.class.getName() + "\n";
        PluginJars.build(pluginsFolder.toPath(), "mixed.jar", List.of(com.example.fixture.FixturePlugin.class),
                java.util.Map.of("plugin.yml", yml, "lang/en-US.yml", "greet: Hello\n"));
        Files.createDirectories(pluginsFolder.toPath().resolve("MixedCase").resolve("lang"));
        Files.writeString(pluginsFolder.toPath().resolve("MixedCase").resolve("lang").resolve("fr-FR.yml"), "greet: Bonjour\n");

        boot();

        AbstractPlugin plugin = (AbstractPlugin) manager.getPlugin("MixedCase");
        assertEquals("Hello", plugin.getLanguage().getString("greet"), "jar strings, namespace = plugin id");
        assertEquals("Bonjour", plugin.getLanguage().getString(java.util.Locale.FRANCE, "greet"), "plugins/<id>/lang override");
    }

    @Test
    void shutdownDisablesInReverseOrderAndReleasesEverything() throws Exception {
        PluginJars.plugin(pluginsFolder.toPath(), "alpha");
        PluginJars.plugin(pluginsFolder.toPath(), "beta", "dependencies: [alpha]\n");
        boot();
        Plugin beta = manager.getPlugin("beta");
        permissions.registerPermission(new Perm("beta.x", PermissionDefault.TRUE), beta);
        registry.register(new fr.farmvivi.fluxcord.core.command.SimpleCommand("bcmd", "d", null, null, null, null, null,
                null, null, false, null, false, null, true, 0, (c, x) -> null), beta);
        PluginCalls.reset();

        manager.close();

        assertNull(permissions.getPermission("beta.x"), "phased shutdown releases permissions (P1)");
        assertTrue(registry.getCommand("bcmd").isEmpty(), "and commands");

        assertEquals(List.of("beta:onPreDisable", "alpha:onPreDisable", "beta:onDisable", "alpha:onDisable",
                "beta:onPostDisable", "alpha:onPostDisable"), PluginCalls.all());
        assertEquals(PluginLifecycle.DISABLED, beta.getLifecycle());
        assertTrue(manager.getPlugins().isEmpty());
        assertNull(manager.getPlugin("alpha"));
    }

    @Test
    void dependenciesAreLoadedAndEnabledFirst() throws Exception {
        PluginJars.plugin(pluginsFolder.toPath(), "zeta");
        PluginJars.plugin(pluginsFolder.toPath(), "alpha", "dependencies: [zeta]\n");

        boot();

        assertEquals(List.of("zeta:onLoad", "alpha:onLoad", "zeta:onPreEnable", "alpha:onPreEnable",
                "zeta:onEnable", "alpha:onEnable", "zeta:onPostEnable", "alpha:onPostEnable"), PluginCalls.all());
    }

    @Test
    void missingDependencyKeepsThePluginOut() throws Exception {
        PluginJars.plugin(pluginsFolder.toPath(), "alpha", "dependencies: [ghost]\n");
        PluginJars.plugin(pluginsFolder.toPath(), "beta");

        boot();

        assertNull(manager.getPlugin("alpha"));
        assertNotNull(manager.getPlugin("beta"));
        assertTrue(PluginCalls.of("alpha").isEmpty(), "never instantiated");
    }

    @Test
    void jarsWithoutDescriptorOrWithABadMainAreSkipped() throws Exception {
        PluginJars.build(pluginsFolder.toPath(), "junk.jar", List.of(), java.util.Map.of("readme.txt", "x"));
        PluginJars.build(pluginsFolder.toPath(), "badmain.jar", List.of(),
                java.util.Map.of("plugin.yml", "id: badmain\nname: b\nversion: 1\nmain: com.example.Missing\n"));
        PluginJars.plugin(pluginsFolder.toPath(), "alpha");

        boot();

        assertEquals(1, manager.getPlugins().size());
        assertEquals(PluginLifecycle.ENABLED, manager.getPlugin("alpha").getLifecycle());
    }

    @Test
    void aPluginFailingInOnePhaseGoesToErrorAndSkipsTheNextPhases() throws Exception {
        PluginJars.plugin(pluginsFolder.toPath(), "alpha");
        PluginJars.plugin(pluginsFolder.toPath(), "beta");
        System.setProperty("fixture.fail", "alpha:onEnable");

        boot();

        assertEquals(PluginLifecycle.ERROR, manager.getPlugin("alpha").getLifecycle());
        assertEquals(List.of("onLoad", "onPreEnable", "onEnable"), PluginCalls.of("alpha"));
        assertEquals(PluginLifecycle.ENABLED, manager.getPlugin("beta").getLifecycle());
    }

    @Test
    void aFailedHardDependencyTakesItsDependantsDownWithIt() throws Exception {
        PluginJars.plugin(pluginsFolder.toPath(), "alpha");
        PluginJars.plugin(pluginsFolder.toPath(), "beta", "dependencies: [alpha]\n");
        PluginJars.plugin(pluginsFolder.toPath(), "gamma", "dependencies: [beta]\n");
        PluginJars.plugin(pluginsFolder.toPath(), "delta", "soft-dependencies: [alpha]\n");
        System.setProperty("fixture.fail", "alpha:onEnable");

        boot();

        assertEquals(PluginLifecycle.ERROR, manager.getPlugin("alpha").getLifecycle());
        assertEquals(PluginLifecycle.ERROR, manager.getPlugin("beta").getLifecycle(), "hard dependant");
        assertEquals(PluginLifecycle.ERROR, manager.getPlugin("gamma").getLifecycle(), "transitive dependant");
        assertEquals(PluginLifecycle.ENABLED, manager.getPlugin("delta").getLifecycle(), "soft dependency: unaffected");
        assertEquals(List.of("onLoad", "onPreEnable"), PluginCalls.of("beta"), "never enabled after alpha failed");
        assertEquals(List.of("onLoad", "onPreEnable"), PluginCalls.of("gamma"));
        assertTrue(manager.getFailedPlugins().containsAll(List.of("alpha", "beta", "gamma")));
    }

    @Test
    void aDependantOfAPluginThatFailedToLoadIsNotLoaded() throws Exception {
        PluginJars.build(pluginsFolder.toPath(), "alpha.jar", List.of(),
                java.util.Map.of("plugin.yml", "id: alpha\nname: a\nversion: 1\nmain: com.example.Missing\n"));
        PluginJars.plugin(pluginsFolder.toPath(), "beta", "dependencies: [alpha]\n");

        boot();

        assertNull(manager.getPlugin("beta"));
        assertTrue(PluginCalls.of("beta").isEmpty(), "never instantiated");
        assertTrue(manager.getFailedPlugins().containsAll(List.of("alpha", "beta")));
    }

    @Test
    void failedPluginIsStillListedAndShutdownIgnoresIt() throws Exception {
        PluginJars.plugin(pluginsFolder.toPath(), "alpha");
        System.setProperty("fixture.fail", "alpha:onPreEnable");
        boot();
        PluginCalls.reset();

        manager.close();

        assertTrue(PluginCalls.all().isEmpty(), "ERROR plugins are not disabled");
    }

    // --- lifecycle events -----------------------------------------------------------------------

    @Test
    void lifecycleChangeEventsFollowTheStateMachine() throws Exception {
        List<String> transitions = new ArrayList<>();
        class Listener {
            @EventHandler public void on(PluginLifecycleChangeEvent e) {
                transitions.add(e.getOldStatus() + ">" + e.getNewStatus());
            }
        }
        events.registerListener(new Listener(), new StubPlugin("test-listener"));
        PluginJars.plugin(pluginsFolder.toPath(), "alpha");

        boot();

        assertEquals(List.of("LOADED>PRE_ENABLING", "PRE_ENABLING>ENABLING", "ENABLING>POST_ENABLING", "POST_ENABLING>ENABLED"),
                transitions);
    }

    @Test
    void enableEventCanVetoAReload() throws Exception {
        PluginJars.plugin(pluginsFolder.toPath(), "alpha");
        boot();
        class Veto { @EventHandler public void on(PluginEnableEvent e) { e.setCancelled(true); } }
        events.registerListener(new Veto(), new StubPlugin("test-listener"));

        assertFalse(manager.reloadPlugin("alpha"));
        Plugin reloaded = manager.getPlugin("alpha");
        assertNotNull(reloaded, "the new instance is registered even though enabling was vetoed");
        assertEquals(PluginLifecycle.LOADED, reloaded.getLifecycle());
    }

    // --- single-plugin path (reload) ------------------------------------------------------------

    @Test
    void reloadPluginReplacesTheInstanceAndCleansUpRegistrations() throws Exception {
        PluginJars.plugin(pluginsFolder.toPath(), "alpha");
        boot();
        Plugin first = manager.getPlugin("alpha");
        permissions.registerPermission(new Perm("alpha.x", PermissionDefault.TRUE), first);
        registry.register(new fr.farmvivi.fluxcord.core.command.SimpleCommand("cmd", "d", null, null, null, null, null,
                null, null, false, null, false, null, true, 0, (c, x) -> null), first);
        PluginCalls.reset();

        assertTrue(manager.reloadPlugin("alpha"));

        Plugin second = manager.getPlugin("alpha");
        assertNotNull(second);
        assertNotSame(first, second);
        assertNotSame(first.getClass(), second.getClass(), "fresh class loader");
        assertEquals(PluginLifecycle.ENABLED, second.getLifecycle());
        assertEquals(PluginLifecycle.DISABLED, first.getLifecycle());
        assertEquals(List.of("alpha:onPreDisable", "alpha:onDisable", "alpha:onPostDisable", "alpha:onLoad",
                "alpha:onPreEnable", "alpha:onEnable", "alpha:onPostEnable"), PluginCalls.all());
        assertNull(permissions.getPermission("alpha.x"), "permissions of the old instance released");
        assertTrue(registry.getCommand("cmd").isEmpty(), "commands of the old instance released");
        assertEquals(1, manager.getPlugins().size(), "keyed by id: no duplicate entry under the display name");
    }

    @Test
    void reloadOfAnUnknownPluginFails() {
        assertFalse(manager.reloadPlugin("nope"));
    }

    // --- full reload (disconnects and reconnects Discord) ---------------------------------------

    @Test
    void reloadPluginsRescansTheFolderAroundADiscordReconnection() throws Exception {
        PluginJars.plugin(pluginsFolder.toPath(), "alpha");
        boot();
        Plugin first = manager.getPlugin("alpha");
        permissions.registerPermission(new Perm("alpha.x", PermissionDefault.TRUE), first);
        PluginJars.plugin(pluginsFolder.toPath(), "beta"); // dropped in while running
        PluginCalls.reset();

        assertTrue(manager.reloadPlugins());

        assertEquals(List.of("alpha:onPreDisable", "alpha:onDisable", "alpha:onPostDisable",
                "alpha:onLoad", "beta:onLoad", "alpha:onPreEnable", "beta:onPreEnable",
                "alpha:onEnable", "beta:onEnable", "alpha:onPostEnable", "beta:onPostEnable"), PluginCalls.all());
        assertEquals(PluginLifecycle.DISABLED, first.getLifecycle());
        assertNotSame(first, manager.getPlugin("alpha"));
        assertEquals(PluginLifecycle.ENABLED, manager.getPlugin("alpha").getLifecycle());
        assertEquals(PluginLifecycle.ENABLED, manager.getPlugin("beta").getLifecycle());
        assertNull(permissions.getPermission("alpha.x"), "old registrations released");

        var order = inOrder(discord);
        order.verify(discord).setShutdownPresence();
        order.verify(discord).disconnect();
        order.verify(discord).connect();
        order.verify(discord).setStartupPresence();
        order.verify(discord).setDefaultPresence();
    }

    @Test
    void reloadPluginsWithoutDiscordLeavesThePluginsPreEnabledOnly() throws Exception {
        PluginJars.plugin(pluginsFolder.toPath(), "alpha");
        boot();
        when(discord.connect()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("gateway down")));
        PluginCalls.reset();

        assertFalse(manager.reloadPlugins());

        assertEquals(List.of("alpha:onPreDisable", "alpha:onDisable", "alpha:onPostDisable", "alpha:onLoad",
                "alpha:onPreEnable"), PluginCalls.all(), "enable phases skipped");
        assertNotEquals(PluginLifecycle.ENABLED, manager.getPlugin("alpha").getLifecycle());
        verify(discord, never()).setDefaultPresence();
    }

    // --- helpers --------------------------------------------------------------------------------

    record Perm(String getName, PermissionDefault getDefault) implements Permission {
        @Override public String getDescription() { return ""; }
    }

}
