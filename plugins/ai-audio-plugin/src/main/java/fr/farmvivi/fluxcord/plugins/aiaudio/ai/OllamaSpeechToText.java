package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Transcription by asking a multimodal model directly, over Ollama's {@code /api/chat}.
 *
 * <p>Why this exists next to {@link OpenAiSpeechToText}: Ollama has no {@code /audio/transcriptions}
 * route, but a model that accepts audio can simply be asked to write down what it hears. On a Gemma 4
 * E4B this answered a French sentence correctly in well under a second, which removes a whole service
 * from the deployment — the same server then does transcription, reasoning and tool calls in one round
 * trip instead of a speech service feeding a language model.
 *
 * <p>Two details cost time to find, so they are pinned by {@link #transcribe}:
 * <ul>
 *   <li>the audio goes in the <strong>{@code images}</strong> field. Ollama passes every kind of media
 *       through it; an {@code audio} field is accepted by the API and silently ignored, and the model
 *       then answers that it was given no audio.
 *   <li>{@code think} is off. A thinking block is pure latency here, and with a small token budget it
 *       consumes the whole answer.
 * </ul>
 *
 * <p>The model must actually accept audio: {@code /api/show} lists {@code audio} among its capabilities
 * (Gemma 4 does, Qwen 3.5 does not).
 */
public class OllamaSpeechToText implements SpeechToText {

    /** The field carrying the text, both in the request and in the answer. */
    private static final String CONTENT = "content";

    private static final Logger LOG = LoggerFactory.getLogger(OllamaSpeechToText.class);

    /**
     * Kept deliberately blunt: any invitation to be helpful makes a chat model comment on the audio
     * instead of transcribing it.
     */
    private static final String INSTRUCTION =
            "Transcribe exactly what is said in this audio. Answer with the transcription only, with no "
                    + "preamble, no quotes and no commentary. If nothing intelligible is said, answer with "
                    + "an empty line.";

    private final AiEndpoint endpoint;
    private final HttpClient http;

    public OllamaSpeechToText(AiEndpoint endpoint, HttpClient http) {
        this.endpoint = endpoint;
        this.http = http;
    }

    @Override
    public String transcribe(PcmAudio audio, String language) {
        if (audio == null || audio.isEmpty()) {
            throw new IllegalArgumentException("audio is required");
        }
        JsonArray media = new JsonArray();
        media.add(Base64.getEncoder().encodeToString(audio.toWav()));

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty(CONTENT, instructionFor(language));
        // Not "audio": Ollama carries every medium in "images", and an "audio" field is ignored in
        // silence - the model then replies that it received nothing to transcribe.
        message.add("images", media);

        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0);

        JsonObject body = new JsonObject();
        body.addProperty("model", endpoint.model());
        body.add("messages", messages);
        body.addProperty("stream", false);
        body.addProperty("think", false);
        body.add("options", options);

        HttpRequest request = AiHttp.request(endpoint, "/api/chat")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        LOG.debug("Transcribing {} of audio with {} on {}", audio.duration(), endpoint.model(), endpoint);
        return readContent(AiHttp.send(http, request, "Transcription"));
    }

    /** Naming the language helps a general model; {@code auto} lets it decide. */
    private String instructionFor(String language) {
        String code = OpenAiSpeechToText.languageCode(language);
        return code.isEmpty() ? INSTRUCTION : INSTRUCTION + " The audio is in " + code + ".";
    }

    private String readContent(byte[] response) {
        String raw = new String(response, StandardCharsets.UTF_8).trim();
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject message = json.getAsJsonObject("message");
            if (message == null || !message.has(CONTENT)) {
                throw new AiRequestException("Transcription answered without a message content");
            }
            return message.get(CONTENT).getAsString().trim();
        } catch (JsonParseException | IllegalStateException e) {
            throw new AiRequestException("Transcription did not answer JSON: "
                    + (raw.length() > 120 ? raw.substring(0, 120) + "..." : raw), e);
        }
    }
}
