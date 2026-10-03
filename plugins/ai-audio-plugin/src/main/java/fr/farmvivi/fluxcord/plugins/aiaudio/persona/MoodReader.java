package fr.farmvivi.fluxcord.plugins.aiaudio.persona;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks the model what the mood in the room actually is, instead of inferring it from the fact somebody spoke.
 *
 * <p>Before this, {@code energy} went up on every transcribed sentence, which is an activity counter wearing
 * an emotion's name: the bot got livelier while being told bad news. Reading the room is something a language
 * model is genuinely good at, and it is the one judgement here that cannot be computed.
 *
 * <p><strong>A separate request, not a field of the answer.</strong> Three shapes were considered and this is
 * the only one that survives the rest of the plugin: a tool call costs a whole extra round (3 to 8 s measured),
 * and asking for the mood alongside the spoken reply — as JSON, or as a marker to strip — breaks as soon as the
 * model answers in audio, because then it would <em>say</em> the numbers out loud. A small separate call is
 * independent of the modality, and it runs after the answer has been handed to playback, so nobody waits for
 * it.
 *
 * <p>Measured on a local Ollama, four scenarios (bad news, a row, joking, a flat exchange), three attempts
 * each: asked for <strong>JSON</strong>, Gemma 4 E4B read the room sensibly <strong>12 times out of 12</strong>
 * in 0.28 to 0.36 s, and Qwen 3.5 9B 9 out of 12. Asked for the same two numbers as
 * {@code energy=<n> warmth=<n>}, the same models scored 6 of 12 and 3 of 12 and took up to 3.7 s — they
 * repeated stereotyped pairs (+0.00/+0.90, +0.50/+0.80) rather than judging. Hence JSON, with the flat format
 * accepted on reading only because a model sometimes answers it anyway.
 */
public class MoodReader {

    private static final Logger logger = LoggerFactory.getLogger(MoodReader.class);

    /** Operator-written, and the only instruction in this request. The conversation itself is user content. */
    private static final String INSTRUCTIONS = """
            You read the mood of a conversation. Answer with one JSON object and nothing else:
            {"energy": <number>, "warmth": <number>}, both between -1 and 1.
            energy is -1 when the conversation is calm, flat or sad and +1 when it is excited or agitated.
            warmth is -1 when it is cold, hostile or hurtful and +1 when it is friendly or affectionate.""";

    /** Most turns to show. The mood is about the last minute of conversation, not its whole history. */
    public static final int MAX_TURNS = 8;

    /**
     * The separator is spelled out rather than "a few non-digits": a greedy {@code \D} class eats the minus
     * sign, which turns {@code warmth=-0.25} into a cheerful {@code +0.25}. A sign belongs to the number.
     */
    /**
     * Both are written to be <strong>unambiguous</strong>, which matters because what they are run
     * against is a language model's output and nobody controls its shape.
     *
     * <p>The separator used to be two quantifiers over overlapping classes with an optional between
     * them, so a run of quotes and spaces not ending in a number made the engine try every way of
     * splitting it - super-linear on input nobody chose. One class with one quantifier cannot
     * backtrack at all. The number had the same defect, being ambiguous about where the integer part
     * ended, and is now an alternation with exactly one reading per input.
     */
    private static final String NUMBER = "([-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))";
    private static final String SEPARATOR = "[\"'\\s=:]*";
    private static final Pattern ENERGY = Pattern.compile("energy" + SEPARATOR + NUMBER,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WARMTH = Pattern.compile("warmth" + SEPARATOR + NUMBER,
            Pattern.CASE_INSENSITIVE);

    /** Enough for the object and no more, so a model that starts explaining is cut off rather than obeyed. */
    private static final int MAX_TOKENS = 60;

    private final ChatModel model;

    public MoodReader(ChatModel model) {
        this.model = model;
    }

    /**
     * Reads the room.
     *
     * <p>Never throws: this runs after the bot has already answered, so a failure here must cost nothing more
     * than the mood staying where it was.
     *
     * @param turns the recent turns, oldest first; only the last {@value #MAX_TURNS} are shown
     * @return where the model says the mood is, or empty when it did not answer usefully
     */
    public Optional<Reading> read(List<Turn> turns) {
        if (turns == null || turns.isEmpty()) {
            return Optional.empty();
        }
        List<ChatModel.Message> messages = List.of(
                ChatModel.Message.system(INSTRUCTIONS),
                ChatModel.Message.user("The conversation:\n" + transcript(turns)));
        try {
            ChatModel.Answer answer = model.reply(messages, List.of(), MAX_TOKENS);
            Optional<Reading> reading = parse(answer.content());
            if (reading.isEmpty()) {
                logger.debug("The model did not read the mood usefully: {}", answer.content());
            }
            return reading;
        } catch (RuntimeException e) {
            logger.debug("Could not read the mood: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** The turns as data, attributed, exactly as the conversation prompt quotes them. */
    private static String transcript(List<Turn> turns) {
        int from = Math.max(0, turns.size() - MAX_TURNS);
        List<String> lines = new ArrayList<>();
        for (Turn turn : turns.subList(from, turns.size())) {
            lines.add("\"" + turn.speaker() + "\" said: " + turn.text());
        }
        return String.join("\n", lines);
    }

    /**
     * Pulls the two numbers out of whatever came back.
     *
     * <p>JSON first, since that is what was asked for and what the models honour; the flat
     * {@code energy=... warmth=...} form is accepted because a model occasionally answers it instead, and
     * refusing it would throw away a perfectly good reading.
     */
    static Optional<Reading> parse(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        Optional<Reading> fromJson = fromJson(content);
        if (fromJson.isPresent()) {
            return fromJson;
        }
        Matcher energy = ENERGY.matcher(content);
        Matcher warmth = WARMTH.matcher(content);
        if (energy.find() && warmth.find()) {
            return reading(parseNumber(energy.group(1)), parseNumber(warmth.group(1)));
        }
        return Optional.empty();
    }

    private static Optional<Reading> fromJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            JsonObject json = JsonParser.parseString(content.substring(start, end + 1)).getAsJsonObject();
            if (!json.has("energy") || !json.has("warmth")) {
                return Optional.empty();
            }
            return reading(json.get("energy").getAsDouble(), json.get("warmth").getAsDouble());
        } catch (JsonParseException | IllegalStateException | NumberFormatException
                 | UnsupportedOperationException e) {
            return Optional.empty();
        }
    }

    private static double parseNumber(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static Optional<Reading> reading(double energy, double warmth) {
        if (Double.isNaN(energy) || Double.isNaN(warmth)) {
            return Optional.empty();
        }
        return Optional.of(new Reading(energy, warmth));
    }

    /**
     * Where the model says the mood is, on the two axes {@link Mood} uses.
     *
     * <p>Clamped on construction: a model asked for a number between -1 and 1 will sometimes answer 5.
     *
     * @param energy calm or sad (-1) to excited (+1)
     * @param warmth cold or hostile (-1) to friendly (+1)
     */
    public record Reading(double energy, double warmth) {

        public Reading {
            energy = Math.clamp(energy, -1, 1);
            warmth = Math.clamp(warmth, -1, 1);
        }

        /**
         * How far to move a mood towards this reading.
         *
         * <p>A fraction rather than the whole distance, which is what keeps the existing design honest: a mood
         * moves gradually, so several readings agreeing shift it while one outlier does not swing the bot.
         *
         * @param current the mood as it stands, already decayed
         * @param weight  how much of the distance to cover, 0 to 1
         * @return the energy and warmth deltas to nudge by
         */
        public double[] deltasFrom(Mood current, double weight) {
            double fraction = Math.clamp(weight, 0, 1);
            return new double[]{
                    (energy - current.energy()) * fraction,
                    (warmth - current.warmth()) * fraction};
        }
    }
}
