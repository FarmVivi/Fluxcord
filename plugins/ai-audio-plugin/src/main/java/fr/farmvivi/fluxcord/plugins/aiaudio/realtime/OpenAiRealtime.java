package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * OpenAI's Realtime event protocol.
 *
 * <p>Every frame carries a {@code "type"} and the model is named in the URL, so this dialect needs no
 * configuration of its own. Audio on the wire is <strong>24 kHz mono 16-bit, base64</strong> in both
 * directions.
 *
 * <p>Two things are known to have moved since the beta and are worth checking first if it does not work:
 * audio deltas are {@code response.output_audio.delta} (the beta called them {@code response.audio.delta}),
 * and the session's audio format is a nested object,
 * {@code session.audio.input.format = {"type": "audio/pcm", "rate": 24000}}, where the beta had a flat
 * {@code input_audio_format: "pcm16"}.
 */
public final class OpenAiRealtime implements RealtimeProtocol {

    /** What the API takes and returns: 24 kHz mono 16-bit PCM. */
    public static final int SAMPLE_RATE = 24_000;

    @Override
    public int inputSampleRate() {
        return SAMPLE_RATE;
    }

    @Override
    public String session(String instructions, String voice, List<ChatModel.Tool> tools) {
        JsonObject format = new JsonObject();
        format.addProperty("type", "audio/pcm");
        format.addProperty("rate", SAMPLE_RATE);

        JsonObject input = new JsonObject();
        input.add("format", format.deepCopy());
        JsonObject turnDetection = new JsonObject();
        turnDetection.addProperty("type", "server_vad");
        input.add("turn_detection", turnDetection);

        JsonObject output = new JsonObject();
        output.add("format", format.deepCopy());
        if (voice != null && !voice.isBlank()) {
            output.addProperty("voice", voice.strip());
        }

        JsonObject audio = new JsonObject();
        audio.add("input", input);
        audio.add("output", output);

        JsonObject session = new JsonObject();
        session.add("audio", audio);
        if (instructions != null && !instructions.isBlank()) {
            session.addProperty("instructions", instructions);
        }
        if (tools != null && !tools.isEmpty()) {
            session.add("tools", wireTools(tools));
        }

        JsonObject frame = new JsonObject();
        frame.addProperty("type", "session.update");
        frame.add("session", session);
        return frame.toString();
    }

    /**
     * Tools as the session declares them.
     *
     * <p>Flatter than the chat API's shape — the name and parameters sit directly on the entry rather than
     * under a {@code function} object — which is why {@link ChatModel.Tool} describes a tool in plain Java and
     * each client spells it out itself.
     */
    private static JsonArray wireTools(List<ChatModel.Tool> tools) {
        JsonArray wire = new JsonArray();
        for (ChatModel.Tool tool : tools) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "function");
            entry.addProperty("name", tool.name());
            entry.addProperty("description", tool.description());
            entry.add("parameters", JsonSchema.of(tool));
            wire.add(entry);
        }
        return wire;
    }

    @Override
    public String appendAudio(PcmAudio audio) {
        PcmAudio wire = audio.sampleRate() == SAMPLE_RATE && audio.channels() == CHANNELS
                ? audio
                : audio.resample(SAMPLE_RATE, CHANNELS);
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "input_audio_buffer.append");
        frame.addProperty("audio", Base64.getEncoder().encodeToString(wire.samples()));
        return frame.toString();
    }

    @Override
    public String commitAudio() {
        return type("input_audio_buffer.commit");
    }

    @Override
    public String createResponse() {
        return type("response.create");
    }

    @Override
    public String cancelResponse() {
        return type("response.cancel");
    }

    @Override
    public String speakerChanged(String displayName) {
        JsonObject text = new JsonObject();
        text.addProperty("type", "input_text");
        text.addProperty("text", "\"" + displayName + "\" is speaking now.");
        JsonArray content = new JsonArray();
        content.add(text);

        JsonObject item = new JsonObject();
        item.addProperty("type", "message");
        item.addProperty("role", "user");
        item.add("content", content);

        JsonObject frame = new JsonObject();
        frame.addProperty("type", "conversation.item.create");
        frame.add("item", item);
        return frame.toString();
    }

    @Override
    public String toolResult(ChatModel.ToolCall call, String output) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "function_call_output");
        item.addProperty("call_id", call.id());
        item.addProperty("output", output);

        JsonObject frame = new JsonObject();
        frame.addProperty("type", "conversation.item.create");
        frame.add("item", item);
        return frame.toString();
    }

    @Override
    public Map<String, String> headers(String apiKey) {
        return apiKey == null || apiKey.isBlank()
                ? Map.of()
                : Map.of("Authorization", "Bearer " + apiKey.strip());
    }

    @Override
    public String endpoint(String url, String apiKey) {
        return url;
    }

    private static String type(String name) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", name);
        return frame.toString();
    }

    @Override
    public Event parse(String frame) {
        if (frame == null || frame.isBlank()) {
            return new Event.Ignored("<empty>");
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(frame).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return new Event.Failure("the service sent something that is not JSON");
        }
        String type = string(json, "type");
        return switch (type) {
            case "response.output_audio.delta" -> audioDelta(json);
            case "response.output_audio_transcript.delta" -> new Event.TranscriptDelta(string(json, "delta"));
            case "conversation.item.input_audio_transcription.completed" ->
                    new Event.HeardFromSomebody(string(json, "transcript"));
            case "input_audio_buffer.speech_started" -> new Event.SpeechStarted();
            case "input_audio_buffer.speech_stopped" -> new Event.SpeechStopped();
            case "response.function_call_arguments.done" -> toolCall(json);
            case "response.done" -> new Event.ResponseDone();
            case "error" -> new Event.Failure(errorMessage(json));
            default -> new Event.Ignored(type);
        };
    }

    private static Event audioDelta(JsonObject json) {
        String delta = string(json, "delta");
        if (delta.isEmpty()) {
            return new Event.Ignored("response.output_audio.delta without audio");
        }
        try {
            byte[] samples = Base64.getDecoder().decode(delta);
            if (samples.length == 0) {
                return new Event.Ignored("response.output_audio.delta with no samples");
            }
            return new Event.AudioDelta(new PcmAudio(samples, SAMPLE_RATE, CHANNELS));
        } catch (IllegalArgumentException e) {
            return new Event.Failure("the service sent audio that is not base64");
        }
    }

    private static Event toolCall(JsonObject json) {
        String name = string(json, "name");
        String callId = string(json, "call_id");
        if (name.isEmpty() || callId.isEmpty()) {
            return new Event.Ignored("a tool call with no name or no id");
        }
        String arguments = string(json, "arguments");
        return new Event.ToolCalled(new ChatModel.ToolCall(callId, name,
                arguments.isEmpty() ? "{}" : arguments));
    }

    /** The service nests its errors; a flat string is accepted too, since one proxy in front of it will. */
    private static String errorMessage(JsonObject json) {
        if (json.has("error") && json.get("error").isJsonObject()) {
            String message = string(json.getAsJsonObject("error"), "message");
            return message.isEmpty() ? "the service reported an error without a message" : message;
        }
        String flat = string(json, "error");
        return flat.isEmpty() ? "the service reported an error without a message" : flat;
    }

    private static String string(JsonObject json, String member) {
        return json.has(member) && json.get(member).isJsonPrimitive()
                ? json.get(member).getAsString()
                : "";
    }
}
