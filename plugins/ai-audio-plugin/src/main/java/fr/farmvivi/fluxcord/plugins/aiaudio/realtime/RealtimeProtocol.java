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
 * The Realtime event protocol, as pure functions: frames in, frames out, nothing else.
 *
 * <p>Separated from the socket on purpose. A WebSocket conversation with a hosted service cannot be exercised
 * from a test, but <em>what we send</em> and <em>what we do with what arrives</em> can be, completely — which is
 * the same split the rest of this plugin uses to keep the untestable part down to a few lines of plumbing
 * ({@link RealtimeSession}).
 *
 * <p><strong>Unvalidated against the real service.</strong> Everything here is built from the published event
 * names and nobody has run it against OpenAI from this repository. Two things are known to have moved since the
 * beta and are worth checking first if it does not work: audio deltas are
 * {@code response.output_audio.delta} (the beta called them {@code response.audio.delta}), and the session's
 * audio format is a nested object, {@code session.audio.input.format = {"type": "audio/pcm", "rate": 24000}},
 * where the beta had a flat {@code input_audio_format: "pcm16"}. An unknown event is ignored rather than
 * treated as an error, so a protocol that gains events does not break this one.
 *
 * <p>Audio on the wire is <strong>24 kHz mono 16-bit, base64</strong> in both directions. Discord's 48 kHz
 * stereo is resampled on the way in and back on the way out, by the same {@link PcmAudio} the rest of the
 * plugin uses.
 */
public final class RealtimeProtocol {

    /** What the API takes and returns: 24 kHz mono 16-bit PCM. */
    public static final int SAMPLE_RATE = 24_000;
    private static final int CHANNELS = 1;

    private RealtimeProtocol() {
    }

    /**
     * The opening frame: who the bot is, which voice it uses, and what it may call.
     *
     * <p>Server-side turn detection is asked for, which is what makes the conversation full duplex — the
     * service decides when somebody has stopped talking, instead of this plugin polling for silence as the
     * turn-based path has to.
     *
     * @param instructions the system prompt; operator-built, exactly as in the turn-based path
     * @param voice        the provider's voice name
     * @param tools        what the model may call, possibly empty
     * @return the frame to send
     */
    public static String sessionUpdate(String instructions, String voice, List<ChatModel.Tool> tools) {
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
            JsonObject properties = new JsonObject();
            JsonArray required = new JsonArray();
            for (Map.Entry<String, ChatModel.Tool.Parameter> parameter : tool.parameters().entrySet()) {
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

            JsonObject entry = new JsonObject();
            entry.addProperty("type", "function");
            entry.addProperty("name", tool.name());
            entry.addProperty("description", tool.description());
            entry.add("parameters", parameters);
            wire.add(entry);
        }
        return wire;
    }

    /**
     * One chunk of what somebody is saying.
     *
     * @param audio the captured audio, in any rate this plugin handles
     * @return the frame to send
     */
    public static String appendAudio(PcmAudio audio) {
        PcmAudio wire = audio.sampleRate() == SAMPLE_RATE && audio.channels() == CHANNELS
                ? audio
                : audio.resample(SAMPLE_RATE, CHANNELS);
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "input_audio_buffer.append");
        frame.addProperty("audio", Base64.getEncoder().encodeToString(wire.samples()));
        return frame.toString();
    }

    /** @return the frame that closes the input buffer, for the case where turn detection is not the server's */
    public static String commitAudio() {
        return type("input_audio_buffer.commit");
    }

    /** @return the frame that asks for an answer now */
    public static String createResponse() {
        return type("response.create");
    }

    /**
     * Stops the bot mid-sentence.
     *
     * <p>Half of what full duplex means: somebody starts talking over the bot and it stops, rather than
     * finishing its paragraph into the noise. The queued audio has to be dropped on this side as well, which is
     * {@code PcmSendHandler.clear()}.
     *
     * @return the frame to send
     */
    public static String cancelResponse() {
        return type("response.cancel");
    }

    /**
     * Says who is talking now.
     *
     * <p><strong>This is the answer to the one problem Realtime creates for this plugin.</strong> A session has
     * a single input buffer, while the whole design hears each person separately — so attribution would be lost
     * the moment two people share a session. Naming the speaker as a text item before their audio keeps it, at
     * utterance granularity, which is the granularity the segmenter already works in.
     *
     * <p>The name goes in a <em>user</em> item, never the instructions: a display name is chosen by its owner,
     * and the trust boundary is the same here as everywhere else in this plugin.
     *
     * @param displayName the speaker, as the others in the server see them
     * @return the frame to send
     */
    public static String speakerChanged(String displayName) {
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

    /**
     * What a tool answered.
     *
     * @param callId what the model called it
     * @param output what the tool found, as text
     * @return the frame to send
     */
    public static String toolResult(String callId, String output) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "function_call_output");
        item.addProperty("call_id", callId);
        item.addProperty("output", output);

        JsonObject frame = new JsonObject();
        frame.addProperty("type", "conversation.item.create");
        frame.add("item", item);
        return frame.toString();
    }

    private static String type(String name) {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", name);
        return frame.toString();
    }

    /**
     * Reads one frame from the service.
     *
     * <p>Anything not recognised becomes {@link Event.Ignored} rather than a failure: the service has 28 server
     * events and this plugin acts on six of them, so treating the rest as errors would mean a protocol that
     * breaks every time the service gains a feature.
     *
     * @param frame the text frame as received
     * @return what happened
     */
    public static Event parse(String frame) {
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

    /** Something the service said, reduced to what this plugin acts on. */
    public sealed interface Event {

        /** A piece of the bot's voice, ready to play. */
        record AudioDelta(PcmAudio audio) implements Event {
        }

        /** A piece of what the bot is saying, in words, which is what the memory keeps. */
        record TranscriptDelta(String text) implements Event {
        }

        /** What somebody in the channel said, as the service transcribed it. */
        record HeardFromSomebody(String text) implements Event {
        }

        /** Somebody started talking: if the bot is speaking, it should stop. */
        record SpeechStarted() implements Event {
        }

        /** Somebody stopped talking; the service will answer on its own. */
        record SpeechStopped() implements Event {
        }

        /** The model wants something looked up before it answers. */
        record ToolCalled(ChatModel.ToolCall call) implements Event {
        }

        /** The answer is finished. */
        record ResponseDone() implements Event {
        }

        /**
         * Something went wrong, in words a user could be shown.
         *
         * @param message what happened
         */
        record Failure(String message) implements Event {
        }

        /**
         * A frame this plugin does not act on.
         *
         * @param type the event type, for a debug line
         */
        record Ignored(String type) implements Event {
        }
    }
}
