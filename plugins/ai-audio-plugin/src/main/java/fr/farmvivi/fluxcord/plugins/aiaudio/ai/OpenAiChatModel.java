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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 *
 * <p><strong>A round that offers tools is the exception</strong>, and it took the real server to see it: with
 * {@code reasoning_effort: "none"} <em>no tool call ever comes back</em>. Measured on the same Ollama with a
 * question only the memory could answer: Gemma 4 E4B answered "I have no memory of that" and Qwen 3.5 9B
 * <em>invented</em> a memory, while with {@code "low"} both called the tool, in 1.7 s and 1.9 s. Gemma even
 * said out loud "I must call recall_person" — the intention without the call. So the effort asked for depends
 * on whether tools were offered: {@code toolReasoningEffort} while it may still ask for something, and the
 * plain {@code reasoningEffort} on the round that only has to answer, which is the one whose latency is heard.
 *
 * <p>This class is the only place that knows how a tool is spelled on the wire; {@link ChatModel.Tool}
 * describes one in plain Java and the JSON schema is built here.
 */
public class OpenAiChatModel implements ChatModel {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiChatModel.class);

    private final AiEndpoint endpoint;
    private final HttpClient http;
    private final double temperature;
    private final String reasoningEffort;
    private final String toolReasoningEffort;

    /** What a round that offers tools asks for when nothing else was configured. See the class comment. */
    public static final String DEFAULT_TOOL_REASONING_EFFORT = "low";

    public OpenAiChatModel(AiEndpoint endpoint, HttpClient http, double temperature, String reasoningEffort) {
        this(endpoint, http, temperature, reasoningEffort, DEFAULT_TOOL_REASONING_EFFORT);
    }

    /**
     * @param endpoint            where the model lives
     * @param http                the shared client
     * @param temperature         how much the model may wander, 0 to 2
     * @param reasoningEffort     what to ask of a reasoning model when it only has to answer; empty omits the
     *                            field entirely, which is what a model that rejects the parameter needs
     * @param toolReasoningEffort what to ask on a round that offers tools, where {@code none} means no tool
     *                            call ever comes back
     */
    public OpenAiChatModel(AiEndpoint endpoint, HttpClient http, double temperature, String reasoningEffort,
                           String toolReasoningEffort) {
        this.endpoint = endpoint;
        this.http = http;
        this.temperature = Math.clamp(temperature, 0, 2);
        this.reasoningEffort = reasoningEffort == null ? "" : reasoningEffort.strip();
        this.toolReasoningEffort = toolReasoningEffort == null ? "" : toolReasoningEffort.strip();
    }

    @Override
    public Answer reply(List<Message> messages, List<Tool> tools, int maxTokens) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages are required");
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", endpoint.model());
        body.add("messages", wireMessages(messages));
        body.addProperty("stream", false);
        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", Math.max(1, maxTokens));
        boolean withTools = tools != null && !tools.isEmpty();
        String effort = withTools ? toolReasoningEffort : reasoningEffort;
        if (!effort.isEmpty()) {
            body.addProperty("reasoning_effort", effort);
        }
        if (withTools) {
            body.add("tools", wireTools(tools));
        }

        HttpRequest request = AiHttp.request(endpoint, "/chat/completions")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        LOG.debug("Asking {} for a reply to {} message(s) with {} tool(s)", endpoint, messages.size(),
                tools == null ? 0 : tools.size());
        return readAnswer(AiHttp.send(http, request, "Chat completion"));
    }

    private JsonArray wireMessages(List<Message> messages) {
        JsonArray wire = new JsonArray();
        for (Message message : messages) {
            JsonObject entry = new JsonObject();
            entry.addProperty("role", message.role().wireName());
            entry.addProperty("content", message.content());
            if (message.toolCallId() != null) {
                entry.addProperty("tool_call_id", message.toolCallId());
            }
            if (!message.toolCalls().isEmpty()) {
                entry.add("tool_calls", wireToolCalls(message.toolCalls()));
            }
            wire.add(entry);
        }
        return wire;
    }

    /** The assistant turn is replayed so the provider can match each result to the call it answers. */
    private JsonArray wireToolCalls(List<ToolCall> calls) {
        JsonArray wire = new JsonArray();
        for (ToolCall call : calls) {
            JsonObject function = new JsonObject();
            function.addProperty("name", call.name());
            function.addProperty("arguments", call.arguments());
            JsonObject entry = new JsonObject();
            entry.addProperty("id", call.id());
            entry.addProperty("type", "function");
            entry.add("function", function);
            wire.add(entry);
        }
        return wire;
    }

    /** Turns the plain-Java tool descriptions into the JSON schema the API expects. */
    private JsonArray wireTools(List<Tool> tools) {
        JsonArray wire = new JsonArray();
        for (Tool tool : tools) {
            JsonObject properties = new JsonObject();
            JsonArray required = new JsonArray();
            for (Map.Entry<String, Tool.Parameter> parameter : tool.parameters().entrySet()) {
                JsonObject schema = new JsonObject();
                schema.addProperty("type", parameter.getValue().type());
                schema.addProperty("description", parameter.getValue().description());
                properties.add(parameter.getKey(), schema);
                if (parameter.getValue().required()) {
                    required.add(parameter.getKey());
                }
            }
            JsonObject parameters = new JsonObject();
            parameters.addProperty("type", "object");
            parameters.add("properties", properties);
            parameters.add("required", required);

            JsonObject function = new JsonObject();
            function.addProperty("name", tool.name());
            function.addProperty("description", tool.description());
            function.add("parameters", parameters);

            JsonObject entry = new JsonObject();
            entry.addProperty("type", "function");
            entry.add("function", function);
            wire.add(entry);
        }
        return wire;
    }

    /**
     * Reads {@code choices[0].message}.
     *
     * <p>An empty content with no tool call and a non-empty token count is the signature of a reasoning model
     * that spent its whole budget thinking; it is reported as such rather than as silence, because the two need
     * different fixes.
     */
    private Answer readAnswer(byte[] response) {
        String raw = new String(response, StandardCharsets.UTF_8).trim();
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new AiRequestException("Chat completion answered without a choice");
            }
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null) {
                throw new AiRequestException("Chat completion answered without a message");
            }
            List<ToolCall> calls = readToolCalls(message);
            String content = message.has("content") && !message.get("content").isJsonNull()
                    ? message.get("content").getAsString()
                    : "";
            if (content.isBlank() && calls.isEmpty()) {
                throw new AiRequestException("Chat completion answered without any content"
                        + " (a reasoning model may have spent the whole token budget thinking)");
            }
            return new Answer(content, calls);
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new AiRequestException("Chat completion did not answer the expected JSON: "
                    + (raw.length() > 120 ? raw.substring(0, 120) + "..." : raw), e);
        }
    }

    private List<ToolCall> readToolCalls(JsonObject message) {
        JsonArray calls = message.getAsJsonArray("tool_calls");
        if (calls == null) {
            return List.of();
        }
        List<ToolCall> read = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            JsonObject call = calls.get(i).getAsJsonObject();
            JsonObject function = call.getAsJsonObject("function");
            if (function == null || !function.has("name")) {
                continue;
            }
            String arguments = function.has("arguments") && !function.get("arguments").isJsonNull()
                    ? asJsonText(function.get("arguments"))
                    : "{}";
            // Ollama has been known to omit the id; the results have to quote something back, so one is made up.
            String id = call.has("id") && !call.get("id").isJsonNull()
                    ? call.get("id").getAsString()
                    : "call_" + i;
            read.add(new ToolCall(id, function.get("name").getAsString(), arguments));
        }
        return read;
    }

    /**
     * The arguments as JSON text.
     *
     * <p>OpenAI sends them as a JSON <em>string</em> holding JSON; Ollama sends the object itself. Both are
     * accepted, because a plugin cannot choose which one it is talking to.
     */
    private String asJsonText(com.google.gson.JsonElement arguments) {
        return arguments.isJsonPrimitive() ? arguments.getAsString() : arguments.toString();
    }
}
