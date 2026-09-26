package fr.farmvivi.fluxcord.plugins.aiaudio;

import fr.farmvivi.fluxcord.api.command.CommandBuilder;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.option.OptionChoice;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.AiEndpoint;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.OllamaSpeechToText;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.OpenAiSpeechToText;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.SpeechToText;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.OpenAiTextToSpeech;
import fr.farmvivi.fluxcord.plugins.aiaudio.commands.ForgetCommand;
import fr.farmvivi.fluxcord.plugins.aiaudio.commands.SilenceCommand;
import fr.farmvivi.fluxcord.plugins.aiaudio.commands.SpeakCommand;
import fr.farmvivi.fluxcord.plugins.aiaudio.commands.TranscribeCommand;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationMemory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Voice AI for Fluxcord: the bot speaks in a voice channel, and writes down what it hears.
 *
 * <p>Everything reaches the AI over the OpenAI HTTP API, which is what OpenAI and the self-hosted servers
 * both speak, so where the models run is a matter of configuration rather than of code — the hosted APIs,
 * a machine on the LAN, or a service beside the bot in the cluster. Nothing here is native and nothing is
 * bundled, so the plugin jar stays small and runs the same in a container.
 *
 * <p>What works today: {@code /speak}, {@code /silence}, {@code /transcribe} and {@code /forget}.
 * Answering out loud on its own — the actual goal — is the next step, and this is the groundwork for it:
 * transcription gives the AI its ears, speech its voice, and {@link ConversationMemory} the context of who
 * is talking and what has been said.
 */
public class AIAudioPlugin extends AbstractPlugin {

    /** The permission nodes, named once: they appear at registration and again on each command. */
    public static final String PERM_TRANSCRIBE = "transcribe";
    public static final String PERM_TTS = "tts";
    public static final String PERM_ADMIN = "admin";

    private SpeechRecognitionService speechRecognition;
    private TextToSpeechService textToSpeech;
    private ConversationMemory memory;
    private HttpClient http;

    private AiSettings settings = AiSettings.defaults();

    @Override
    public void onPreEnable() {
        // Permissions have to exist before any command referring to them is registered.
        registerPermission(PERM_TRANSCRIBE, "Allows transcribing a voice channel", PermissionDefault.TRUE);
        registerPermission(PERM_TTS, "Allows making the bot speak", PermissionDefault.TRUE);
        registerPermission(PERM_ADMIN, "Allows clearing what the bot remembers of a server",
                PermissionDefault.OP);
        logger.debug("AI Audio permissions registered: {}", getPermissions().getRegisteredPermissions());
    }

    @Override
    public void onEnable() {
        settings = AiSettings.from(getConfiguration(), logger);
        initializeServices();
        registerCommands();

        if (settings.synthesisNeedsKey() || settings.transcriptionNeedsKey()) {
            logger.warn("No API key configured for api.openai.com; set one, or point "
                    + "speech_to_text.base_url / text_to_speech.base_url at your own server");
        }
        logger.info("AI Audio enabled: transcription {} via {} ({}), speech {} (voice {}), memory {}",
                settings.speechToText().model(), settings.speechToTextApi(), settings.transcriptionLanguage(),
                settings.textToSpeech().model(), settings.voice(),
                memory.isDisabled() ? "off" : settings.channelTurns() + "/" + settings.serverTurns()
                        + "/" + settings.userTurns() + " turns per channel/server/person");
    }

    @Override
    public void onDisable() {
        // Idempotent: a failed enable, a reload and a shutdown all land here.
        if (speechRecognition != null) {
            speechRecognition.shutdown();
            speechRecognition = null;
        }
        if (textToSpeech != null) {
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        if (http != null) {
            http.close();
            http = null;
        }
        memory = null;
        logger.info("AI Audio disabled");
    }

    /**
     * Builds the services, in the order their dependencies require: one HTTP client, the two providers
     * reading their own endpoint, the memory, then the services that use them.
     */
    private void initializeServices() {
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        memory = new ConversationMemory(getStorage(), settings.channelTurns(), settings.serverTurns(),
                settings.userTurns());
        textToSpeech = new TextToSpeechService(this,
                new OpenAiTextToSpeech(settings.textToSpeech(), http));
        speechRecognition = new SpeechRecognitionService(this, speechToText(), memory);
    }

    /**
     * The transcription client the configuration asked for.
     *
     * @return a client for {@code POST /audio/transcriptions}, or one that asks a multimodal model over
     *         Ollama's {@code /api/chat} when that is where the models live
     */
    private SpeechToText speechToText() {
        return switch (settings.speechToTextApi()) {
            case OPENAI -> new OpenAiSpeechToText(settings.speechToText(), http);
            case OLLAMA -> new OllamaSpeechToText(settings.speechToText(), http);
        };
    }

    private void registerCommands() {
        command("speak", builder -> builder
                .permission(permissionKey(PERM_TTS))
                .cooldown(getConfiguration().getInt("request.cooldown_seconds", 0))
                .stringOption("text", text("commands.speak.option.text"), true)
                .stringOption("voice", text("commands.speak.option.voice"), false)
                .executor((ctx, cmd) -> {
                    new SpeakCommand(this).execute(ctx, ctx.getRequiredOption("text"),
                            ctx.getOption("voice", null));
                    return CommandResult.success();
                }));

        command("silence", builder -> builder
                .permission(permissionKey(PERM_TTS))
                .executor((ctx, cmd) -> {
                    new SilenceCommand(this).execute(ctx);
                    return CommandResult.success();
                }));

        command("transcribe", builder -> builder
                .permission(permissionKey(PERM_TRANSCRIBE))
                .stringOption("action", text("commands.transcribe.option.action"), true,
                        choice("commands.transcribe.start", TranscribeCommand.START),
                        choice("commands.transcribe.stop", TranscribeCommand.STOP))
                .executor((ctx, cmd) -> {
                    new TranscribeCommand(this).execute(ctx, ctx.getRequiredOption("action"));
                    return CommandResult.success();
                }));

        // No permission here: erasing one's own history is always allowed. The wider scopes check the
        // admin permission themselves, since only some of the choices need it.
        command("forget", builder -> builder
                .stringOption("scope", text("commands.forget.option.scope"), false,
                        choice("commands.forget.me", ForgetCommand.ME),
                        choice("commands.forget.channel", ForgetCommand.CHANNEL),
                        choice("commands.forget.server", ForgetCommand.SERVER))
                .executor((ctx, cmd) -> {
                    new ForgetCommand(this).execute(ctx, ctx.getOption("scope", ForgetCommand.ME));
                    return CommandResult.success();
                }));
    }

    /** Fills in what every command of this plugin shares: its name, description and category. */
    private void command(String name, Consumer<CommandBuilder> configurer) {
        getCommands().registerCommand(builder -> {
            builder.name(name)
                    .description(text("commands." + name + ".description"))
                    .category("AI Audio");
            configurer.accept(builder);
        });
    }

    /** A named choice whose label is translated and whose value is what the executor receives. */
    private OptionChoice<String> choice(String labelKey, String value) {
        return OptionChoice.of(text(labelKey), value);
    }

    /** A translated string in the bot's default locale, for the texts Discord stores once. */
    private String text(String key) {
        return getLanguage().getString(key);
    }

    private void registerPermission(String node, String description, PermissionDefault defaultValue) {
        getPermissions().registerPermission(new AiPermission(permissionKey(node), description, defaultValue));
    }

    /** @return the fully qualified name of one of this plugin's permission nodes */
    public String permissionKey(String node) {
        return getId() + "." + node;
    }

    /** @return the settings read from {@code config.yml} at enable time */
    public AiSettings getSettings() {
        return settings;
    }

    /** @return the transcription service, or null before {@code onEnable} */
    public SpeechRecognitionService getSpeechRecognition() {
        return speechRecognition;
    }

    /** @return the speech service, or null before {@code onEnable} */
    public TextToSpeechService getTextToSpeech() {
        return textToSpeech;
    }

    /** @return what the bot remembers of the conversations, or null before {@code onEnable} */
    public ConversationMemory getMemory() {
        return memory;
    }

    /** Minimal {@link Permission} carrier; the plugin only needs a name, a description and a default. */
    private record AiPermission(String name, String description, PermissionDefault defaultValue)
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
