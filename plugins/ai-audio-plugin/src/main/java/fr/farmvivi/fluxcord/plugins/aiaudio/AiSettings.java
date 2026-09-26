package fr.farmvivi.fluxcord.plugins.aiaudio;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.AiEndpoint;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.Persona;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Locale;

/**
 * Everything this plugin reads from {@code config.yml}, in one place.
 *
 * <p>Both capabilities are configured independently — a bot can transcribe with a self-hosted Whisper
 * and speak through OpenAI, or the other way round — which is why each carries its own
 * {@link AiEndpoint} instead of sharing one base URL and key.
 *
 * @param speechToText          where to send audio for transcription
 * @param speechToTextApi       which HTTP shape that endpoint speaks
 * @param transcriptionLanguage BCP 47 tag of the expected speech, or {@code auto} to let the provider
 *                              detect it
 * @param textToSpeech          where to send text for synthesis
 * @param voice                 the provider's voice name used by {@code /speak} when none is given
 * @param volume                playback volume of the bot's own voice (0-100)
 * @param priority              audio priority of the bot's voice; above the pipeline's ducking
 *                              threshold it lowers the music while the bot speaks
 * @param maxTextLength         longest text {@code /speak} accepts, a guard against both Discord's
 *                              limits and a surprise bill
 * @param silence               how long a speaker must be quiet before their sentence is transcribed
 * @param maxSegment            longest utterance transcribed in one request, so someone talking without
 *                              pause is still transcribed
 * @param minSegment            utterances shorter than this are dropped rather than sent — a cough costs
 *                              a request otherwise
 * @param channelTurns          turns remembered per voice channel, 0 to remember none
 * @param serverTurns           turns remembered per server across its channels, 0 to remember none
 * @param userTurns             turns remembered per person across every server, 0 to remember none
 * @param persona               who the bot is, and how much a conversation moves its mood
 * @param chat                  the model that answers, and how it is asked
 */
public record AiSettings(AiEndpoint speechToText, SpeechApi speechToTextApi, String transcriptionLanguage,
                         AiEndpoint textToSpeech, String voice, int volume, int priority,
                         int maxTextLength, Duration silence, Duration maxSegment, Duration minSegment,
                         int channelTurns, int serverTurns, int userTurns, PersonaSettings persona,
                         ChatSettings chat) {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    /** Shipped defaults, matching {@code config.yml}: named so the two cannot drift apart. */
    private static final String DEFAULT_TRANSCRIPTION_MODEL = "whisper-1";
    private static final String DEFAULT_SPEECH_MODEL = "gpt-4o-mini-tts";
    private static final String DEFAULT_VOICE = "alloy";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_TEXT_LENGTH = 1000;
    private static final int DEFAULT_SILENCE_MS = 1200;
    private static final int DEFAULT_MAX_SEGMENT_SECONDS = 25;
    private static final int DEFAULT_MIN_SEGMENT_MS = 400;
    private static final int DEFAULT_CHANNEL_TURNS = 40;
    private static final int DEFAULT_SERVER_TURNS = 200;
    private static final int DEFAULT_USER_TURNS = 100;

    /**
     * Keeps the one invariant the segmenter depends on: an utterance has to be allowed to last longer
     * than the silence that ends it, or nothing would ever be transcribed.
     */
    public AiSettings {
        if (maxSegment.compareTo(silence) <= 0) {
            maxSegment = silence.multipliedBy(10);
        }
    }

    /**
     * Reads the configuration, falling back to the shipped defaults for anything missing or unusable.
     *
     * <p>Every key here must exist in {@code src/main/resources/config.yml}: a lookup whose key is not
     * in the shipped file silently returns its default forever, which is exactly the defect this
     * plugin shipped with until 2026-09-22.
     *
     * @param config the plugin configuration
     * @param logger used to report values that had to be corrected
     * @return the settings to run with
     */
    public static AiSettings from(Configuration config, Logger logger) {
        int timeout = positive(config.getInt("request.timeout_seconds", DEFAULT_TIMEOUT_SECONDS),
                DEFAULT_TIMEOUT_SECONDS, "request.timeout_seconds", logger);

        AiEndpoint stt = new AiEndpoint(
                nonBlank(config.getString("speech_to_text.base_url", DEFAULT_BASE_URL), DEFAULT_BASE_URL),
                config.getString("speech_to_text.api_key", ""),
                nonBlank(config.getString("speech_to_text.model", DEFAULT_TRANSCRIPTION_MODEL),
                        DEFAULT_TRANSCRIPTION_MODEL),
                Duration.ofSeconds(timeout));

        AiEndpoint tts = new AiEndpoint(
                nonBlank(config.getString("text_to_speech.base_url", DEFAULT_BASE_URL), DEFAULT_BASE_URL),
                config.getString("text_to_speech.api_key", ""),
                nonBlank(config.getString("text_to_speech.model", DEFAULT_SPEECH_MODEL),
                        DEFAULT_SPEECH_MODEL),
                Duration.ofSeconds(timeout));

        return new AiSettings(
                stt,
                SpeechApi.of(config.getString("speech_to_text.api", SpeechApi.OPENAI.name()), logger),
                nonBlank(config.getString("speech_to_text.language", "auto"), "auto"),
                tts,
                nonBlank(config.getString("text_to_speech.voice", DEFAULT_VOICE), DEFAULT_VOICE),
                clamp(config.getInt("text_to_speech.volume", AudioService.DEFAULT_VOLUME),
                        AudioService.MIN_VOLUME, AudioService.MAX_VOLUME, "text_to_speech.volume", logger),
                clamp(config.getInt("text_to_speech.priority", AudioService.DEFAULT_PRIORITY_THRESHOLD),
                        AudioService.MIN_PRIORITY, AudioService.MAX_PRIORITY, "text_to_speech.priority", logger),
                positive(config.getInt("request.max_text_length", DEFAULT_MAX_TEXT_LENGTH),
                        DEFAULT_MAX_TEXT_LENGTH, "request.max_text_length", logger),
                Duration.ofMillis(positive(config.getInt("transcription.silence_ms", DEFAULT_SILENCE_MS),
                        DEFAULT_SILENCE_MS, "transcription.silence_ms", logger)),
                Duration.ofSeconds(positive(
                        config.getInt("transcription.max_segment_seconds", DEFAULT_MAX_SEGMENT_SECONDS),
                        DEFAULT_MAX_SEGMENT_SECONDS, "transcription.max_segment_seconds", logger)),
                Duration.ofMillis(Math.max(0,
                        config.getInt("transcription.min_segment_ms", DEFAULT_MIN_SEGMENT_MS))),
                Math.max(0, config.getInt("memory.channel_turns", DEFAULT_CHANNEL_TURNS)),
                Math.max(0, config.getInt("memory.server_turns", DEFAULT_SERVER_TURNS)),
                Math.max(0, config.getInt("memory.user_turns", DEFAULT_USER_TURNS)),
                PersonaSettings.from(config),
                ChatSettings.from(config, timeout));
    }

    /** @return the settings the plugin runs with before {@code onEnable} has read the configuration */
    public static AiSettings defaults() {
        Duration timeout = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);
        return new AiSettings(
                new AiEndpoint(DEFAULT_BASE_URL, "", DEFAULT_TRANSCRIPTION_MODEL, timeout), SpeechApi.OPENAI, "auto",
                new AiEndpoint(DEFAULT_BASE_URL, "", DEFAULT_SPEECH_MODEL, timeout), DEFAULT_VOICE,
                AudioService.DEFAULT_VOLUME, AudioService.DEFAULT_PRIORITY_THRESHOLD,
                DEFAULT_MAX_TEXT_LENGTH,
                Duration.ofMillis(DEFAULT_SILENCE_MS), Duration.ofSeconds(DEFAULT_MAX_SEGMENT_SECONDS),
                Duration.ofMillis(DEFAULT_MIN_SEGMENT_MS),
                DEFAULT_CHANNEL_TURNS, DEFAULT_SERVER_TURNS, DEFAULT_USER_TURNS,
                PersonaSettings.defaults(), ChatSettings.defaults());
    }

    /** @return true when the transcription endpoint is OpenAI's and no key was configured */
    public boolean transcriptionNeedsKey() {
        return speechToTextApi == SpeechApi.OPENAI && needsKey(speechToText);
    }

    /** @return true when the synthesis endpoint is OpenAI's and no key was configured */
    public boolean synthesisNeedsKey() {
        return needsKey(textToSpeech);
    }

    /**
     * A self-hosted server usually needs no key, so a missing key is only a problem when the endpoint
     * is a hosted one. Checking the host rather than always warning keeps a local setup quiet.
     */
    private static boolean needsKey(AiEndpoint endpoint) {
        return !endpoint.hasApiKey() && endpoint.uri("/").getHost() != null
                && endpoint.uri("/").getHost().endsWith("openai.com");
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int clamp(int value, int min, int max, String key, Logger logger) {
        if (value < min || value > max) {
            int clamped = Math.clamp(value, min, max);
            logger.warn("{} is {}, outside {}-{}; using {}", key, value, min, max, clamped);
            return clamped;
        }
        return value;
    }

    private static int positive(int value, int fallback, String key, Logger logger) {
        if (value <= 0) {
            logger.warn("{} is {}, which is not usable; using {}", key, value, fallback);
            return fallback;
        }
        return value;
    }

    /**
     * The model that answers out loud, and how it is asked.
     *
     * @param endpoint       where the chat model lives; OpenAI and Ollama both serve {@code /chat/completions}
     * @param enabled        whether answering aloud is possible at all
     * @param wakeWord       only sentences containing it are answered; empty answers everything
     * @param historyTurns   how many earlier turns of the channel the model is shown
     * @param maxReplyTokens the longest answer to ask for; a spoken reply wants a short one
     * @param temperature    how much the model may wander
     * @param reasoningEffort what to ask of a reasoning model; empty omits the parameter
     */
    public record ChatSettings(AiEndpoint endpoint, boolean enabled, String wakeWord, int historyTurns,
                               int maxReplyTokens, double temperature, String reasoningEffort) {

        private static final String DEFAULT_CHAT_MODEL = "gpt-4o-mini";
        private static final int DEFAULT_HISTORY_TURNS = 8;
        private static final int DEFAULT_MAX_REPLY_TOKENS = 120;
        /** In hundredths, since the configuration reads integers. */
        private static final int DEFAULT_TEMPERATURE = 70;
        /**
         * No reasoning by default. Measured on Ollama: without this a reasoning model spends the whole token
         * budget thinking and answers with empty content.
         */
        private static final String DEFAULT_REASONING_EFFORT = "none";

        public ChatSettings {
            historyTurns = Math.clamp(historyTurns, 0, 50);
            maxReplyTokens = Math.clamp(maxReplyTokens, 16, 2000);
            temperature = Math.clamp(temperature, 0, 2);
            wakeWord = wakeWord == null ? "" : wakeWord.strip();
            reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.strip();
        }

        static ChatSettings from(Configuration config, int timeoutSeconds) {
            AiEndpoint endpoint = new AiEndpoint(
                    nonBlank(config.getString("chat.base_url", DEFAULT_BASE_URL), DEFAULT_BASE_URL),
                    config.getString("chat.api_key", ""),
                    nonBlank(config.getString("chat.model", DEFAULT_CHAT_MODEL), DEFAULT_CHAT_MODEL),
                    Duration.ofSeconds(timeoutSeconds));
            return new ChatSettings(endpoint,
                    config.getBoolean("conversation.enabled", false),
                    config.getString("conversation.wake_word", ""),
                    config.getInt("conversation.history_turns", DEFAULT_HISTORY_TURNS),
                    config.getInt("conversation.max_reply_tokens", DEFAULT_MAX_REPLY_TOKENS),
                    config.getInt("chat.temperature", DEFAULT_TEMPERATURE) / 100.0,
                    config.getString("chat.reasoning_effort", DEFAULT_REASONING_EFFORT));
        }

        /** @return the settings used before the configuration has been read */
        public static ChatSettings defaults() {
            return new ChatSettings(
                    new AiEndpoint(DEFAULT_BASE_URL, "", DEFAULT_CHAT_MODEL, Duration.ofSeconds(30)),
                    false, "", DEFAULT_HISTORY_TURNS, DEFAULT_MAX_REPLY_TOKENS, DEFAULT_TEMPERATURE / 100.0,
                    DEFAULT_REASONING_EFFORT);
        }

        /** @return true when a key is needed for this endpoint and none was given */
        public boolean needsKey() {
            return !endpoint.hasApiKey() && endpoint.uri("/").getHost() != null
                    && endpoint.uri("/").getHost().endsWith("openai.com");
        }

        /**
         * Whether a sentence is addressed to the bot.
         *
         * @param spoken what was said
         * @return true when there is no wake word, or the sentence contains it
         */
        public boolean isAddressedToUs(String spoken) {
            if (wakeWord.isEmpty()) {
                return true;
            }
            return spoken != null
                    && spoken.toLowerCase(Locale.ROOT).contains(wakeWord.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * The persona, and how much a conversation is allowed to move the mood.
     *
     * <p>Grouped rather than flattened into {@link AiSettings}: they are read together, handed to the
     * {@code PersonaStore} together, and the settings record is long enough already.
     *
     * @param persona            who the bot is before any per-server or per-channel override
     * @param moodEnabled        whether the conversation moves the mood at all
     * @param energyPerTurnBasis how much each transcribed sentence raises the energy, in hundredths
     */
    public record PersonaSettings(Persona persona, boolean moodEnabled, int energyPerTurnBasis) {

        /** Default energy added per transcribed sentence, in hundredths: 8 hundredths of the axis. */
        public static final int DEFAULT_ENERGY_PER_TURN = 8;

        public PersonaSettings {
            energyPerTurnBasis = Math.clamp(energyPerTurnBasis, 0, 100);
        }

        static PersonaSettings from(Configuration config) {
            Persona persona = new Persona(
                    config.getString("persona.name", "Fluxcord"),
                    traits(config.getString("persona.traits", "")),
                    config.getString("persona.tone", "neutre"),
                    language(config.getString("persona.language", "fr-FR")),
                    config.getString("persona.instructions", ""));
            return new PersonaSettings(persona,
                    config.getBoolean("persona.mood.enabled", true),
                    config.getInt("persona.mood.energy_per_turn", DEFAULT_ENERGY_PER_TURN));
        }

        /** @return the persona the plugin runs with before the configuration has been read */
        public static PersonaSettings defaults() {
            return new PersonaSettings(
                    new Persona("Fluxcord", java.util.List.of(), "neutre", Locale.FRANCE, ""),
                    true, DEFAULT_ENERGY_PER_TURN);
        }

        /** @return how much one sentence moves the energy axis, as a fraction */
        public double energyPerTurn() {
            return energyPerTurnBasis / 100.0;
        }

        private static java.util.List<String> traits(String configured) {
            if (configured == null || configured.isBlank()) {
                return java.util.List.of();
            }
            return java.util.Arrays.stream(configured.split(",")).map(String::strip)
                    .filter(trait -> !trait.isEmpty()).toList();
        }

        private static Locale language(String tag) {
            Locale locale = tag == null || tag.isBlank() ? Locale.FRANCE : Locale.forLanguageTag(tag);
            return locale.getLanguage().isEmpty() ? Locale.FRANCE : locale;
        }
    }

    /**
     * Which HTTP shape the transcription endpoint speaks.
     *
     * <p>Not a detail that can be guessed from the URL: the two are different routes with different
     * bodies, and a server may expose either.
     */
    public enum SpeechApi {
        /**
         * {@code POST /audio/transcriptions} with a multipart WAV — OpenAI, Speaches, whisper.cpp,
         * faster-whisper-server, LocalAI.
         */
        OPENAI,
        /**
         * {@code POST /api/chat} asking a multimodal model to write down what it hears — Ollama, which
         * has no transcription route but can serve a model that accepts audio. One server then covers
         * transcription, reasoning and tool calls.
         */
        OLLAMA;

        static SpeechApi of(String value, Logger logger) {
            for (SpeechApi api : values()) {
                if (api.name().equalsIgnoreCase(value == null ? "" : value.trim())) {
                    return api;
                }
            }
            logger.warn("Unknown speech_to_text.api '{}'; using {}", value, OPENAI);
            return OPENAI;
        }
    }
}
