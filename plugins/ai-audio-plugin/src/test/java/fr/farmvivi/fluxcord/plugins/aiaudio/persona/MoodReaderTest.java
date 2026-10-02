package fr.farmvivi.fluxcord.plugins.aiaudio.persona;

import fr.farmvivi.fluxcord.plugins.aiaudio.ai.AiRequestException;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading the room.
 *
 * <p>The model writes the answer, so the parsing is the load-bearing part: JSON is what it is asked for and
 * what it mostly produces, the flat form is what it produces otherwise, and anything else has to leave the
 * mood exactly where it was rather than throwing — this runs after the bot has already spoken, so a failure
 * here must cost nothing.
 */
class MoodReaderTest {

    private static final long NOW = 2_000_000L;

    private final AtomicReference<List<ChatModel.Message>> asked = new AtomicReference<>();

    private MoodReader readerAnswering(String content) {
        return new MoodReader((messages, tools, maxTokens) -> {
            asked.set(messages);
            return ChatModel.Answer.spoken(content);
        });
    }

    private static Turn said(String speaker, String text) {
        return new Turn(NOW, "u1", speaker, "g1", "My Server", "c1", "General", text);
    }

    private static final List<Turn> A_CONVERSATION = List.of(
            said("Victor", "mon chat est mort hier soir"),
            said("Alice", "oh non, je suis vraiment désolée"));

    @Test
    void theJsonTheModelWasAskedForIsRead() {
        Optional<MoodReader.Reading> reading =
                readerAnswering("{\"energy\": -0.6, \"warmth\": 0.4}").read(A_CONVERSATION);

        assertTrue(reading.isPresent());
        assertEquals(-0.6, reading.get().energy(), 1e-9);
        assertEquals(0.4, reading.get().warmth(), 1e-9);
    }

    @Test
    void jsonWithTheModelTalkingAroundItIsStillRead() {
        // Asked for "one JSON object and nothing else", a model will still sometimes introduce it.
        Optional<MoodReader.Reading> reading = readerAnswering(
                "Voici mon analyse :\n```json\n{\"energy\": 0.8, \"warmth\": -0.9}\n```\nVoilà.")
                .read(A_CONVERSATION);

        assertTrue(reading.isPresent());
        assertEquals(0.8, reading.get().energy(), 1e-9);
        assertEquals(-0.9, reading.get().warmth(), 1e-9);
    }

    @Test
    void theFlatFormIsAcceptedBecauseModelsSometimesAnswerItInstead() {
        Optional<MoodReader.Reading> reading =
                readerAnswering("energy=+0.5 warmth=-0.25").read(A_CONVERSATION);

        assertTrue(reading.isPresent());
        assertEquals(0.5, reading.get().energy(), 1e-9);
        assertEquals(-0.25, reading.get().warmth(), 1e-9);
    }

    @Test
    void theMinusSignSurvivesEverySpellingOfTheSeparator() {
        // It did not: a "few non-digits" separator swallowed the sign, and a mood reading of -0.25 became a
        // cheerful +0.25 - the exact failure this whole feature exists to fix, reintroduced by a regex.
        for (String content : List.of("energy=-0.8 warmth=-0.4", "energy: -0.8, warmth: -0.4",
                "energy -0.8 warmth -0.4", "\"energy\": -0.8 and \"warmth\": -0.4")) {
            MoodReader.Reading reading = readerAnswering(content).read(A_CONVERSATION).orElseThrow(
                    () -> new AssertionError("not read: " + content));
            assertEquals(-0.8, reading.energy(), 1e-9, content);
            assertEquals(-0.4, reading.warmth(), 1e-9, content);
        }
    }

    @Test
    void anAnswerWithNoNumbersInItLeavesTheMoodAlone() {
        for (String content : List.of("", "   ", "je ne sais pas", "{}", "{\"energy\": 0.5}",
                "{\"energy\": \"beaucoup\", \"warmth\": \"un peu\"}", "{ not json at all")) {
            assertTrue(readerAnswering(content).read(A_CONVERSATION).isEmpty(), content);
        }
    }

    @Test
    void aModelFailureIsNotAFailureOfTheTurn() {
        MoodReader reader = new MoodReader((messages, tools, maxTokens) -> {
            throw new AiRequestException("the server is down");
        });

        assertTrue(assertDoesNotThrow(() -> reader.read(A_CONVERSATION)).isEmpty());
    }

    @Test
    void nothingIsAskedAboutAnEmptyConversation() {
        MoodReader reader = readerAnswering("{\"energy\": 1, \"warmth\": 1}");

        assertTrue(reader.read(List.of()).isEmpty());
        assertTrue(reader.read(null).isEmpty());
        assertNull(asked.get(), "no request was made");
    }

    @Test
    void aNumberOutsideTheAxisIsClampedRatherThanRefused() {
        // A model asked for a number between -1 and 1 will sometimes answer 5.
        MoodReader.Reading reading =
                readerAnswering("{\"energy\": 5, \"warmth\": -42}").read(A_CONVERSATION).orElseThrow();

        assertEquals(1, reading.energy(), 1e-9);
        assertEquals(-1, reading.warmth(), 1e-9);
    }

    @Test
    void theConversationIsSentAsDataAndTheInstructionIsTheOnlySystemMessage() {
        readerAnswering("{\"energy\": 0, \"warmth\": 0}").read(A_CONVERSATION);

        List<ChatModel.Message> messages = asked.get();
        assertEquals(2, messages.size());
        assertEquals(ChatModel.Role.SYSTEM, messages.get(0).role());
        assertTrue(messages.get(0).content().contains("You read the mood"));
        assertEquals(ChatModel.Role.USER, messages.get(1).role(),
                "what people said is user content here as everywhere else");
        assertTrue(messages.get(1).content().contains("\"Victor\" said: mon chat est mort hier soir"));
    }

    @Test
    void onlyTheRecentTurnsAreShown() {
        // The mood is about the last minute of conversation, not its whole history.
        List<Turn> many = new ArrayList<>();
        for (int i = 0; i < MoodReader.MAX_TURNS + 5; i++) {
            many.add(said("Victor", "phrase " + i));
        }

        readerAnswering("{\"energy\": 0, \"warmth\": 0}").read(many);

        String transcript = asked.get().get(1).content();
        assertEquals(MoodReader.MAX_TURNS, transcript.lines().filter(l -> l.contains("said:")).count());
        assertTrue(transcript.contains("phrase " + (many.size() - 1)), "the latest ones");
        assertFalse(transcript.contains("phrase 0"), "not the oldest");
    }

    @Test
    void aReadingMovesTheMoodPartOfTheWayNotAllOfIt() {
        // One outlier must not swing the bot; readings that agree still get there in a few turns.
        Mood current = new Mood(0, 0, NOW);
        MoodReader.Reading reading = new MoodReader.Reading(1, -1);

        double[] half = reading.deltasFrom(current, 0.5);

        assertEquals(0.5, half[0], 1e-9);
        assertEquals(-0.5, half[1], 1e-9);
        assertEquals(1, current.nudged(half[0], half[1], NOW).energy(), 0.5 + 1e-9,
                "and it moves towards the reading, never past it");
    }

    @Test
    void aWeightOfZeroFreezesTheMoodAndOneObeysTheReadingExactly() {
        Mood current = new Mood(-0.2, 0.6, NOW);
        MoodReader.Reading reading = new MoodReader.Reading(0.8, -0.4);

        assertArrayEquals(new double[]{0, 0}, reading.deltasFrom(current, 0), 1e-9);

        double[] full = reading.deltasFrom(current, 1);
        assertEquals(0.8, current.nudged(full[0], full[1], NOW).energy(), 1e-9);
        assertEquals(-0.4, current.nudged(full[0], full[1], NOW).warmth(), 1e-9);
    }

    @Test
    void aWeightOutsideZeroToOneIsClamped() {
        Mood current = new Mood(0, 0, NOW);
        MoodReader.Reading reading = new MoodReader.Reading(1, 1);

        assertArrayEquals(new double[]{1, 1}, reading.deltasFrom(current, 5), 1e-9);
        assertArrayEquals(new double[]{0, 0}, reading.deltasFrom(current, -5), 1e-9);
    }
}
