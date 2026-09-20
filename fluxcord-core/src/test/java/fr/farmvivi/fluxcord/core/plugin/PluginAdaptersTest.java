package fr.farmvivi.fluxcord.core.plugin;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.PluginCommandAdapter;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.plugin.PluginLifecycle;
import fr.farmvivi.fluxcord.api.storage.DataStorage;
import fr.farmvivi.fluxcord.api.storage.binary.PluginBinaryStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.binary.ScopedBinaryStorage;
import fr.farmvivi.fluxcord.core.command.SimpleCommandBuilder;
import fr.farmvivi.fluxcord.core.command.SimpleCommandService;
import fr.farmvivi.fluxcord.core.config.CoreSettings;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import fr.farmvivi.fluxcord.core.language.SimpleLanguageManager;
import fr.farmvivi.fluxcord.core.permissions.SimplePermissionManager;
import fr.farmvivi.fluxcord.core.storage.SimpleDataStorageManager;
import fr.farmvivi.fluxcord.core.storage.binary.SimpleBinaryStorageManager;
import fr.farmvivi.fluxcord.core.storage.binary.file.FileBinaryStorage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The plugin-facing adapters ({@code PluginContext.getCommands()/getPermissions()/getLanguage()/getBinaryStorage()})
 * and {@link AbstractPlugin} itself, against real core services: what a plugin sees is namespaced by its id.
 */
class PluginAdaptersTest {

    static class Demo extends AbstractPlugin { }

    record Perm(String getName, String getDescription, PermissionDefault getDefault) implements Permission { }

    @TempDir Path dir;
    private final SimpleEventManager events = new SimpleEventManager();
    private final SimpleLanguageManager language = new SimpleLanguageManager(Locale.US, events);
    private final SimplePermissionManager permissions = new SimplePermissionManager(events, new SimpleDataStorageManager(mock(DataStorage.class)));
    private final JDA jda = mock(JDA.class);
    private final DiscordAPI discord = mock(DiscordAPI.class);
    private SimpleCommandService commandService;
    private PluginContextImpl context;
    private final Demo plugin = new Demo();
    private final Plugin other = mock(Plugin.class);

    @BeforeEach
    void setUp() {
        when(jda.getStatus()).thenReturn(JDA.Status.LOADING_SUBSYSTEMS);
        when(other.getId()).thenReturn("other");
        when(other.getName()).thenReturn("other");
        commandService = new SimpleCommandService(events, language, permissions,
                new CoreSettings.Commands("!", false, false, false, false), new SimpleDataStorageManager(mock(DataStorage.class)));
        commandService.setJDA(jda);
        commandService.enable();
        language.registerNamespace("demo");
        language.loadLanguage("demo", Locale.US, Map.of("hello", "Hello {0}", "plain", "Plain"));
        language.loadLanguage("demo", Locale.FRANCE, Map.of("hello", "Bonjour {0}"));

        SimpleBinaryStorageManager binary = new SimpleBinaryStorageManager(new FileBinaryStorage("file", dir.resolve("bin").toFile(), events));
        context = new PluginContextImpl("demo", "Demo", "1.0", LoggerFactory.getLogger("demo"), events, discord,
                mock(fr.farmvivi.fluxcord.api.config.Configuration.class), dir.resolve("plugins").resolve("demo").toString(),
                mock(fr.farmvivi.fluxcord.api.plugin.PluginLoader.class), getClass().getClassLoader(),
                mock(fr.farmvivi.fluxcord.api.audio.AudioService.class),
                new PluginCommandAdapter(plugin, commandService),
                new PluginPermissionAdapter(plugin, permissions, language),
                new PluginLanguageAdapter("demo", "Demo", language),
                new fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter("demo", new SimpleDataStorageManager(mock(DataStorage.class))),
                new PluginBinaryStorageAdapter("demo", binary));
        plugin.onLoad(context);
    }

    @AfterEach
    void tearDown() {
        commandService.disable();
        events.shutdown();
    }

    @Test
    void abstractPluginExposesTheContextAndItsLifecycle() {
        assertEquals("demo", plugin.getId());
        assertEquals("Demo", plugin.getName());
        assertEquals("1.0", plugin.getVersion());
        assertSame(context, plugin.getContext());
        assertSame(context.getLogger(), plugin.getLogger());
        assertSame(context.getConfiguration(), plugin.getConfiguration());
        assertEquals(context.getDataFolder(), plugin.getDataFolder());
        assertTrue(new File(plugin.getDataFolder()).isDirectory(), "onLoad creates the data folder");
        assertSame(context.getCommands(), plugin.getCommands());
        assertSame(context.getPermissions(), plugin.getPermissions());
        assertSame(context.getLanguage(), plugin.getLanguage());
        assertSame(context.getStorage(), plugin.getStorage());
        assertSame(context.getBinaryStorage(), plugin.getBinaryStorage());

        assertEquals(PluginLifecycle.DISCOVERED, plugin.getLifecycle());
        assertFalse(plugin.isEnabled());
        plugin.setLifecycle(PluginLifecycle.ENABLED);
        assertTrue(plugin.isEnabled());
        // default phase hooks only log
        plugin.onPreEnable(); plugin.onEnable(); plugin.onPostEnable();
        plugin.onPreDisable(); plugin.onDisable(); plugin.onPostDisable();
    }

    @Test
    void commandAdapterRegistersForThePluginAndOnlyUnregistersItsOwn() {
        PluginCommandAdapter commands = plugin.getCommands();
        assertSame(commandService, commands.getCommandService());
        assertTrue(commands.registerCommand(b -> b.name("ping").description("d").aliases("p").executor((c, x) -> CommandResult.success())));
        assertTrue(commands.registerCommand(new SimpleCommandBuilder().name("pong").description("d").executor((c, x) -> CommandResult.success()).build()));
        commandService.registerCommand(new SimpleCommandBuilder().name("theirs").description("d").executor((c, x) -> CommandResult.success()).build(), other);

        assertEquals(List.of("ping", "pong"), commands.getCommands().stream().map(Command::getName).sorted().toList());
        assertEquals("ping", commands.getCommand("ping").orElseThrow().getName());
        assertEquals("ping", commands.getCommandByAlias("p").orElseThrow().getName());
        assertEquals("!", commands.getPrefix());
        assertEquals("!", commands.getPrefix("g1"));
        assertFalse(commands.isOnCooldown("u", "ping"));
        assertEquals(0, commands.getRemainingCooldown("u", "ping"));

        assertFalse(commands.unregisterCommand("theirs"), "another plugin's command");
        assertFalse(commands.unregisterCommand("nope"));
        assertTrue(commands.unregisterCommand("ping"));
        assertTrue(commands.getCommand("ping").isEmpty());
        assertEquals(1, commands.unregisterAll());
        assertTrue(commands.getCommands().isEmpty());
        assertTrue(commandService.getRegistry().getCommand("theirs").isPresent(), "untouched");

        // sync helpers are plain pass-throughs (JDA not connected here: no-ops)
        commands.synchronizeCommands();
        commands.synchronizeGlobalCommands();
        commands.synchronizeGuildCommands(mock(Guild.class));
    }

    @Test
    void permissionAdapterTracksWhatThePluginRegistered() {
        PluginPermissionAdapter perms = plugin.getPermissions();
        perms.registerPermission(new Perm("demo.use", "Use", PermissionDefault.TRUE));
        perms.registerPermission(new Perm("demo.admin", "Admin", PermissionDefault.OP));

        assertEquals(Set.of("demo.use", "demo.admin"), perms.getRegisteredPermissions());
        assertTrue(perms.hasPermission("u", "demo.use"));
        assertFalse(perms.hasPermission("u", "demo.admin"));
        assertTrue(perms.hasPermission("u", "g1", "demo.use"));
        assertFalse(perms.hasPermission("u", "g1", "demo.admin"));
        assertEquals(2, permissions.getPermissions(plugin).size(), "registered on behalf of the plugin");
    }

    @Test
    void languageAdapterPrefixesKeysWithThePluginNamespace() {
        PluginLanguageAdapter lang = plugin.getLanguage();
        assertSame(language, lang.getLanguageManager());
        assertEquals(Locale.US, lang.getDefaultLocale());
        assertEquals("Plain", lang.getString("plain"));
        assertEquals("Hello Bob", lang.getString("hello", "Bob"));
        assertEquals("Bonjour Bob", lang.getString(Locale.FRANCE, "hello", "Bob"));
        assertEquals("Hello {0}", lang.getString(Locale.GERMANY, "hello"), "falls back to en-US through the manager");
        assertEquals("demo:missing", lang.getString("missing"), "the raw namespaced key is the miss signal");
    }

    @Test
    void binaryAdapterKeepsPluginFilesUnderThePluginId() throws Exception {
        ScopedBinaryStorage guild = plugin.getBinaryStorage().getGuildStorage("g1");
        assertEquals("guild:g1", guild.getScope());
        assertEquals("demo/", guild.getPrefix());

        assertTrue(guild.saveFile("covers/a.txt", new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)), false));
        try (OutputStream out = guild.getOutputStream("covers/b.txt", true)) {
            out.write("yo".getBytes(StandardCharsets.UTF_8));
        }
        File local = dir.resolve("local.txt").toFile();
        Files.writeString(local.toPath(), "from-file");
        assertTrue(guild.saveFile("c.txt", local, false));

        assertTrue(guild.fileExists("covers/a.txt"));
        assertTrue(Files.exists(dir.resolve("bin").resolve("guild").resolve("g1").resolve("demo").resolve("covers").resolve("a.txt")), "on disk under the plugin id");
        try (InputStream in = guild.getInputStream("covers/a.txt").orElseThrow()) {
            assertEquals("hi", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(List.of("covers/a.txt", "covers/b.txt"), guild.listFiles("covers").stream().sorted().toList(), "prefix stripped");
        assertEquals(2, guild.getFileSize("covers/a.txt"));
        assertTrue(guild.getLastModifiedTime("covers/a.txt") > 0);
        assertEquals("text/plain", guild.getContentType("covers/a.txt"));
        assertTrue(guild.isDirectory("covers"));
        assertTrue(guild.createDirectory("empty"));
        assertTrue(guild.getPublicUrl("covers/a.txt", 60).isEmpty(), "file backend has no public URLs");

        File downloaded = dir.resolve("dl.txt").toFile();
        assertTrue(guild.downloadFile("c.txt", downloaded));
        assertEquals("from-file", Files.readString(downloaded.toPath()));
        assertTrue(guild.deleteFile("c.txt"));
        assertFalse(guild.fileExists("c.txt"));

        ScopedBinaryStorage nested = guild.namespaced("sub");
        assertEquals("demo/sub/", nested.getPrefix());
        assertSame(plugin.getBinaryStorage().getGlobalStorage().getScope(), plugin.getBinaryStorage().getGlobalStorage().getScope());
        assertEquals("user:u1", plugin.getBinaryStorage().getUserStorage("u1").getScope());
    }
}
