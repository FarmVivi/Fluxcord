package fr.farmvivi.fluxcord.plugins.aiaudio;

import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The plugin is a skeleton, so what can be tested today is its lifecycle: the permissions it
 * declares, the settings it reads and the fact that it disables cleanly. The services themselves
 * are still TODO.
 */
class AIAudioPluginTest {

    @TempDir Path dataFolder;

    private AIAudioPlugin plugin;
    private PluginPermissionAdapter permissions;
    private Configuration configuration;
    private DiscordAPI discordAPI;

    @BeforeEach
    void setUp() {
        permissions = mock(PluginPermissionAdapter.class);
        when(permissions.getRegisteredPermissions()).thenReturn(java.util.Set.of());
        discordAPI = mock(DiscordAPI.class);

        configuration = mock(Configuration.class);
        when(configuration.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        when(configuration.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));

        PluginContext context = mock(PluginContext.class);
        when(context.getPluginId()).thenReturn("ai-audio-plugin");
        when(context.getPluginName()).thenReturn("AI Audio");
        when(context.getPluginVersion()).thenReturn("3.0.0-TEST");
        when(context.getLogger()).thenReturn(LoggerFactory.getLogger("ai-audio-test"));
        when(context.getEventManager()).thenReturn(mock(EventManager.class));
        when(context.getDiscordAPI()).thenReturn(discordAPI);
        when(context.getConfiguration()).thenReturn(configuration);
        when(context.getDataFolder()).thenReturn(dataFolder.toString());
        when(context.getPermissions()).thenReturn(permissions);
        when(context.getLanguage()).thenReturn(mock(PluginLanguageAdapter.class));

        plugin = new AIAudioPlugin();
        plugin.onLoad(context);
    }

    private Map<String, PermissionDefault> declaredPermissions() {
        ArgumentCaptor<Permission> captor = ArgumentCaptor.forClass(Permission.class);
        verify(permissions, atLeastOnce()).registerPermission(captor.capture());
        Map<String, PermissionDefault> declared = new HashMap<>();
        captor.getAllValues().forEach(p -> declared.put(p.getName(), p.getDefault()));
        return declared;
    }

    @Test
    void everyPermissionIsNamespacedByThePluginId() {
        plugin.onEnable();

        assertEquals(Map.of(
                "ai-audio-plugin.transcribe", PermissionDefault.TRUE,
                "ai-audio-plugin.tts", PermissionDefault.TRUE,
                "ai-audio-plugin.voicecommands", PermissionDefault.TRUE,
                "ai-audio-plugin.analyze", PermissionDefault.OP,
                "ai-audio-plugin.admin", PermissionDefault.OP), declaredPermissions());
        assertEquals("ai-audio-plugin.admin", plugin.permissionKey("admin"));
    }

    @Test
    void theServicesExistOnceEnabledAndAreReleasedOnDisable() {
        assertNull(plugin.getSpeechRecognition(), "nothing before onEnable");

        plugin.onEnable();

        assertNotNull(plugin.getSpeechRecognition());
        assertNotNull(plugin.getTextToSpeech());
        assertNotNull(plugin.getAudioAnalysis());

        plugin.onDisable();

        assertNull(plugin.getSpeechRecognition());
        assertNull(plugin.getTextToSpeech());
        assertNull(plugin.getAudioAnalysis());
        assertDoesNotThrow(plugin::onDisable, "disabling twice must not throw");
    }

    @Test
    void theSettingsComeFromTheKeysThatActuallyExistInConfigYml() {
        // These are the keys config.yml documents; the plugin used to read ai.* names that were
        // nowhere in the file, so every one of these settings was silently ignored.
        when(configuration.getString("speech_recognition.language", "en-US")).thenReturn("fr-FR");
        when(configuration.getString("speech_recognition.confidence_threshold", "0.8")).thenReturn("0.55");
        when(configuration.getString("text_to_speech.default_voice", "en-US-Standard-A")).thenReturn("fr-FR-Wavenet-A");
        when(configuration.getBoolean("voice_commands.enabled", true)).thenReturn(false);
        when(configuration.getString("voice_commands.wake_word", "hey bot")).thenReturn("dis flux");
        when(configuration.getString("ai.openai_api_key", "")).thenReturn("sk-test");

        plugin.onEnable();

        AIAudioPlugin.AISettings settings = plugin.getSettings();
        assertEquals("fr-FR", settings.transcriptionLanguage());
        assertEquals(0.55, settings.confidenceThreshold());
        assertEquals("fr-FR-Wavenet-A", settings.ttsVoice());
        assertFalse(settings.voiceCommandsEnabled());
        assertEquals("dis flux", settings.wakeWord());
        assertEquals("sk-test", settings.openAiKey());
        assertEquals("", settings.googleCredentialsPath());
    }

    @Test
    void anUnreadableThresholdFallsBackInsteadOfBreakingTheBoot() {
        when(configuration.getString("speech_recognition.confidence_threshold", "0.8")).thenReturn("very high");

        assertDoesNotThrow(plugin::onEnable);

        assertEquals(0.8, plugin.getSettings().confidenceThreshold());
    }

    @Test
    void theVoiceListenerIsInstalledOnlyAfterTheServicesExist() {
        plugin.onEnable();

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(discordAPI).addEventListeners(same(plugin), captor.capture());
        Object[] listeners = captor.getValue();

        assertEquals(1, listeners.length);
        assertInstanceOf(ListenerAdapter.class, listeners[0]);
        // The listener forwards to the plugin, which must already hold its services: a voice event
        // can arrive before onEnable returns.
        assertNotNull(plugin.getSpeechRecognition());
    }

    @Test
    void aVoiceUpdateBeforeOrAfterTheLifecycleIsHarmless() {
        GuildVoiceUpdateEvent event = mock(GuildVoiceUpdateEvent.class);

        assertDoesNotThrow(() -> plugin.onVoiceUpdate(event), "no service yet");

        plugin.onEnable();
        assertDoesNotThrow(() -> plugin.onVoiceUpdate(event));

        plugin.onDisable();
        assertDoesNotThrow(() -> plugin.onVoiceUpdate(event), "services released");
    }
}
