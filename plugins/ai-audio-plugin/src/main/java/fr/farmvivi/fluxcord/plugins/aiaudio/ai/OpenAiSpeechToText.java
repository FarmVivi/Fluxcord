package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Speech-to-text over {@code POST /audio/transcriptions}, the OpenAI Whisper endpoint.
 *
 * <p>Works unchanged against OpenAI and against a self-hosted server implementing the same route
 * (Speaches, whisper.cpp's {@code whisper-server}, faster-whisper-server, LocalAI). The audio is sent
 * as a WAV file in a multipart body, because that is what these endpoints expect — they read the format
 * from the file, not from a parameter.
 */
public class OpenAiSpeechToText implements SpeechToText {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiSpeechToText.class);
    private static final String BOUNDARY = "fluxcord-ai-audio-boundary";

    private final AiEndpoint endpoint;
    private final HttpClient http;

    public OpenAiSpeechToText(AiEndpoint endpoint, HttpClient http) {
        this.endpoint = endpoint;
        this.http = http;
    }

    @Override
    public String transcribe(PcmAudio audio, String language, List<String> vocabulary) {
        if (audio == null || audio.isEmpty()) {
            throw new IllegalArgumentException("audio is required");
        }
        byte[] body = multipartBody(audio.toWav(), language, vocabulary);

        HttpRequest request = AiHttp.request(endpoint, "/audio/transcriptions")
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        LOG.debug("Transcribing {} of audio ({}) on {}", audio.duration(), language, endpoint);
        byte[] response = AiHttp.send(http, request, "Transcription");
        return readText(response);
    }

    /**
     * Builds the {@code multipart/form-data} body by hand: the JDK client has no multipart publisher,
     * and a WAV plus three short fields does not justify a dependency.
     */
    private byte[] multipartBody(byte[] wav, String language, List<String> vocabulary) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(wav.length + 512);
        writeField(out, "model", endpoint.model());
        writeField(out, "response_format", "json");
        String code = languageCode(language);
        if (!code.isEmpty()) {
            writeField(out, "language", code);
        }
        // Whisper's documented use for "prompt": words it should be ready to hear. A plain comma-separated
        // list works, and is cheaper than a sentence - the prompt is charged as context on every request.
        String hint = hint(vocabulary);
        if (!hint.isEmpty()) {
            writeField(out, "prompt", hint);
        }
        write(out, "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n");
        out.writeBytes(wav);
        write(out, "\r\n--" + BOUNDARY + "--\r\n");
        return out.toByteArray();
    }

    /**
     * @return the vocabulary as the provider wants it, or an empty string when there is nothing to say
     */
    static String hint(List<String> vocabulary) {
        if (vocabulary == null || vocabulary.isEmpty()) {
            return "";
        }
        return vocabulary.stream()
                .filter(word -> word != null && !word.isBlank())
                .map(String::trim)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }

    private void writeField(ByteArrayOutputStream out, String name, String value) {
        write(out, "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n");
    }

    private void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * These endpoints want an ISO-639-1 code, so a configured BCP 47 tag such as {@code fr-FR} has to
     * lose its region. Sending the full tag makes OpenAI reject the request outright.
     *
     * @return the bare language code, or an empty string to let the provider detect the language
     */
    static String languageCode(String language) {
        if (language == null || language.isBlank() || "auto".equalsIgnoreCase(language)) {
            return "";
        }
        String tag = language.trim();
        int separator = Math.max(tag.indexOf('-'), tag.indexOf('_'));
        return (separator > 0 ? tag.substring(0, separator) : tag).toLowerCase(java.util.Locale.ROOT);
    }

    /** The answer is {@code {"text": "..."}}; a server answering plain text is tolerated. */
    private String readText(byte[] response) {
        String raw = new String(response, StandardCharsets.UTF_8).trim();
        try {
            var json = JsonParser.parseString(raw).getAsJsonObject();
            if (json.has("text")) {
                return json.get("text").getAsString().trim();
            }
            throw new AiRequestException("Transcription answered JSON without a 'text' field");
        } catch (JsonParseException | IllegalStateException e) {
            return raw;
        }
    }
}
