package fr.farmvivi.fluxcord.plugins.music;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.command.CommandBuilder;
import fr.farmvivi.fluxcord.api.command.PluginCommandAdapter;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.plugins.music.testing.MemoryDataStorage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link MusicPlugin}'s wiring: which permissions and commands it declares, which JDA listeners it
 * installs, and what it reads from {@code config.yml}. This is the part a smoke run also proves,
 * but here a rename or a missing language key fails the build instead of the bot.
 */
class MusicPluginTest {

    @TempDir Path dataFolder;

    private MusicPlugin plugin;
    private PluginCommandAdapter commands;
    private PluginPermissionAdapter permissions;
    private DiscordAPI discordAPI;
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        commands = mock(PluginCommandAdapter.class);
        permissions = mock(PluginPermissionAdapter.class);
        discordAPI = mock(DiscordAPI.class);

        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(anyString())).thenAnswer(i -> i.getArgument(0));
        when(language.getString(any(Locale.class), anyString())).thenAnswer(i -> i.getArgument(1));

        configuration = mock(Configuration.class);
        when(configuration.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(configuration.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));

        PluginContext context = mock(PluginContext.class);
        when(context.getPluginId()).thenReturn("music-plugin");
        when(context.getPluginName()).thenReturn("Fluxcord Plugin - Music");
        when(context.getPluginVersion()).thenReturn("3.0.0-TEST");
        when(context.getLogger()).thenReturn(LoggerFactory.getLogger("music-plugin-test"));
        when(context.getEventManager()).thenReturn(mock(EventManager.class));
        when(context.getDiscordAPI()).thenReturn(discordAPI);
        when(context.getConfiguration()).thenReturn(configuration);
        when(context.getDataFolder()).thenReturn(dataFolder.toString());
        when(context.getAudioService()).thenReturn(mock(AudioService.class));
        when(context.getCommands()).thenReturn(commands);
        when(context.getPermissions()).thenReturn(permissions);
        when(context.getLanguage()).thenReturn(language);
        when(context.getStorage()).thenReturn(new PluginDataStorageAdapter("music-plugin", new MemoryDataStorage()));

        plugin = new MusicPlugin();
        plugin.onLoad(context);
    }

    @AfterEach
    void tearDown() {
        if (plugin.getScheduler() != null && !plugin.getScheduler().isShutdown()) {
            plugin.onDisable();
        }
    }

    /** The names of the commands registered, by running each registration against a recording builder. */
    private List<String> registeredCommandNames() {
        ArgumentCaptor<Consumer<CommandBuilder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(commands, atLeastOnce()).registerCommand(captor.capture());

        List<String> names = new ArrayList<>();
        for (Consumer<CommandBuilder> registration : captor.getAllValues()) {
            CommandBuilder builder = mock(CommandBuilder.class, invocation ->
                    CommandBuilder.class.isAssignableFrom(invocation.getMethod().getReturnType())
                            ? invocation.getMock() : null);
            registration.accept(builder);
            ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
            verify(builder).name(name.capture());
            names.add(name.getValue());
        }
        return names;
    }

    @Test
    void everyPermissionNodeIsNamespacedByThePluginId() {
        plugin.onEnable();

        ArgumentCaptor<Permission> captor = ArgumentCaptor.forClass(Permission.class);
        verify(permissions, atLeastOnce()).registerPermission(captor.capture());

        Map<String, PermissionDefault> declared = new java.util.HashMap<>();
        captor.getAllValues().forEach(p -> declared.put(p.getName(), p.getDefault()));

        assertEquals(Map.of(
                "music-plugin.play", PermissionDefault.TRUE,
                "music-plugin.skip", PermissionDefault.TRUE,
                "music-plugin.queue", PermissionDefault.TRUE,
                "music-plugin.playlist", PermissionDefault.TRUE,
                "music-plugin.volume", PermissionDefault.OP,
                "music-plugin.admin", PermissionDefault.OP), declared);
        assertEquals("music-plugin.admin", plugin.permissionKey("admin"));
    }

    @Test
    void theWholeCommandSetIsRegistered() {
        plugin.onEnable();

        assertEquals(List.of("play", "pause", "skip", "stop", "queue", "nowplaying", "volume", "loop",
                        "shuffle", "clear", "remove", "seek", "playlist"),
                registeredCommandNames());
    }

    @Test
    void theJdaListenersAreInstalledOnEnable() {
        plugin.onEnable();

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(discordAPI).addEventListeners(same(plugin), captor.capture());

        Object[] installed = captor.getValue();
        List<String> listeners = java.util.Arrays.stream(installed)
                .map(listener -> listener.getClass().getSimpleName()).toList();
        assertTrue(listeners.containsAll(List.of("MusicButtonListener", "MusicModalListener",
                "MusicReadyListener", "MusicVoiceListener")), listeners.toString());
        assertTrue(java.util.Arrays.stream(installed).allMatch(ListenerAdapter.class::isInstance));
    }

    @Test
    void theManagersAreAvailableOnceEnabled() {
        assertNull(plugin.getMusicManager(), "nothing before onEnable");

        plugin.onEnable();

        assertNotNull(plugin.getMusicManager());
        assertNotNull(plugin.getPlaylistManager());
        assertNotNull(plugin.getScheduler());
    }

    @Test
    void thePersistenceSettingsComeFromTheConfiguration() {
        when(configuration.getBoolean("music.persistence.enabled", true)).thenReturn(true);
        when(configuration.getInt("music.persistence.ttl_seconds", 3600)).thenReturn(120);
        when(configuration.getInt("music.auto_leave_timeout", 300_000)).thenReturn(45_000);

        plugin.onEnable();

        assertTrue(plugin.isPersistenceEnabled());
        assertEquals(120_000L, plugin.getPersistenceTtlMillis());
        assertEquals(45_000L, plugin.getAutoLeaveTimeoutMs());
    }

    @Test
    void aTtlOfZeroMeansNeverExpire() {
        when(configuration.getInt("music.persistence.ttl_seconds", 3600)).thenReturn(0);

        plugin.onEnable();

        assertEquals(0L, plugin.getPersistenceTtlMillis());
    }

    @Test
    void persistenceCanBeDisabled() {
        when(configuration.getBoolean("music.persistence.enabled", true)).thenReturn(false);

        plugin.onEnable();

        assertFalse(plugin.isPersistenceEnabled());
    }

    @Test
    void anAlreadyConnectedJdaRestoresPlaybackWithoutWaitingForReady() throws Exception {
        JDA jda = mock(JDA.class);
        when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);
        when(jda.getGuilds()).thenReturn(List.of());
        when(discordAPI.getJDA()).thenReturn(jda);

        plugin.onEnable();

        // The restore is dispatched on the plugin scheduler (hot-reload path, no ReadyEvent to come).
        verify(jda, timeout(2000).atLeastOnce()).getGuilds();
    }

    @Test
    void disablingShutsTheSchedulerDownAndIsIdempotent() {
        plugin.onEnable();

        plugin.onDisable();

        assertTrue(plugin.getScheduler().isShutdown());
        assertDoesNotThrow(plugin::onDisable, "a second disable must not throw");
    }

    @Test
    void aReadyEventAfterEnableAlsoTriggersTheRestore() {
        plugin.onEnable();
        JDA jda = mock(JDA.class);
        when(jda.getGuilds()).thenReturn(List.of());
        ReadyEvent ready = mock(ReadyEvent.class);
        when(ready.getJDA()).thenReturn(jda);

        new fr.farmvivi.fluxcord.plugins.music.events.MusicReadyListener(plugin).onReady(ready);

        verify(jda, atLeastOnce()).getGuilds();
    }
}
