package fr.farmvivi.fluxcord.plugins.aiaudio;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.AiEndpoint;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * Everything this plugin reads from {@code config.yml}, in one place.
 *
 * <p>Both capabilities are configured independently — a bot can transcribe with a self-hosted Whisper
 * and speak through OpenAI, or the other way round — which is why each carries its own
 * {@link AiEndpoint} instead of sharing one base URL and key.
 *
 * @param speechToText          where to send audio for transcription
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
 */
public record AiSettings(AiEndpoint speechToText, String transcriptionLanguage,
                         AiEndpoint textToSpeech, String voice, int volume, int priority,
                         int maxTextLength, Duration silence, Duration maxSegment, Duration minSegment,
                         int channelTurns, int serverTurns, int userTurns) {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
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
                nonBlank(config.getString("speech_to_text.model", "whisper-1"), "whisper-1"),
                Duration.ofSeconds(timeout));

        AiEndpoint tts = new AiEndpoint(
                nonBlank(config.getString("text_to_speech.base_url", DEFAULT_BASE_URL), DEFAULT_BASE_URL),
                config.getString("text_to_speech.api_key", ""),
                nonBlank(config.getString("text_to_speech.model", "gpt-4o-mini-tts"), "gpt-4o-mini-tts"),
                Duration.ofSeconds(timeout));

        return new AiSettings(
                stt,
                nonBlank(config.getString("speech_to_text.language", "auto"), "auto"),
                tts,
                nonBlank(config.getString("text_to_speech.voice", "alloy"), "alloy"),
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
                Math.max(0, config.getInt("memory.user_turns", DEFAULT_USER_TURNS)));
    }

    /** @return the settings the plugin runs with before {@code onEnable} has read the configuration */
    public static AiSettings defaults() {
        Duration timeout = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS);
        return new AiSettings(
                new AiEndpoint(DEFAULT_BASE_URL, "", "whisper-1", timeout), "auto",
                new AiEndpoint(DEFAULT_BASE_URL, "", "gpt-4o-mini-tts", timeout), "alloy",
                AudioService.DEFAULT_VOLUME, AudioService.DEFAULT_PRIORITY_THRESHOLD,
                DEFAULT_MAX_TEXT_LENGTH,
                Duration.ofMillis(DEFAULT_SILENCE_MS), Duration.ofSeconds(DEFAULT_MAX_SEGMENT_SECONDS),
                Duration.ofMillis(DEFAULT_MIN_SEGMENT_MS),
                DEFAULT_CHANNEL_TURNS, DEFAULT_SERVER_TURNS, DEFAULT_USER_TURNS);
    }

    /** @return true when the transcription endpoint is OpenAI's and no key was configured */
    public boolean transcriptionNeedsKey() {
        return needsKey(speechToText);
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
}
