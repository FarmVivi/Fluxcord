package fr.farmvivi.fluxcord.core;

import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.storage.StorageKey;
import fr.farmvivi.fluxcord.core.config.CoreConfiguration;
import fr.farmvivi.fluxcord.core.config.CoreSettings;
import fr.farmvivi.fluxcord.core.testing.PluginCalls;
import fr.farmvivi.fluxcord.core.testing.PluginJars;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Plan item B2: the engine boots and stops as an instance, from a directory and a {@link DiscordAPI}, without
 * touching statics or {@code System.exit}. Discord is a mock whose {@code connect()} completes immediately.
 */
class FluxcordRuntimeTest {

    @TempDir Path root;
    private CoreConfiguration config;
    private CoreSettings settings;
    private DiscordAPI discord;
    private JDA jda;
    private String previousPluginsDir;
    private FluxcordRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        config = new CoreConfiguration(root.resolve("config.yml").toFile());
        config.set("discord.token", "test-token");
        config.set("data.storage.file.folder", root.resolve("data").toString());
        config.set("data.binary.storage.file.folder", root.resolve("binary").toString());
        config.set("permissions.operators", List.of("42"));
        config.set("commands.default-prefix", "?");
        config.set("language.default", "fr-FR");
        settings = CoreSettings.from(config, root.toFile());

        jda = mock(JDA.class);
        when(jda.getStatus()).thenReturn(JDA.Status.LOADING_SUBSYSTEMS); // never CONNECTED: no slash sync in tests
        discord = mock(DiscordAPI.class);
        when(discord.connect()).thenReturn(CompletableFuture.completedFuture(null));
        when(discord.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
        when(discord.getJDA()).thenReturn(jda);

        Files.createDirectories(root.resolve("plugins"));
        previousPluginsDir = System.getProperty("plugins.dir");
        System.setProperty("plugins.dir", root.resolve("plugins").toString());
        System.clearProperty("fixture.fail");
        PluginCalls.reset();
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.stop();
        }
        if (previousPluginsDir == null) {
            System.clearProperty("plugins.dir");
        } else {
            System.setProperty("plugins.dir", previousPluginsDir);
        }
    }

    @Test
    void wiresServicesFromTheConfiguration() {
        runtime = new FluxcordRuntime(root.toFile(), settings, discord, InputStream.nullInputStream());

        assertEquals(Locale.FRANCE, runtime.getLanguageManager().getDefaultLocale());
        assertEquals("?", runtime.getCommandService().getPrefix());
        assertTrue(runtime.getPermissionManager().isOperator("42"));
        assertFalse(runtime.getPermissionManager().isOperator("43"));
        assertSame(discord, runtime.getDiscordAPI());
        assertFalse(runtime.isStarted());
        assertTrue(root.resolve("plugins").isAbsolute() && root.resolve("plugins").toFile().isDirectory());
    }

    @Test
    void startRunsTheBootSequenceInOrderAndStopReversesIt() throws Exception {
        PluginJars.plugin(root.resolve("plugins"), "alpha");
        runtime = new FluxcordRuntime(root.toFile(), settings, discord, InputStream.nullInputStream());
        runtime.startHealthServer(0);
        int port = runtime.getHealthServer().getPort();
        assertEquals(503, status(port, "/readyz"), "not ready before start()");

        runtime.start();

        assertTrue(runtime.isStarted());
        assertEquals(List.of("onLoad", "onPreEnable", "onEnable", "onPostEnable"), PluginCalls.of("alpha"));
        assertEquals(200, status(port, "/readyz"));
        assertTrue(runtime.getCommandService().isEnabled());
        assertTrue(runtime.getConsoleCommandService().isRunning());
        assertSame(jda, runtime.getCommandService().getJDA());
        var order = inOrder(discord);
        order.verify(discord).connect();
        order.verify(discord).setStartupPresence();
        order.verify(discord).setDefaultPresence();
        assertThrows(IllegalStateException.class, runtime::start, "start() is not reentrant");

        // something to persist, so stop() has a flush to prove
        runtime.getDataStorageManager().getGuildStorage("g1").set("commands.prefix", "$");

        runtime.stop();

        assertTrue(runtime.isStopped());
        assertEquals(List.of("onLoad", "onPreEnable", "onEnable", "onPostEnable", "onPreDisable", "onDisable", "onPostDisable"),
                PluginCalls.of("alpha"));
        verify(discord).disconnect();
        assertFalse(runtime.getCommandService().isEnabled());
        assertFalse(runtime.getConsoleCommandService().isRunning());
        assertTrue(Files.exists(root.resolve("data").resolve("storage").resolve("guild").resolve("g1").resolve("data.json")), "storage flushed on stop");
        assertThrows(Exception.class, () -> status(port, "/healthz"), "health server stopped");
        runtime.stop(); // idempotent
        verify(discord, times(1)).disconnect();
    }

    @Test
    void aFailedConnectionPropagatesAndStopStillCleansUp() throws Exception {
        PluginJars.plugin(root.resolve("plugins"), "alpha");
        when(discord.connect()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("bad token")));
        runtime = new FluxcordRuntime(root.toFile(), settings, discord, InputStream.nullInputStream());

        RuntimeException failure = assertThrows(RuntimeException.class, runtime::start);
        assertTrue(failure.getCause() instanceof IllegalStateException, String.valueOf(failure.getCause()));
        assertEquals(List.of("onLoad", "onPreEnable"), PluginCalls.of("alpha"), "plugins were never enabled");

        runtime.stop();
        assertTrue(runtime.isStopped());
        verify(discord).disconnect();
        assertFalse(runtime.getCommandService().isEnabled());
    }

    @Test
    void shutdownRequestReleasesTheWaiter() throws Exception {
        runtime = new FluxcordRuntime(root.toFile(), settings, discord, InputStream.nullInputStream());
        Thread waiter = new Thread(() -> {
            try {
                runtime.awaitShutdownRequest();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        assertTrue(waiter.isAlive());
        runtime.requestShutdown();
        waiter.join(2000);
        assertFalse(waiter.isAlive(), "awaitShutdownRequest returned after requestShutdown");
        assertFalse(runtime.isStopped(), "requesting does not stop; the waiter decides");
    }

    @Test
    void guildOperatorsAreTheOwnerAndAdministrators() {
        Guild guild = mock(Guild.class);
        Member admin = mock(Member.class);
        Member plain = mock(Member.class);
        when(jda.getGuildById("g")).thenReturn(guild);
        when(guild.getOwnerId()).thenReturn("owner");
        when(guild.getMemberById("admin")).thenReturn(admin);
        when(guild.getMemberById("plain")).thenReturn(plain);
        when(admin.hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);

        assertTrue(FluxcordRuntime.isGuildOperator(jda, "owner", "g"));
        assertTrue(FluxcordRuntime.isGuildOperator(jda, "admin", "g"));
        assertFalse(FluxcordRuntime.isGuildOperator(jda, "plain", "g"));
        assertFalse(FluxcordRuntime.isGuildOperator(jda, "unknown", "g"));
        assertFalse(FluxcordRuntime.isGuildOperator(jda, "owner", "nope"), "unknown guild");
    }

    @Test
    void storedGuildPrefixSurvivesARestart() throws Exception {
        runtime = new FluxcordRuntime(root.toFile(), settings, discord, InputStream.nullInputStream());
        runtime.start();
        runtime.getCommandService().setPrefix("g1", "$");
        runtime.stop();

        runtime = new FluxcordRuntime(root.toFile(), settings, discord, InputStream.nullInputStream());
        assertEquals("$", runtime.getCommandService().getPrefix("g1"));
        assertEquals("$", runtime.getDataStorageManager().getGuildStorage("g1").get("commands.prefix", String.class).orElseThrow());
        assertEquals("?", runtime.getCommandService().getPrefix("g2"));
        assertEquals("$", runtime.getDataStorageManager().getGuildStorage("g1")
                .get(StorageKey.guild("g1", "commands.prefix").getKey(), String.class).orElseThrow());
    }

    private static int status(int port, String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://localhost:" + port + path).toURL().openConnection();
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(1000);
        try {
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }
}
