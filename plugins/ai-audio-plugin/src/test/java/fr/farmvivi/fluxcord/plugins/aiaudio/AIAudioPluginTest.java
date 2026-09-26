package fr.farmvivi.fluxcord.plugins.aiaudio;

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
import fr.farmvivi.fluxcord.plugins.aiaudio.testing.MemoryDataStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
 * The plugin's lifecycle: what it declares to the core, and what it reads to configure itself.
 *
 * <p>The settings tests matter more than they look: every key asserted here is one that must exist in the
 * shipped {@code config.yml}, because a lookup on a key that is not in that file silently returns its
 * default forever. That is the defect this plugin shipped with.
 */
class AIAudioPluginTest {

    private static final String PLUGIN_ID = "ai-audio-plugin";

    @TempDir Path dataFolder;

    private AIAudioPlugin plugin;
    private PluginCommandAdapter commands;
    private PluginPermissionAdapter permissions;
    private Configuration configuration;

    @BeforeEach
    void setUp() {
        commands = mock(PluginCommandAdapter.class);
        permissions = mock(PluginPermissionAdapter.class);
        when(permissions.getRegisteredPermissions()).thenReturn(java.util.Set.of());

        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(anyString())).thenAnswer(i -> i.getArgument(0));
        when(language.getString(any(Locale.class), anyString())).thenAnswer(i -> i.getArgument(1));

        configuration = mock(Configuration.class);
        when(configuration.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        when(configuration.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(configuration.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));

        PluginContext context = mock(PluginContext.class);
        when(context.getPluginId()).thenReturn(PLUGIN_ID);
        when(context.getPluginName()).thenReturn("Fluxcord Plugin - AI Audio");
        when(context.getPluginVersion()).thenReturn("3.0.0-TEST");
        when(context.getLogger()).thenReturn(LoggerFactory.getLogger("ai-audio-test"));
        when(context.getEventManager()).thenReturn(mock(EventManager.class));
        when(context.getDiscordAPI()).thenReturn(mock(DiscordAPI.class));
        when(context.getConfiguration()).thenReturn(configuration);
        when(context.getDataFolder()).thenReturn(dataFolder.toString());
        when(context.getAudioService()).thenReturn(mock(AudioService.class));
        when(context.getCommands()).thenReturn(commands);
        when(context.getPermissions()).thenReturn(permissions);
        when(context.getLanguage()).thenReturn(language);
        when(context.getStorage()).thenReturn(new PluginDataStorageAdapter(PLUGIN_ID, new MemoryDataStorage()));

        plugin = new AIAudioPlugin();
        plugin.onLoad(context);
    }

    @AfterEach
    void tearDown() {
        plugin.onDisable();
    }

    /** Replays each captured registration against a recording builder. */
    private List<RecordedCommand> registeredCommands() {
        ArgumentCaptor<Consumer<CommandBuilder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(commands, atLeastOnce()).registerCommand(captor.capture());

        List<RecordedCommand> recorded = new ArrayList<>();
        for (Consumer<CommandBuilder> registration : captor.getAllValues()) {
            CommandBuilder builder = mock(CommandBuilder.class, invocation ->
                    CommandBuilder.class.isAssignableFrom(invocation.getMethod().getReturnType())
                            ? invocation.getMock() : null);
            registration.accept(builder);

            ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
            verify(builder).name(name.capture());

            ArgumentCaptor<String> permission = ArgumentCaptor.forClass(String.class);
            String requiredPermission = mockingDetails(builder).getInvocations().stream()
                    .anyMatch(i -> i.getMethod().getName().equals("permission"))
                    ? capture(builder, permission) : null;

            List<String> options = new ArrayList<>();
            mockingDetails(builder).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().endsWith("Option"))
                    .forEach(i -> options.add(i.getArgument(0)));

            recorded.add(new RecordedCommand(name.getValue(), requiredPermission, options));
        }
        return recorded;
    }

    private String capture(CommandBuilder builder, ArgumentCaptor<String> captor) {
        verify(builder).permission(captor.capture());
        return captor.getValue();
    }

    private Map<String, PermissionDefault> declaredPermissions() {
        ArgumentCaptor<Permission> captor = ArgumentCaptor.forClass(Permission.class);
        verify(permissions, atLeastOnce()).registerPermission(captor.capture());
        Map<String, PermissionDefault> declared = new HashMap<>();
        captor.getAllValues().forEach(p -> declared.put(p.getName(), p.getDefault()));
        return declared;
    }

    @Test
    void onlyThePermissionsThePluginActuallyEnforcesAreDeclared() {
        // The generated version declared 'analyze' and 'voicecommands' for features that did not exist.
        plugin.onPreEnable();

        assertEquals(Map.of(
                PLUGIN_ID + ".transcribe", PermissionDefault.TRUE,
                PLUGIN_ID + ".tts", PermissionDefault.TRUE,
                PLUGIN_ID + ".admin", PermissionDefault.OP), declaredPermissions());
        assertEquals(PLUGIN_ID + ".admin", plugin.permissionKey("admin"));
    }

    @Test
    void theCommandsAreRegisteredWithTheirOptionsAndPermissions() {
        plugin.onEnable();

        List<RecordedCommand> registered = registeredCommands();

        assertEquals(List.of("speak", "silence", "transcribe", "forget"),
                registered.stream().map(RecordedCommand::name).toList());
        RecordedCommand speak = registered.get(0);
        assertEquals(PLUGIN_ID + ".tts", speak.permission());
        assertEquals(List.of("text", "voice"), speak.options());
        assertEquals(PLUGIN_ID + ".transcribe", registered.get(2).permission());
        assertEquals(List.of("action"), registered.get(2).options());
        assertNull(registered.get(3).permission(),
                "erasing one's own history needs no permission; the wider scopes check admin themselves");
        assertEquals(List.of("scope"), registered.get(3).options());
    }

    @Test
    void theServicesExistOnceEnabledAndAreReleasedOnDisable() {
        assertNull(plugin.getSpeechRecognition(), "nothing before onEnable");

        plugin.onEnable();

        assertNotNull(plugin.getSpeechRecognition());
        assertNotNull(plugin.getTextToSpeech());
        assertNotNull(plugin.getMemory());

        plugin.onDisable();

        assertNull(plugin.getSpeechRecognition());
        assertNull(plugin.getTextToSpeech());
        assertNull(plugin.getMemory());
        assertDoesNotThrow(plugin::onDisable, "disabling twice must not throw");
    }

    @Test
    void theSettingsComeFromTheKeysThatActuallyExistInConfigYml() {
        when(configuration.getString("speech_to_text.base_url", "https://api.openai.com/v1"))
                .thenReturn("http://whisper.local:8000/v1");
        when(configuration.getString("speech_to_text.model", "whisper-1")).thenReturn("faster-whisper-small");
        when(configuration.getString("speech_to_text.language", "auto")).thenReturn("fr-FR");
        when(configuration.getString("text_to_speech.base_url", "https://api.openai.com/v1"))
                .thenReturn("http://kokoro.local:8880/v1");
        when(configuration.getString("text_to_speech.model", "gpt-4o-mini-tts")).thenReturn("kokoro");
        when(configuration.getString("text_to_speech.voice", "alloy")).thenReturn("af_heart");
        when(configuration.getInt("text_to_speech.volume", 100)).thenReturn(70);
        when(configuration.getInt("text_to_speech.priority", 70)).thenReturn(90);
        when(configuration.getInt("request.timeout_seconds", 30)).thenReturn(120);
        when(configuration.getInt("request.max_text_length", 1000)).thenReturn(300);
        when(configuration.getInt("transcription.silence_ms", 1200)).thenReturn(800);
        when(configuration.getInt("memory.user_turns", 100)).thenReturn(0);

        plugin.onEnable();
        AiSettings settings = plugin.getSettings();

        assertEquals("http://whisper.local:8000/v1", settings.speechToText().baseUrl());
        assertEquals("faster-whisper-small", settings.speechToText().model());
        assertEquals("fr-FR", settings.transcriptionLanguage());
        assertEquals("http://kokoro.local:8880/v1", settings.textToSpeech().baseUrl());
        assertEquals("kokoro", settings.textToSpeech().model());
        assertEquals("af_heart", settings.voice());
        assertEquals(70, settings.volume());
        assertEquals(90, settings.priority());
        assertEquals(Duration.ofSeconds(120), settings.textToSpeech().timeout());
        assertEquals(300, settings.maxTextLength());
        assertEquals(Duration.ofMillis(800), settings.silence());
        assertEquals(0, settings.userTurns(), "a person's history can be turned off on its own");
        assertFalse(settings.synthesisNeedsKey(), "a local server needs no key");
        assertFalse(settings.transcriptionNeedsKey());
    }

    @Test
    void aMissingKeyOnlyMattersWhenTheEndpointIsAHostedOne() {
        plugin.onEnable(); // defaults: OpenAI, no key

        assertTrue(plugin.getSettings().synthesisNeedsKey());
        assertTrue(plugin.getSettings().transcriptionNeedsKey());

        when(configuration.getString("text_to_speech.api_key", "")).thenReturn("sk-test");
        plugin.onEnable();

        assertFalse(plugin.getSettings().synthesisNeedsKey());
        assertTrue(plugin.getSettings().transcriptionNeedsKey(), "the two endpoints are configured apart");
    }

    @Test
    void unusableNumbersAreCorrectedInsteadOfBreakingTheBoot() {
        when(configuration.getInt("text_to_speech.volume", 100)).thenReturn(400);
        when(configuration.getInt("request.timeout_seconds", 30)).thenReturn(0);
        when(configuration.getInt("transcription.max_segment_seconds", 25)).thenReturn(1);
        when(configuration.getInt("transcription.silence_ms", 1200)).thenReturn(5000);

        assertDoesNotThrow(plugin::onEnable);
        AiSettings settings = plugin.getSettings();

        assertEquals(AudioService.MAX_VOLUME, settings.volume(), "clamped into range");
        assertEquals(Duration.ofSeconds(30), settings.speechToText().timeout(), "back to the default");
        assertTrue(settings.maxSegment().compareTo(settings.silence()) > 0,
                "an utterance must be able to outlast the silence that ends it, or nothing is ever transcribed");
    }

    @Test
    void enablingTwiceDoesNotLeakTheFirstSetOfServices() {
        // What a plugin reload does: onEnable can run again on the same instance.
        plugin.onEnable();
        SpeechRecognitionService first = plugin.getSpeechRecognition();

        plugin.onEnable();

        assertNotSame(first, plugin.getSpeechRecognition());
        assertDoesNotThrow(plugin::onDisable);
    }

    /** What a command declared to the core, as observed by replaying its registration. */
    private record RecordedCommand(String name, String permission, List<String> options) {
    }
}
