package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import com.google.gson.JsonObject;
import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;

/**
 * Text-to-speech over {@code POST /audio/speech}, the OpenAI speech endpoint.
 *
 * <p>Works unchanged against OpenAI and against a self-hosted server that implements the same route
 * (Kokoro-FastAPI, openedai-speech, LocalAI). WAV is requested explicitly because it is the only format
 * every one of them supports and the only one this plugin can decode without a codec.
 */
public class OpenAiTextToSpeech implements TextToSpeech {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiTextToSpeech.class);

    private final AiEndpoint endpoint;
    private final HttpClient http;

    public OpenAiTextToSpeech(AiEndpoint endpoint, HttpClient http) {
        this.endpoint = endpoint;
        this.http = http;
    }

    @Override
    public PcmAudio synthesize(String text, String voice) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text is required");
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", endpoint.model());
        body.addProperty("input", text);
        body.addProperty("voice", voice);
        // Anything else would need a decoder; every compatible server can answer WAV.
        body.addProperty("response_format", "wav");

        HttpRequest request = AiHttp.request(endpoint, "/audio/speech")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        LOG.debug("Synthesising {} characters with voice '{}' on {}", text.length(), voice, endpoint);
        byte[] wav = AiHttp.send(http, request, "Text-to-speech");
        try {
            PcmAudio audio = PcmAudio.fromWav(wav);
            LOG.debug("Synthesised {} of audio at {} Hz, {} channel(s)",
                    audio.duration(), audio.sampleRate(), audio.channels());
            return audio;
        } catch (IllegalArgumentException e) {
            // A server that ignored response_format, or an HTML page from a misconfigured proxy.
            throw new AiRequestException("Text-to-speech did not return a usable WAV: " + e.getMessage(), e);
        }
    }
}
