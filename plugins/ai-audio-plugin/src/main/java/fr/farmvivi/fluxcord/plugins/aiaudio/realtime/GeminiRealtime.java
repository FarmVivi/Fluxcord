package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Google's Live API dialect ({@code BidiGenerateContent}).
 *
 * <p>Here because it is roughly an order of magnitude cheaper per minute of conversation than the OpenAI
 * Realtime API, which for a bot sitting in a voice channel is the difference between a toy and something you
 * leave switched on. It is a genuinely different protocol, not a variant: there is no {@code "type"} field,
 * and what happened is told by <em>which top-level key the frame has</em>.
 *
 * <p>Four differences cost real work and are worth naming, because each one is a way this could silently
 * half-work:
 * <ul>
 *   <li><strong>The rates differ in each direction.</strong> Input is 16 kHz, output is 24 kHz — unlike
 *       OpenAI, where both are 24. The output rate is read from the {@code mimeType} that comes with the
 *       audio rather than assumed, so a service that changes it does not leave the bot speaking at the wrong
 *       pitch.
 *   <li><strong>The model is named in the first frame</strong>, not in the URL, so this dialect is the only
 *       one that needs configuring.
 *   <li><strong>The key goes in the URL</strong>, as {@code ?key=}, and there is no Authorization header.
 *   <li><strong>One frame says several things.</strong> A {@code serverContent} can carry the audio, its
 *       transcription and the fact that the turn is over all at once — which is why
 *       {@link RealtimeProtocol#parseAll} exists. Returning only the first of those would mean a turn that is
 *       never written to the memory.
 * </ul>
 *
 * <p>Transcription of both sides is asked for explicitly ({@code inputAudioTranscription} and
 * {@code outputAudioTranscription}), and is not optional for this plugin: the memory keeps words, so a
 * session that only exchanged audio would leave nothing behind.
 */
public final class GeminiRealtime implements RealtimeProtocol {

    /** What the API wants to be fed. */
    public static final int INPUT_SAMPLE_RATE = 16_000;

    /** What it speaks at, used when a frame arrives without saying. */
    public static final int OUTPUT_SAMPLE_RATE = 24_000;

    /** The published endpoint; the key is appended to it as a query parameter. */
    public static final String DEFAULT_URL = "wss://generativelanguage.googleapis.com/ws/"
            + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

    private final String model;

    /**
     * @param model the model to open the session with; {@code models/} is prepended when it is missing,
     *              since that prefix is part of the resource name and easy to forget in a config file
     */
    public GeminiRealtime(String model) {
        String name = model == null ? "" : model.strip();
        this.model = name.startsWith("models/") ? name : "models/" + name;
    }

    /** @return the model as the setup frame names it, prefix included */
    public String model() {
        return model;
    }

    @Override
    public int inputSampleRate() {
        return INPUT_SAMPLE_RATE;
    }

    @Override
    public String session(SessionConfig config) {
        String instructions = config.instructions();
        String voice = config.voice();
        List<ChatModel.Tool> tools = config.tools();
        JsonObject generationConfig = new JsonObject();
        JsonArray modalities = new JsonArray();
        modalities.add("AUDIO");
        generationConfig.add("responseModalities", modalities);
        if (voice != null && !voice.isBlank()) {
            JsonObject prebuilt = new JsonObject();
            prebuilt.addProperty("voiceName", voice.strip());
            JsonObject voiceConfig = new JsonObject();
            voiceConfig.add("prebuiltVoiceConfig", prebuilt);
            JsonObject speechConfig = new JsonObject();
            speechConfig.add("voiceConfig", voiceConfig);
            generationConfig.add("speechConfig", speechConfig);
        }

        JsonObject setup = new JsonObject();
        setup.addProperty("model", model);
        setup.add("generationConfig", generationConfig);
        if (instructions != null && !instructions.isBlank()) {
            setup.add("systemInstruction", textContent(instructions));
        }
        if (tools != null && !tools.isEmpty()) {
            setup.add("tools", wireTools(tools));
        }
        // Not optional for this plugin: the memory keeps words, and a session that only exchanged audio
        // would leave nothing behind for the next conversation to remember.
        setup.add("inputAudioTranscription", new JsonObject());
        setup.add("outputAudioTranscription", new JsonObject());
        setup.add("realtimeInputConfig", realtimeInput(config.serviceDecidesTurns()));

        JsonObject frame = new JsonObject();
        frame.add("setup", setup);
        return frame.toString();
    }

    /**
     * Who closes a turn, and whether the bot may be talked over.
     *
     * <p>MEASURED, 2026-10-03: with {@code automaticActivityDetection.disabled} the service waits for
     * {@code activityStart} and {@code activityEnd} and answers nothing in between, while still streaming
     * the input transcription — which is what lets this plugin read who was being addressed before deciding
     * to answer. {@code NO_INTERRUPTION} is the other half: without it, anybody making a noise cuts the bot
     * off mid-sentence, which in a channel with eight people in it means it never finishes a sentence.
     */
    private static JsonObject realtimeInput(boolean serviceDecidesTurns) {
        JsonObject realtimeInput = new JsonObject();
        if (!serviceDecidesTurns) {
            JsonObject detection = new JsonObject();
            detection.addProperty("disabled", true);
            realtimeInput.add("automaticActivityDetection", detection);
        }
        realtimeInput.addProperty("activityHandling",
                serviceDecidesTurns ? "START_OF_ACTIVITY_INTERRUPTS" : "NO_INTERRUPTION");
        return realtimeInput;
    }

    /** A {@code Content} holding one piece of text, the shape this API uses everywhere. */
    private static JsonObject textContent(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        return content;
    }

    /** One {@code Tool} carrying every function, rather than one entry per function. */
    private static JsonArray wireTools(List<ChatModel.Tool> tools) {
        JsonArray declarations = new JsonArray();
        for (ChatModel.Tool tool : tools) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", tool.name());
            entry.addProperty("description", tool.description());
            entry.add("parameters", JsonSchema.of(tool));
            declarations.add(entry);
        }
        JsonObject group = new JsonObject();
        group.add("functionDeclarations", declarations);
        JsonArray wire = new JsonArray();
        wire.add(group);
        return wire;
    }

    @Override
    public String appendAudio(PcmAudio audio) {
        PcmAudio wire = audio.sampleRate() == INPUT_SAMPLE_RATE && audio.channels() == CHANNELS
                ? audio
                : audio.resample(INPUT_SAMPLE_RATE, CHANNELS);
        JsonObject blob = new JsonObject();
        blob.addProperty("data", Base64.getEncoder().encodeToString(wire.samples()));
        blob.addProperty("mimeType", "audio/pcm;rate=" + INPUT_SAMPLE_RATE);

        JsonObject realtimeInput = new JsonObject();
        realtimeInput.add("audio", blob);
        JsonObject frame = new JsonObject();
        frame.add("realtimeInput", realtimeInput);
        return frame.toString();
    }

    /**
     * @return {@code activityEnd}, which on this API both closes the turn and asks for the answer — see
     *         {@link #answersOnTurnEnd()}
     */
    @Override
    public String commitAudio() {
        return activity("activityEnd");
    }

    @Override
    public String beginTurn() {
        return activity("activityStart");
    }

    @Override
    public boolean answersOnTurnEnd() {
        return true;
    }

    private static String activity(String marker) {
        JsonObject realtimeInput = new JsonObject();
        realtimeInput.add(marker, new JsonObject());
        JsonObject frame = new JsonObject();
        frame.add("realtimeInput", realtimeInput);
        return frame.toString();
    }

    /**
     * @return nothing. The service answers as soon as it decides the turn is over, including after a tool
     *         result, so there is no frame that means "answer now" and sending one would be inventing it.
     */
    @Override
    public String createResponse() {
        return "";
    }

    /**
     * @return nothing. Interruption is the server's: it detects the overlap, stops generating and says so
     *         with {@code serverContent.interrupted}. What remains for this side is dropping the audio it
     *         already queued, which the caller does regardless.
     */
    @Override
    public String cancelResponse() {
        return "";
    }

    @Override
    public String speakerChanged(String displayName) {
        JsonObject turn = textContent("\"" + displayName + "\" is speaking now.");
        turn.addProperty("role", "user");
        JsonArray turns = new JsonArray();
        turns.add(turn);

        JsonObject clientContent = new JsonObject();
        clientContent.add("turns", turns);
        // False on purpose: naming the speaker is not a question, and ending the turn here would make the
        // model answer the label instead of the sentence that follows it.
        clientContent.addProperty("turnComplete", false);

        JsonObject frame = new JsonObject();
        frame.add("clientContent", clientContent);
        return frame.toString();
    }

    @Override
    public String toolResult(ChatModel.ToolCall call, String output) {
        JsonObject response = new JsonObject();
        response.addProperty("result", output);

        JsonObject functionResponse = new JsonObject();
        if (call.id() != null && !call.id().isBlank()) {
            functionResponse.addProperty("id", call.id());
        }
        functionResponse.addProperty("name", call.name());
        functionResponse.add("response", response);

        JsonArray functionResponses = new JsonArray();
        functionResponses.add(functionResponse);
        JsonObject toolResponse = new JsonObject();
        toolResponse.add("functionResponses", functionResponses);

        JsonObject frame = new JsonObject();
        frame.add("toolResponse", toolResponse);
        return frame.toString();
    }

    /** @return no headers: this API authenticates by query parameter */
    @Override
    public Map<String, String> headers(String apiKey) {
        return Map.of();
    }

    @Override
    public String endpoint(String url, String apiKey) {
        String base = url == null || url.isBlank() ? DEFAULT_URL : url.strip();
        if (apiKey == null || apiKey.isBlank() || base.contains("key=")) {
            return base;
        }
        return base + (base.contains("?") ? "&" : "?") + "key="
                + java.net.URLEncoder.encode(apiKey.strip(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public Event parse(String frame) {
        List<Event> events = parseAll(frame);
        return events.get(0);
    }

    @Override
    public List<Event> parseAll(String frame) {
        if (frame == null || frame.isBlank()) {
            return List.of(new Event.Ignored("<empty>"));
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(frame).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return List.of(new Event.Failure("the service sent something that is not JSON"));
        }
        if (json.has("error")) {
            return List.of(new Event.Failure(errorMessage(json)));
        }
        if (json.has("setupComplete")) {
            return List.of(new Event.Ignored("setupComplete"));
        }
        if (json.has("toolCall")) {
            return toolCalls(json.getAsJsonObject("toolCall"));
        }
        if (json.has("serverContent") && json.get("serverContent").isJsonObject()) {
            return serverContent(json.getAsJsonObject("serverContent"));
        }
        return List.of(new Event.Ignored(json.keySet().stream().findFirst().orElse("<unknown>")));
    }

    /**
     * One {@code serverContent}, which may say up to three things.
     *
     * <p>The order matters and is the order below: an interruption first, because everything after it is
     * about an answer that has been abandoned; then the audio and the words; then the fact that the turn is
     * over, which is what makes the caller record it.
     */
    private List<Event> serverContent(JsonObject content) {
        List<Event> events = new ArrayList<>();
        if (bool(content, "interrupted")) {
            // Mapped to "somebody started talking" because that is what it means here and what the caller
            // does about it: stop speaking and keep what was actually said.
            events.add(new Event.SpeechStarted());
        }
        Event audio = audioOf(content);
        if (audio != null) {
            events.add(audio);
        }
        String spoken = transcript(content, "outputTranscription");
        if (!spoken.isEmpty()) {
            events.add(new Event.TranscriptDelta(spoken));
        }
        String heard = transcript(content, "inputTranscription");
        if (!heard.isEmpty()) {
            // A fragment, not an utterance: this API streams the input transcription like the output one.
            events.add(new Event.HeardDelta(heard));
        }
        if (bool(content, "turnComplete")) {
            events.add(new Event.ResponseDone());
        }
        if (events.isEmpty()) {
            events.add(new Event.Ignored("serverContent with nothing acted on"));
        }
        return List.copyOf(events);
    }

    /**
     * Every audio part of a model turn, joined into one.
     *
     * <p>Joined rather than reported separately because they are consecutive samples of the same sentence:
     * handing them over as one buffer is both correct and one fewer queue operation per frame.
     *
     * @return the audio, or null when this frame carried none
     */
    private Event audioOf(JsonObject content) {
        if (!content.has("modelTurn") || !content.get("modelTurn").isJsonObject()) {
            return null;
        }
        JsonObject turn = content.getAsJsonObject("modelTurn");
        if (!turn.has("parts") || !turn.get("parts").isJsonArray()) {
            return null;
        }
        ByteArrayOutputStream samples = new ByteArrayOutputStream();
        int rate = OUTPUT_SAMPLE_RATE;
        for (JsonElement element : turn.getAsJsonArray("parts")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject part = element.getAsJsonObject();
            if (!part.has("inlineData") || !part.get("inlineData").isJsonObject()) {
                continue;
            }
            JsonObject inline = part.getAsJsonObject("inlineData");
            String data = string(inline, "data");
            if (data.isEmpty()) {
                continue;
            }
            try {
                samples.writeBytes(Base64.getDecoder().decode(data));
            } catch (IllegalArgumentException e) {
                return new Event.Failure("the service sent audio that is not base64");
            }
            rate = rateOf(string(inline, "mimeType"), rate);
        }
        return samples.size() == 0 ? null
                : new Event.AudioDelta(new PcmAudio(samples.toByteArray(), rate, CHANNELS));
    }

    /**
     * The rate out of {@code audio/pcm;rate=24000}.
     *
     * <p>Read rather than assumed: the published input and output rates already differ, and a bot speaking at
     * the wrong pitch is a bug nobody would think to look for in a JSON parser.
     *
     * @param mimeType the declared type, possibly empty
     * @param fallback what to use when it says nothing usable
     * @return the sample rate in hertz
     */
    static int rateOf(String mimeType, int fallback) {
        if (mimeType == null) {
            return fallback;
        }
        int at = mimeType.indexOf("rate=");
        if (at < 0) {
            return fallback;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = at + "rate=".length(); i < mimeType.length(); i++) {
            char c = mimeType.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            digits.append(c);
        }
        if (digits.isEmpty()) {
            return fallback;
        }
        try {
            int rate = Integer.parseInt(digits.toString());
            return rate > 0 ? rate : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private List<Event> toolCalls(JsonObject toolCall) {
        if (!toolCall.has("functionCalls") || !toolCall.get("functionCalls").isJsonArray()) {
            return List.of(new Event.Ignored("a toolCall with no calls"));
        }
        List<Event> events = new ArrayList<>();
        for (JsonElement element : toolCall.getAsJsonArray("functionCalls")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject call = element.getAsJsonObject();
            String name = string(call, "name");
            if (name.isEmpty()) {
                continue;
            }
            // The arguments arrive as an object; every tool source parses them from text, so they are
            // handed over the way the chat path hands them over.
            String arguments = call.has("args") && call.get("args").isJsonObject()
                    ? call.getAsJsonObject("args").toString()
                    : "{}";
            events.add(new Event.ToolCalled(new ChatModel.ToolCall(string(call, "id"), name, arguments)));
        }
        return events.isEmpty() ? List.of(new Event.Ignored("a toolCall with nothing callable"))
                : List.copyOf(events);
    }

    private static String transcript(JsonObject content, String member) {
        if (!content.has(member) || !content.get(member).isJsonObject()) {
            return "";
        }
        return string(content.getAsJsonObject(member), "text");
    }

    private static String errorMessage(JsonObject json) {
        if (json.get("error").isJsonObject()) {
            String message = string(json.getAsJsonObject("error"), "message");
            return message.isEmpty() ? "the service reported an error without a message" : message;
        }
        String flat = string(json, "error");
        return flat.isEmpty() ? "the service reported an error without a message" : flat;
    }

    private static boolean bool(JsonObject json, String member) {
        try {
            return json.has(member) && json.get(member).isJsonPrimitive()
                    && json.get(member).getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String string(JsonObject json, String member) {
        return json.has(member) && json.get(member).isJsonPrimitive()
                ? json.get(member).getAsString()
                : "";
    }
}
