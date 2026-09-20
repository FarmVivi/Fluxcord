package fr.farmvivi.fluxcord.plugins.aiaudio;

import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;

/**
 * AI-powered audio plugin for Fluxcord.
 * <p>
 * Features:
 * - Voice recognition and transcription
 * - Text-to-speech with multiple voices
 * - Audio analysis and processing
 * - Integration with AI services (OpenAI, Google Cloud, etc.)
 * - Voice commands and natural language processing
 */
public class AIAudioPlugin extends AbstractPlugin {

    private SpeechRecognitionService speechRecognition;
    private TextToSpeechService textToSpeech;
    private AudioAnalysisService audioAnalysis;

    @Override
    public void onEnable() {
        logger.info("AI Audio Plugin enabling...");

        // Register permissions
        registerPermissions();

        // Voice events from Discord
        addDiscordListeners(new net.dv8tion.jda.api.hooks.ListenerAdapter() {
            @Override
            public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
                onVoiceUpdate(event);
            }
        });

        // Initialize AI services
        initializeServices();

        // Load configuration
        loadConfiguration();

        logger.info("AI Audio Plugin enabled successfully!");
    }

    @Override
    public void onDisable() {
        logger.info("AI Audio Plugin disabling...");

        if (speechRecognition != null) {
            speechRecognition.shutdown();
        }

        if (textToSpeech != null) {
            textToSpeech.shutdown();
        }

        if (audioAnalysis != null) {
            audioAnalysis.shutdown();
        }

        logger.info("AI Audio Plugin disabled!");
    }

    private void registerPermissions() {
        perm("transcribe", "Allows transcription of voice channels", PermissionDefault.TRUE);
        perm("tts", "Allows text-to-speech usage", PermissionDefault.TRUE);
        perm("analyze", "Allows advanced audio analysis", PermissionDefault.OP);
        perm("voicecommands", "Allows voice command features", PermissionDefault.TRUE);
        perm("admin", "Allows administrative AI audio actions", PermissionDefault.OP);
        logger.debug("AI Audio permissions registered: {}", getPluginPermissionManager().getRegisteredPermissions());
    }

    private void perm(String node, String desc, PermissionDefault def) {
        getPluginPermissionManager().registerPermission(new AIPermission(permissionKey(node), desc, def));
    }

    private String permissionKey(String node) {
        return getId() + "." + node;
    }

    private void initializeServices() {
        this.speechRecognition = new SpeechRecognitionService(this);
        this.textToSpeech = new TextToSpeechService(this);
        this.audioAnalysis = new AudioAnalysisService(this);
    }

    private void loadConfiguration() {
        String openAiKey = getConfiguration().getString("ai.openai_api_key", "");
        String googleCreds = getConfiguration().getString("ai.google_credentials_path", "");
        String language = getConfiguration().getString("ai.transcription_language", "en-US");
        String voice = getConfiguration().getString("ai.tts_voice", "en-US-Standard-A");
        boolean voiceCmd = getConfiguration().getBoolean("ai.enable_voice_commands", true);
        double threshold;
        String thresholdRaw = getConfiguration().getString("ai.confidence_threshold", "0.8");
        try {
            threshold = Double.parseDouble(thresholdRaw);
        } catch (NumberFormatException e) {
            threshold = 0.8;
        }
        logger.info("AI Audio config: lang={}, voice={}, voiceCmd={}, threshold={}, openAIKeySet={}, googleCredsSet={}",
                language, voice, voiceCmd, threshold, !openAiKey.isEmpty(), !googleCreds.isEmpty());
    }

    // TODO: Implement commands when command API is available
    /*
    @Command(name = "transcribe", description = "Start transcribing voice channel audio")
    public CommandResult transcribeCommand(CommandContext ctx) {
        // TODO: Implement voice transcription
        ctx.reply("🎤 Transcribe command - Implementation coming soon!");
        return CommandResult.SUCCESS;
    }
    */

    // Discord events reach plugins through JDA listeners (see onEnable), not @EventHandler
    public void onVoiceUpdate(GuildVoiceUpdateEvent event) {
        // TODO: Handle voice channel events for AI processing
        if (speechRecognition != null) {
            speechRecognition.handleVoiceUpdate(event);
        }
    }

    // Getters for services (used by other classes)
    public SpeechRecognitionService getSpeechRecognition() {
        return speechRecognition;
    }

    public TextToSpeechService getTextToSpeech() {
        return textToSpeech;
    }

    public AudioAnalysisService getAudioAnalysis() {
        return audioAnalysis;
    }
}

// Internal permission implementation
class AIPermission implements Permission {
    private final String name;
    private final String description;
    private final PermissionDefault def;

    AIPermission(String name, String description, PermissionDefault def) {
        this.name = name;
        this.description = description;
        this.def = def;
    }

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
        return def;
    }
}