package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Chat over {@code POST /chat/completions}, which OpenAI defines and Ollama also serves.
 *
 * <p>{@code stream} is always off: the answer is synthesised as a whole anyway.
 *
 * <p>Stopping a reasoning model from thinking matters more than it sounds, and the field that does it is
 * <strong>{@code reasoning_effort}</strong>. Measured against Ollama 0.34 with a Gemma 4 E4B: with
 * {@code reasoning_effort: "none"} the answer came back in 0.29 s using 14 tokens; with Ollama's own
 * {@code think: false}, which this route silently ignores, the model spent all 120 tokens thinking and the
 * content came back <em>empty</em>. Left alone entirely it does answer, but only after burning 376 tokens and
 * five seconds on a reasoning block nobody hears.
 */
public class OpenAiChatModel implements ChatModel {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiChatModel.class);

    private final AiEndpoint endpoint;
    private final HttpClient http;
    private final double temperature;
    private final String reasoningEffort;

    /**
     * @param endpoint        where the model lives
     * @param http            the shared client
     * @param temperature     how much the model may wander, 0 to 2
     * @param reasoningEffort what to ask of a reasoning model; empty omits the field entirely, which is what
     *                        a model that rejects the parameter needs
     */
    public OpenAiChatModel(AiEndpoint endpoint, HttpClient http, double temperature, String reasoningEffort) {
        this.endpoint = endpoint;
        this.http = http;
        this.temperature = Math.clamp(temperature, 0, 2);
        this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.strip();
    }

    @Override
    public String reply(List<Message> messages, int maxTokens) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages are required");
        }
        JsonArray wire = new JsonArray();
        for (Message message : messages) {
            JsonObject entry = new JsonObject();
            entry.addProperty("role", message.role().wireName());
            entry.addProperty("content", message.content());
            wire.add(entry);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", endpoint.model());
        body.add("messages", wire);
        body.addProperty("stream", false);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", Math.max(1, maxTokens));
        if (!reasoningEffort.isEmpty()) {
            body.addProperty("reasoning_effort", reasoningEffort);
        }

        HttpRequest request = AiHttp.request(endpoint, "/chat/completions")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        LOG.debug("Asking {} for a reply to {} message(s)", endpoint, messages.size());
        return readReply(AiHttp.send(http, request, "Chat completion"));
    }

    /**
     * Reads {@code choices[0].message.content}.
     *
     * <p>An empty content with a non-empty answer is the signature of a reasoning model that spent its whole
     * budget thinking; it is reported as such rather than as silence, because the two need different fixes.
     */
    private String readReply(byte[] response) {
        String raw = new String(response, StandardCharsets.UTF_8).trim();
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new AiRequestException("Chat completion answered without a choice");
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
                throw new AiRequestException("Chat completion answered without any content"
                        + " (a reasoning model may have spent the whole token budget thinking)");
            }
            return message.get("content").getAsString().trim();
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new AiRequestException("Chat completion did not answer the expected JSON: "
                    + (raw.length() > 120 ? raw.substring(0, 120) + "..." : raw), e);
        }
    }
}
