package fr.farmvivi.fluxcord.plugins.aiaudio;

import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * AI-powered audio plugin for Fluxcord.
 *
 * <p><strong>This plugin is a skeleton</strong>: the services below do nothing yet (see their
 * TODOs). What is wired here is the plugin lifecycle — permissions, configuration and the voice
 * listener — so that adding a real implementation is the only thing left to do.
 *
 * <p>Planned features: voice transcription, text-to-speech, audio analysis, and voice commands on
 * top of them.
 */
public class AIAudioPlugin extends AbstractPlugin {

    private SpeechRecognitionService speechRecognition;
    private TextToSpeechService textToSpeech;
    private AudioAnalysisService audioAnalysis;

    private AISettings settings = AISettings.defaults();

    @Override
    public void onEnable() {
        logger.info("AI Audio Plugin enabling...");

        registerPermissions();

        // Configuration and services first: the listener registered below can fire immediately.
        settings = loadSettings();
        initializeServices();

        // Discord events reach plugins through JDA listeners, never through @EventHandler.
        addDiscordListeners(new ListenerAdapter() {
            @Override
            public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
                onVoiceUpdate(event);
            }
        });

        logger.info("AI Audio Plugin enabled: language={}, voice={}, voiceCommands={}, threshold={}, "
                        + "openAiKeySet={}, googleCredentialsSet={}",
                settings.transcriptionLanguage(), settings.ttsVoice(), settings.voiceCommandsEnabled(),
                settings.confidenceThreshold(), !settings.openAiKey().isEmpty(),
                !settings.googleCredentialsPath().isEmpty());
    }

    @Override
    public void onDisable() {
        logger.info("AI Audio Plugin disabling...");

        if (speechRecognition != null) {
            speechRecognition.shutdown();
            speechRecognition = null;
        }
        if (textToSpeech != null) {
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        if (audioAnalysis != null) {
            audioAnalysis.shutdown();
            audioAnalysis = null;
        }

        logger.info("AI Audio Plugin disabled!");
    }

    private void registerPermissions() {
        registerPermission("transcribe", "Allows transcription of voice channels", PermissionDefault.TRUE);
        registerPermission("tts", "Allows text-to-speech usage", PermissionDefault.TRUE);
        registerPermission("analyze", "Allows advanced audio analysis", PermissionDefault.OP);
        registerPermission("voicecommands", "Allows voice command features", PermissionDefault.TRUE);
        registerPermission("admin", "Allows administrative AI audio actions", PermissionDefault.OP);
        logger.debug("AI Audio permissions registered: {}", getPermissions().getRegisteredPermissions());
    }

    private void registerPermission(String node, String description, PermissionDefault defaultValue) {
        getPermissions().registerPermission(new AIPermission(permissionKey(node), description, defaultValue));
    }

    /** @return the fully qualified name of one of this plugin's permission nodes */
    public String permissionKey(String node) {
        return getId() + "." + node;
    }

    private void initializeServices() {
        this.speechRecognition = new SpeechRecognitionService(this);
        this.textToSpeech = new TextToSpeechService(this);
        this.audioAnalysis = new AudioAnalysisService(this);
    }

    /**
     * Reads {@code config.yml}. The keys must match the shipped file: the previous version read
     * {@code ai.transcription_language}, {@code ai.tts_voice}, {@code ai.enable_voice_commands} and
     * {@code ai.confidence_threshold}, none of which exist there, so every one of those settings
     * silently fell back to its default and the documented configuration did nothing.
     */
    AISettings loadSettings() {
        return new AISettings(
                getConfiguration().getString("ai.openai_api_key", ""),
                getConfiguration().getString("ai.google_credentials_path", ""),
                getConfiguration().getString("speech_recognition.language", "en-US"),
                confidenceThreshold(),
                getConfiguration().getString("text_to_speech.default_voice", "en-US-Standard-A"),
                getConfiguration().getBoolean("voice_commands.enabled", true),
                getConfiguration().getString("voice_commands.wake_word", "hey bot"));
    }

    /** {@code Configuration} has no {@code getDouble}, so the ratio is read as text and parsed. */
    private double confidenceThreshold() {
        String raw = getConfiguration().getString("speech_recognition.confidence_threshold", "0.8");
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            logger.warn("Invalid speech_recognition.confidence_threshold '{}', falling back to 0.8", raw);
            return 0.8;
        }
    }

    /**
     * Called by the JDA listener registered in {@link #onEnable()}.
     *
     * <p>TODO: this is where a voice channel is turned into audio for the AI services. Commands
     * (transcribe, say, analyse) are registered with {@code getCommands().registerCommand(builder ->
     * ...)}, like every other plugin — see {@code MusicPlugin} or the command example.
     */
    void onVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (speechRecognition != null) {
            speechRecognition.handleVoiceUpdate(event);
        }
    }

    /** @return the settings read from {@code config.yml} at enable time */
    public AISettings getSettings() {
        return settings;
    }

    public SpeechRecognitionService getSpeechRecognition() {
        return speechRecognition;
    }

    public TextToSpeechService getTextToSpeech() {
        return textToSpeech;
    }

    public AudioAnalysisService getAudioAnalysis() {
        return audioAnalysis;
    }

    /**
     * Everything this plugin reads from {@code config.yml}, in one place.
     *
     * @param openAiKey             OpenAI API key, empty when not configured
     * @param googleCredentialsPath path to the Google Cloud credentials, empty when not configured
     * @param transcriptionLanguage language tag used for speech recognition
     * @param confidenceThreshold   minimum confidence for accepting a transcription (0.0-1.0)
     * @param ttsVoice              default voice used for text-to-speech
     * @param voiceCommandsEnabled  whether spoken commands are listened for
     * @param wakeWord              the phrase that activates voice commands
     */
    public record AISettings(String openAiKey, String googleCredentialsPath, String transcriptionLanguage,
                             double confidenceThreshold, String ttsVoice, boolean voiceCommandsEnabled,
                             String wakeWord) {

        static AISettings defaults() {
            return new AISettings("", "", "en-US", 0.8, "en-US-Standard-A", true, "hey bot");
        }
    }

    /** Minimal {@link Permission} carrier; the plugin only needs a name, a description and a default. */
    private record AIPermission(String name, String description, PermissionDefault defaultValue)
            implements Permission {

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public PermissionDefault getDefault() {
            return defaultValue;
        }
    }
}
