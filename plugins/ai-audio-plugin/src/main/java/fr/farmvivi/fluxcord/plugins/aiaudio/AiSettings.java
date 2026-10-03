package fr.farmvivi.fluxcord.plugins.aiaudio;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.AiEndpoint;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatAudio;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.OpenAiChatModel;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.Persona;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Everything this plugin reads from {@code config.yml}, in one place.
 *
 * <p>Both capabilities are configured independently — a bot can transcribe with a self-hosted Whisper
 * and speak through OpenAI, or the other way round — which is why each carries its own
 * {@link AiEndpoint} instead of sharing one base URL and key.
 *
 * @param transcription what the bot hears with, and how an utterance is cut out of a stream
 * @param speech        what the bot speaks with
 * @param memory        how much of a conversation is kept, at each scale
 * @param persona       who the bot is, and how much a conversation moves its mood
 * @param chat          the model that answers, and how it is asked
 * @param webSearch     whether the model may look something up, and where
 * @param realtime      whether a turn is a request or a full-duplex session
 */
public record AiSettings(TranscriptionSettings transcription, SpeechSettings speech, MemorySettings memory,
                         PersonaSettings persona, ChatSettings chat, WebSearchSettings webSearch,
                         RealtimeSettings realtime) {

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
     * What the bot hears with, and how a stream of audio is cut into utterances.
     *
     * @param endpoint   where to send audio for transcription
     * @param api        which HTTP shape that endpoint speaks
     * @param language   BCP 47 tag of the expected speech, or {@code auto} to let the provider detect it
     * @param silence    how long a speaker must be quiet before their sentence is transcribed
     * @param maxSegment longest utterance transcribed in one request, so someone talking without pause is
     *                   still transcribed
     * @param minSegment utterances shorter than this are dropped rather than sent - a cough costs a request
     *                   otherwise
     * @param vocabulary words the provider is told to expect, for names no speech model can guess
     */
    public record TranscriptionSettings(AiEndpoint endpoint, SpeechApi api, String language,
                                        Duration silence, Duration maxSegment, Duration minSegment,
                                        List<String> vocabulary) {

        /**
         * Keeps the one invariant the segmenter depends on: an utterance has to be allowed to last longer
         * than the silence that ends it, or nothing would ever be transcribed.
         */
        public TranscriptionSettings {
            if (maxSegment.compareTo(silence) <= 0) {
                maxSegment = silence.multipliedBy(10);
            }
            vocabulary = vocabulary == null ? List.of() : List.copyOf(vocabulary);
        }

        /** Transcription with nothing to spell out, which is what every caller wanted before there was. */
        public TranscriptionSettings(AiEndpoint endpoint, SpeechApi api, String language,
                                     Duration silence, Duration maxSegment, Duration minSegment) {
            this(endpoint, api, language, silence, maxSegment, minSegment, List.of());
        }
    }

    /**
     * What the bot speaks with.
     *
     * @param endpoint      where to send text for synthesis
     * @param voice         the provider's voice name used by {@code /speak} when none is given
     * @param volume        playback volume of the bot's own voice (0-100)
     * @param priority      audio priority of the bot's voice; above the pipeline's ducking threshold it
     *                      lowers the music while the bot speaks
     * @param maxTextLength longest text {@code /speak} accepts, a guard against both Discord's limits and a
     *                      surprise bill
     */
    public record SpeechSettings(AiEndpoint endpoint, String voice, int volume, int priority,
                                 int maxTextLength) {
    }

    /**
     * How much of a conversation is kept, at each of the three scales the memory reads.
     *
     * @param channelTurns turns remembered per voice channel, 0 to remember none
     * @param serverTurns  turns remembered per server across its channels, 0 to remember none
     * @param userTurns    turns remembered per person across every server, 0 to remember none
     */
    public record MemorySettings(int channelTurns, int serverTurns, int userTurns) {
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
                new TranscriptionSettings(stt,
                        SpeechApi.of(config.getString("speech_to_text.api", SpeechApi.OPENAI.name()), logger),
                        nonBlank(config.getString("speech_to_text.language", "auto"), "auto"),
                        Duration.ofMillis(positive(
                                config.getInt("transcription.silence_ms", DEFAULT_SILENCE_MS),
                                DEFAULT_SILENCE_MS, "transcription.silence_ms", logger)),
                        Duration.ofSeconds(positive(
                                config.getInt("transcription.max_segment_seconds", DEFAULT_MAX_SEGMENT_SECONDS),
                                DEFAULT_MAX_SEGMENT_SECONDS, "transcription.max_segment_seconds", logger)),
                        Duration.ofMillis(Math.max(0,
                                config.getInt("transcription.min_segment_ms", DEFAULT_MIN_SEGMENT_MS))),
                        config.getStringList("speech_to_text.vocabulary", List.of())),
                new SpeechSettings(tts,
                        nonBlank(config.getString("text_to_speech.voice", DEFAULT_VOICE), DEFAULT_VOICE),
                        clamp(config.getInt("text_to_speech.volume", AudioService.DEFAULT_VOLUME),
                                AudioService.MIN_VOLUME, AudioService.MAX_VOLUME, "text_to_speech.volume",
                                logger),
                        clamp(config.getInt("text_to_speech.priority",
                                        AudioService.DEFAULT_PRIORITY_THRESHOLD),
                                AudioService.MIN_PRIORITY, AudioService.MAX_PRIORITY,
                                "text_to_speech.priority", logger),
                        positive(config.getInt("request.max_text_length", DEFAULT_MAX_TEXT_LENGTH),
                                DEFAULT_MAX_TEXT_LENGTH, "request.max_text_length", logger)),
                new MemorySettings(
                        Math.max(0, config.getInt("memory.channel_turns", DEFAULT_CHANNEL_TURNS)),
                        Math.max(0, config.getInt("memory.server_turns", DEFAULT_SERVER_TURNS)),
                        Math.max(0, config.getInt("memory.user_turns", DEFAULT_USER_TURNS))),
                PersonaSettings.from(config),
                ChatSettings.from(config, timeout),
                WebSearchSettings.from(config, timeout),
                RealtimeSettings.from(config, logger));
    }

    /** @return the settings the plugin runs with before {@code onEnable} has read the configuration */
    public static AiSettings defaults() {
        Duration timeout = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);
        return new AiSettings(
                new TranscriptionSettings(
                        new AiEndpoint(DEFAULT_BASE_URL, "", DEFAULT_TRANSCRIPTION_MODEL, timeout),
                        SpeechApi.OPENAI, "auto",
                        Duration.ofMillis(DEFAULT_SILENCE_MS),
                        Duration.ofSeconds(DEFAULT_MAX_SEGMENT_SECONDS),
                        Duration.ofMillis(DEFAULT_MIN_SEGMENT_MS)),
                new SpeechSettings(
                        new AiEndpoint(DEFAULT_BASE_URL, "", DEFAULT_SPEECH_MODEL, timeout), DEFAULT_VOICE,
                        AudioService.DEFAULT_VOLUME, AudioService.DEFAULT_PRIORITY_THRESHOLD,
                        DEFAULT_MAX_TEXT_LENGTH),
                new MemorySettings(DEFAULT_CHANNEL_TURNS, DEFAULT_SERVER_TURNS, DEFAULT_USER_TURNS),
                PersonaSettings.defaults(), ChatSettings.defaults(),
                WebSearchSettings.disabled(), RealtimeSettings.disabled());
    }

    /**
     * The same settings with one group replaced.
     *
     * <p>These exist for the tests, and they earn their place: a test that needs a different chat model should
     * say only that, not restate seventeen unrelated values. Every time this record gained a component, every
     * positional construction in the test tree broke - which is how the grouping came to be done at all.
     *
     * @param transcription what to hear with instead
     * @return a copy
     */
    public AiSettings withTranscription(TranscriptionSettings transcription) {
        return new AiSettings(transcription, speech, memory, persona, chat, webSearch, realtime);
    }

    /**
     * @param speech what to speak with instead
     * @return a copy
     */
    public AiSettings withSpeech(SpeechSettings speech) {
        return new AiSettings(transcription, speech, memory, persona, chat, webSearch, realtime);
    }

    /**
     * @param memory how much to remember instead
     * @return a copy
     */
    public AiSettings withMemory(MemorySettings memory) {
        return new AiSettings(transcription, speech, memory, persona, chat, webSearch, realtime);
    }

    /**
     * @param persona who to be instead
     * @return a copy
     */
    public AiSettings withPersona(PersonaSettings persona) {
        return new AiSettings(transcription, speech, memory, persona, chat, webSearch, realtime);
    }

    /**
     * @param chat which model answers, and how it is asked
     * @return a copy
     */
    public AiSettings withChat(ChatSettings chat) {
        return new AiSettings(transcription, speech, memory, persona, chat, webSearch, realtime);
    }

    /**
     * @param webSearch whether the model may look something up, and where
     * @return a copy
     */
    public AiSettings withWebSearch(WebSearchSettings webSearch) {
        return new AiSettings(transcription, speech, memory, persona, chat, webSearch, realtime);
    }

    /**
     * @param realtime whether a turn is a request or a full-duplex session
     * @return a copy
     */
    public AiSettings withRealtime(RealtimeSettings realtime) {
        return new AiSettings(transcription, speech, memory, persona, chat, webSearch, realtime);
    }

    /** @return true when the transcription endpoint is OpenAI's and no key was configured */
    public boolean transcriptionNeedsKey() {
        return transcription.api() == SpeechApi.OPENAI && needsKey(transcription.endpoint());
    }

    /** @return true when the synthesis endpoint is OpenAI's and no key was configured */
    public boolean synthesisNeedsKey() {
        return needsKey(speech.endpoint());
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
     * @param toolReasoningEffort what to ask on a round that offers tools; {@code none} there means no tool
     *                       call ever comes back, so it is not the same value as above
     * @param memoryTools    whether the model may look the memory up itself instead of only being handed the
     *                       last turns; needs a model that supports tool calls
     * @param maxToolRounds  how many times in a row the model may ask for something before it has to answer,
     *                       a guard against a loop that never ends
     * @param audio          whether the model hears the voice and answers with one, which needs a model that
     *                       declares an audio modality and replaces transcription and synthesis in the turn
     * @param maxToolTokens  the budget for a round that offers tools, which has to be far larger than the
     *                       one above: a reasoning model thinks before it calls anything, and that thinking
     *                       comes out of the same budget
     * @param commandTools   whether the model may run the bot's own commands on behalf of whoever asked
     */
    public record ChatSettings(AiEndpoint endpoint, boolean enabled, String wakeWord, int historyTurns,
                               int maxReplyTokens, double temperature, String reasoningEffort,
                               String toolReasoningEffort, boolean memoryTools, int maxToolRounds,
                               ChatAudio audio, int maxToolTokens, boolean commandTools) {

        /** Chat settings from before commands could be run, which is off. */
        public ChatSettings(AiEndpoint endpoint, boolean enabled, String wakeWord, int historyTurns,
                            int maxReplyTokens, double temperature, String reasoningEffort,
                            String toolReasoningEffort, boolean memoryTools, int maxToolRounds,
                            ChatAudio audio, int maxToolTokens) {
            this(endpoint, enabled, wakeWord, historyTurns, maxReplyTokens, temperature, reasoningEffort,
                    toolReasoningEffort, memoryTools, maxToolRounds, audio, maxToolTokens, false);
        }

        private static final String DEFAULT_CHAT_MODEL = "gpt-4o-mini";
        private static final int DEFAULT_HISTORY_TURNS = 8;
        private static final int DEFAULT_MAX_REPLY_TOKENS = 220;
        private static final int DEFAULT_MAX_TOOL_ROUNDS = 3;
        /**
         * Measured, and the reason this is a separate setting: with the 120 of
         * {@code max_reply_tokens}, Gemma 4 E4B spent <em>exactly</em> 120 completion tokens thinking on a
         * round that offered tools and came back with no call and empty content. At 200, exactly 200. Only at
         * 600 did a call appear. A tool round therefore cannot share the budget that keeps a spoken answer
         * short.
         */
        private static final int DEFAULT_MAX_TOOL_TOKENS = 600;
        /** In hundredths, since the configuration reads integers. */
        private static final int DEFAULT_TEMPERATURE = 70;
        /**
         * No reasoning by default. Measured on Ollama: without this a reasoning model spends the whole token
         * budget thinking and answers with empty content.
         */
        private static final String DEFAULT_REASONING_EFFORT = "none";
        /** OpenAI's default audio voice, and the name the other providers copied. */
        private static final String DEFAULT_AUDIO_VOICE = "alloy";

        public ChatSettings {
            historyTurns = Math.clamp(historyTurns, 0, 50);
            maxReplyTokens = Math.clamp(maxReplyTokens, 16, 2000);
            temperature = Math.clamp(temperature, 0, 2);
            wakeWord = wakeWord == null ? "" : wakeWord.strip();
            reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.strip();
            toolReasoningEffort = toolReasoningEffort == null ? "" : toolReasoningEffort.strip();
            maxToolRounds = Math.clamp(maxToolRounds, 0, 10);
            audio = audio == null ? ChatAudio.off() : audio;
            maxToolTokens = Math.clamp(maxToolTokens, 16, 4000);
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
                    config.getString("chat.reasoning_effort", DEFAULT_REASONING_EFFORT),
                    config.getString("chat.tool_reasoning_effort",
                            OpenAiChatModel.DEFAULT_TOOL_REASONING_EFFORT),
                    config.getBoolean("conversation.memory_tools", false),
                    config.getInt("conversation.max_tool_rounds", DEFAULT_MAX_TOOL_ROUNDS),
                    new ChatAudio(
                            config.getBoolean("conversation.audio.hear", false),
                            config.getBoolean("conversation.audio.speak", false),
                            nonBlank(config.getString("conversation.audio.voice", DEFAULT_AUDIO_VOICE),
                                    DEFAULT_AUDIO_VOICE),
                            config.getString("conversation.audio.format", ChatAudio.WAV)),
                    config.getInt("conversation.max_tool_tokens", DEFAULT_MAX_TOOL_TOKENS),
                    config.getBoolean("conversation.command_tools", false));
        }

        /** @return the settings used before the configuration has been read */
        public static ChatSettings defaults() {
            return new ChatSettings(
                    new AiEndpoint(DEFAULT_BASE_URL, "", DEFAULT_CHAT_MODEL, Duration.ofSeconds(30)),
                    false, "", DEFAULT_HISTORY_TURNS, DEFAULT_MAX_REPLY_TOKENS, DEFAULT_TEMPERATURE / 100.0,
                    DEFAULT_REASONING_EFFORT, OpenAiChatModel.DEFAULT_TOOL_REASONING_EFFORT, false,
                    DEFAULT_MAX_TOOL_ROUNDS, ChatAudio.off(), DEFAULT_MAX_TOOL_TOKENS);
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
    public record PersonaSettings(Persona persona, boolean moodEnabled, int energyPerTurnBasis,
                                  boolean moodFromModel, int moodWeightBasis) {

        /** Default energy added per transcribed sentence, in hundredths: 8 hundredths of the axis. */
        public static final int DEFAULT_ENERGY_PER_TURN = 8;
        /**
         * How far one reading moves the mood towards it, in hundredths. Half way: a reading that agrees with
         * the last one gets there quickly, a single outlier does not swing the bot.
         */
        public static final int DEFAULT_MOOD_WEIGHT = 50;

        public PersonaSettings {
            energyPerTurnBasis = Math.clamp(energyPerTurnBasis, 0, 100);
            moodWeightBasis = Math.clamp(moodWeightBasis, 0, 100);
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
                    config.getInt("persona.mood.energy_per_turn", DEFAULT_ENERGY_PER_TURN),
                    config.getBoolean("persona.mood.from_model", true),
                    config.getInt("persona.mood.weight", DEFAULT_MOOD_WEIGHT));
        }

        /** @return the persona the plugin runs with before the configuration has been read */
        public static PersonaSettings defaults() {
            return new PersonaSettings(
                    new Persona("Fluxcord", List.of(), "neutre", Locale.FRANCE, ""),
                    true, DEFAULT_ENERGY_PER_TURN, true, DEFAULT_MOOD_WEIGHT);
        }

        /** @return how far one reading of the room moves the mood towards it, as a fraction */
        public double moodWeight() {
            return moodWeightBasis / 100.0;
        }

        /** @return how much one sentence moves the energy axis, as a fraction */
        public double energyPerTurn() {
            return energyPerTurnBasis / 100.0;
        }

        private static List<String> traits(String configured) {
            if (configured == null || configured.isBlank()) {
                return List.of();
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

    /**
     * Which full-duplex service the realtime path talks to.
     *
     * <p>Not derivable from the URL: a proxy in front of either one would say nothing about the protocol
     * behind it, and the two protocols share no field names at all.
     */
    public enum RealtimeApi {
        /** OpenAI's Realtime API: every frame carries a {@code type}, the model is named in the URL. */
        OPENAI,
        /**
         * Google's Live API ({@code BidiGenerateContent}): the model is named in the first frame, the key
         * goes in the URL, and what happened is told by which top-level key a frame has. Roughly an order
         * of magnitude cheaper per minute of conversation.
         */
        GEMINI;

        static RealtimeApi of(String value, Logger logger) {
            for (RealtimeApi api : values()) {
                if (api.name().equalsIgnoreCase(value == null ? "" : value.trim())) {
                    return api;
                }
            }
            logger.warn("Unknown conversation.realtime.api '{}'; using {}", value, OPENAI);
            return OPENAI;
        }
    }

    /** Which search API a {@code WebSearch} implementation speaks. */
    public enum SearchApi {
        /** A self-hosted SearxNG instance, queried over its JSON API. */
        SEARXNG
    }

    /**
     * Letting the model look something up on the web.
     *
     * <p><strong>There is deliberately no default instance.</strong> A default would mean that enabling the
     * feature silently sends what people say in a voice channel to somebody else's server; the operator has to
     * name the instance, which is also the moment they decide where the queries go.
     *
     * @param enabled    whether the model is offered the search tool at all
     * @param api        which backend's API the instance speaks
     * @param baseUrl    the instance's root; empty means nothing was configured, which disables the feature
     * @param apiKey     a bearer token, for an instance behind an authenticating proxy
     * @param timeout    how long to wait for one query
     * @param maxResults how many results to return when the model does not ask for a number
     * @param language   the language code to search in, or empty to let the instance decide
     * @param safeSearch 0 off, 1 moderate, 2 strict
     */
    public record WebSearchSettings(boolean enabled, SearchApi api, String baseUrl, String apiKey,
                                    Duration timeout, int maxResults, String language, int safeSearch) {

        private static final int DEFAULT_MAX_RESULTS = 5;
        private static final int DEFAULT_SAFE_SEARCH = 1;

        public WebSearchSettings {
            api = api == null ? SearchApi.SEARXNG : api;
            baseUrl = baseUrl == null ? "" : baseUrl.strip();
            apiKey = apiKey == null ? "" : apiKey.strip();
            maxResults = Math.clamp(maxResults, 1, 10);
            language = language == null ? "" : language.strip();
            safeSearch = Math.clamp(safeSearch, 0, 2);
        }

        /** @return the settings used when nothing was configured */
        public static WebSearchSettings disabled() {
            return new WebSearchSettings(false, SearchApi.SEARXNG, "", "",
                    Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS), DEFAULT_MAX_RESULTS, "",
                    DEFAULT_SAFE_SEARCH);
        }

        static WebSearchSettings from(Configuration config, int timeoutSeconds) {
            return new WebSearchSettings(
                    config.getBoolean("web_search.enabled", false),
                    searchApi(config.getString("web_search.api", SearchApi.SEARXNG.name())),
                    config.getString("web_search.base_url", ""),
                    config.getString("web_search.api_key", ""),
                    Duration.ofSeconds(timeoutSeconds),
                    config.getInt("web_search.max_results", DEFAULT_MAX_RESULTS),
                    config.getString("web_search.language", ""),
                    config.getInt("web_search.safe_search", DEFAULT_SAFE_SEARCH));
        }

        private static SearchApi searchApi(String configured) {
            try {
                return SearchApi.valueOf(configured.strip().toUpperCase(Locale.ROOT));
            } catch (RuntimeException e) {
                return SearchApi.SEARXNG;
            }
        }

        /**
         * @return true when the model should actually be offered the tool: switched on, and pointed somewhere
         */
        public boolean isUsable() {
            return enabled && !baseUrl.isEmpty();
        }
    }

    /**
     * The full-duplex path: a WebSocket session instead of a turn at a time.
     *
     * <p>Off by default, and it is the one capability in this plugin that <strong>nothing has verified against
     * the real service</strong> — the frames are built from the published event names, covered by tests on this
     * side only. It is also hosted-only today: no self-hosted server speaks this protocol.
     *
     * @param enabled whether {@code /converse} opens a realtime session instead of answering turn by turn
     * @param url     the service's WebSocket URL, model included
     * @param apiKey  the bearer token
     * @param voice   the provider's voice name for the spoken answer
     */
    public record RealtimeSettings(boolean enabled, RealtimeApi api, String url, String apiKey, String voice,
                                   String model) {

        private static final String DEFAULT_URL = "wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1";
        private static final String DEFAULT_VOICE = "marin";
        /** Google's cheapest live model, which is the reason that dialect exists at all. */
        private static final String DEFAULT_GEMINI_MODEL = "gemini-live-2.5-flash-preview";
        private static final String DEFAULT_GEMINI_VOICE = "Puck";

        public RealtimeSettings {
            api = api == null ? RealtimeApi.OPENAI : api;
            url = url == null ? "" : url.strip();
            apiKey = apiKey == null ? "" : apiKey.strip();
            voice = voice == null ? "" : voice.strip();
            model = model == null ? "" : model.strip();
        }

        /** Realtime settings from before there was a choice of service, which means OpenAI. */
        public RealtimeSettings(boolean enabled, String url, String apiKey, String voice) {
            this(enabled, RealtimeApi.OPENAI, url, apiKey, voice, "");
        }

        /** @return the settings used when nothing was configured */
        public static RealtimeSettings disabled() {
            return new RealtimeSettings(false, RealtimeApi.OPENAI, DEFAULT_URL, "", DEFAULT_VOICE, "");
        }

        static RealtimeSettings from(Configuration config, Logger logger) {
            RealtimeApi api = RealtimeApi.of(
                    config.getString("conversation.realtime.api", RealtimeApi.OPENAI.name()), logger);
            // Each service has its own endpoint, voice names and model naming, so the defaults follow the
            // chosen one - a Gemini URL with an OpenAI voice would fail in a way nobody could read.
            String defaultUrl = api == RealtimeApi.GEMINI
                    ? fr.farmvivi.fluxcord.plugins.aiaudio.realtime.GeminiRealtime.DEFAULT_URL
                    : DEFAULT_URL;
            String defaultVoice = api == RealtimeApi.GEMINI ? DEFAULT_GEMINI_VOICE : DEFAULT_VOICE;
            return new RealtimeSettings(
                    config.getBoolean("conversation.realtime.enabled", false),
                    api,
                    nonBlank(config.getString("conversation.realtime.url", defaultUrl), defaultUrl),
                    config.getString("conversation.realtime.api_key", ""),
                    nonBlank(config.getString("conversation.realtime.voice", defaultVoice), defaultVoice),
                    nonBlank(config.getString("conversation.realtime.model", DEFAULT_GEMINI_MODEL),
                            DEFAULT_GEMINI_MODEL));
        }

        /**
         * @return true when the realtime path should actually be used: switched on, pointed somewhere, and
         *         holding the key such a service invariably needs
         */
        public boolean isUsable() {
            return enabled && !url.isEmpty() && !apiKey.isEmpty();
        }
    }
}
