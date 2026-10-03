package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;
import fr.farmvivi.fluxcord.plugins.aiaudio.conversation.ToolSource;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.Mood;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.Persona;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a full-duplex conversation does with each event.
 *
 * <p>The socket is replaced by a recording {@link RealtimeLink}, so everything that matters is observable:
 * whether the bot shuts up when somebody talks over it, whether a tool result goes back with the right id,
 * whether a turn is attributed to the person who actually said it, and whether a broken connection is reported
 * once. What this cannot show is that OpenAI sends these events under these names — that is
 * {@link RealtimeProtocolTest}'s subject, and it is unverified against the real service either way.
 */
class RealtimeConversationTest {

    private static final long NOW = 2_000_000L;

    /** Everything the conversation sent, in order. */
    private final List<String> sent = new ArrayList<>();
    private final List<PcmAudio> played = new ArrayList<>();
    private final List<Turn> remembered = new ArrayList<>();
    private final List<String> reported = new ArrayList<>();
    private final AtomicInteger stops = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();

    private RealtimeConversation conversation;
    private Consumer<RealtimeProtocol.Event> events;

    /** A link that records frames and stays open until closed. */
    private final class RecordingLink implements RealtimeLink {
        private boolean open = true;

        @Override
        public void send(String frame) {
            sent.add(frame);
        }

        @Override
        public void close() {
            open = false;
            closes.incrementAndGet();
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }

    private static PersonaSnapshot snapshot() {
        ConversationContext conversation = new ConversationContext("g1", "My Server", "c1", "General",
                List.of(), List.of(), List.of());
        return new PersonaSnapshot(new Persona("Fluxcord", List.of(), "neutre", Locale.FRANCE, ""),
                Mood.neutral(NOW), conversation,
                List.of(new PersonaSnapshot.Acquaintance("u1", "Victor",
                        PersonaSnapshot.Acquaintance.Level.REGULAR, 10)));
    }

    /** A tool group that records what it ran. */
    private final List<String> ran = new ArrayList<>();

    private final ToolSource tools = new ToolSource() {
        @Override
        public List<ChatModel.Tool> declarations() {
            return List.of(new ChatModel.Tool("search_the_web", "Search the web", Map.of()));
        }

        @Override
        public boolean handles(String name) {
            return "search_the_web".equals(name);
        }

        @Override
        public String execute(ChatModel.ToolCall call, PersonaSnapshot snapshot, long nowMs) {
            ran.add(call.name() + " " + call.arguments());
            return "it says Spain won";
        }
    };

    @BeforeEach
    void setUp() {
        conversation = new RealtimeConversation(
                LoggerFactory.getLogger(RealtimeConversationTest.class), new OpenAiRealtime(),
                () -> NOW, List.of(tools),
                played::add, stops::incrementAndGet, remembered::add, reported::add);
        conversation.start(snapshot(), "You are Fluxcord.", "marin", "bot-1", sink -> {
            events = sink;
            return new RecordingLink();
        });
        sent.clear();
    }

    private static JsonObject frame(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private List<String> types() {
        return sent.stream().map(f -> frame(f).get("type").getAsString()).toList();
    }

    private static PcmAudio audio(int bytes) {
        return new PcmAudio(new byte[bytes], OpenAiRealtime.SAMPLE_RATE, 1);
    }

    private static String audioDelta(PcmAudio audio) {
        return "{\"type\":\"response.output_audio.delta\",\"delta\":\""
                + Base64.getEncoder().encodeToString(audio.samples()) + "\"}";
    }

    @Test
    void openingTheConversationDeclaresThePersonaAndTheTools() {
        // setUp cleared the list, so reopen to look at the opening frame.
        sent.clear();
        conversation.start(snapshot(), "You are Fluxcord.", "marin", "bot-1",
                sink -> new RecordingLink());

        JsonObject session = frame(sent.get(0)).getAsJsonObject("session");
        assertEquals("session.update", frame(sent.get(0)).get("type").getAsString());
        assertEquals("You are Fluxcord.", session.get("instructions").getAsString());
        assertEquals("search_the_web",
                session.getAsJsonArray("tools").get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void theFirstChunkFromSomebodyNamesThemAndLaterChunksDoNot() {
        conversation.hear("u1", "Victor", audio(480));
        conversation.hear("u1", "Victor", audio(480));

        assertEquals(List.of("conversation.item.create", "input_audio_buffer.append",
                "input_audio_buffer.append"), types(),
                "named once, then just audio: " + types());
    }

    @Test
    void theSpeakerIsNamedAgainWhenSomebodyElseTakesOver() {
        // The single input buffer is the one thing that would lose attribution; this is what keeps it.
        conversation.hear("u1", "Victor", audio(480));
        conversation.hear("u2", "Alice", audio(480));
        conversation.hear("u1", "Victor", audio(480));

        assertEquals(3, types().stream().filter("conversation.item.create"::equals).count(),
                "once per change of speaker: " + types());
    }

    @Test
    void audioFromTheServiceIsPlayed() {
        PcmAudio voice = audio(960);

        events.accept(new OpenAiRealtime().parse(audioDelta(voice)));

        assertEquals(List.of(voice), played);
    }

    @Test
    void somebodyTalkingOverTheBotStopsItOnBothSides() {
        // Cancelling without clearing would leave the bot finishing a sentence the service has abandoned.
        events.accept(new OpenAiRealtime().parse(audioDelta(audio(960))));

        events.accept(new OpenAiRealtime().parse("{\"type\":\"input_audio_buffer.speech_started\"}"));

        assertTrue(types().contains("response.cancel"), types().toString());
        assertEquals(1, stops.get(), "and what was queued here is dropped");
    }

    @Test
    void whatTheBotSaidIsRememberedByItsWordsWhenTheAnswerEnds() {
        events.accept(new OpenAiRealtime().parse(
                "{\"type\":\"response.output_audio_transcript.delta\",\"delta\":\"il est \"}"));
        events.accept(new OpenAiRealtime().parse(
                "{\"type\":\"response.output_audio_transcript.delta\",\"delta\":\"six heures\"}"));

        events.accept(new OpenAiRealtime().parse("{\"type\":\"response.done\"}"));

        assertEquals(1, remembered.size());
        assertEquals("il est six heures", remembered.get(0).text(), "the deltas are one sentence");
        assertEquals("bot-1", remembered.get(0).userId(), "attributed to the bot");
        assertEquals("Fluxcord", remembered.get(0).speaker());
    }

    @Test
    void anInterruptedAnswerKeepsWhatWasActuallySaid() {
        events.accept(new OpenAiRealtime().parse(
                "{\"type\":\"response.output_audio_transcript.delta\",\"delta\":\"alors en fait\"}"));

        events.accept(new OpenAiRealtime().parse("{\"type\":\"input_audio_buffer.speech_started\"}"));

        assertEquals(1, remembered.size(), "cut off, but it did say that much");
        assertEquals("alors en fait", remembered.get(0).text());
    }

    @Test
    void anAnswerWithNoWordsInItIsNotRemembered() {
        events.accept(new OpenAiRealtime().parse("{\"type\":\"response.done\"}"));

        assertTrue(remembered.isEmpty());
    }

    @Test
    void whatSomebodySaidIsAttributedToWhoeverWasSpeaking() {
        conversation.hear("u1", "Victor", audio(480));

        events.accept(new OpenAiRealtime().parse(
                "{\"type\":\"conversation.item.input_audio_transcription.completed\","
                        + "\"transcript\":\"quelle heure il est ?\"}"));

        assertEquals(1, remembered.size());
        assertEquals("u1", remembered.get(0).userId());
        assertEquals("Victor", remembered.get(0).speaker(),
                "the name the context showed, so the memory reads the same either way");
        assertEquals("quelle heure il est ?", remembered.get(0).text());
    }

    @Test
    void aToolIsRunAndItsResultGoesBackWithTheIdTheModelUsed() {
        events.accept(new OpenAiRealtime().parse(
                "{\"type\":\"response.function_call_arguments.done\",\"call_id\":\"call_9\","
                        + "\"name\":\"search_the_web\",\"arguments\":\"{}\"}"));

        assertEquals(List.of("search_the_web {}"), ran);
        JsonObject result = frame(sent.get(sent.size() - 2)).getAsJsonObject("item");
        assertEquals("function_call_output", result.get("type").getAsString());
        assertEquals("call_9", result.get("call_id").getAsString());
        assertEquals("it says Spain won", result.get("output").getAsString());
        assertEquals("response.create", frame(sent.get(sent.size() - 1)).get("type").getAsString(),
                "and the answer is asked to continue");
    }

    @Test
    void aToolNobodyOwnsIsAnsweredRatherThanLeavingTheModelWaiting() {
        events.accept(new OpenAiRealtime().parse(
                "{\"type\":\"response.function_call_arguments.done\",\"call_id\":\"c\","
                        + "\"name\":\"order_a_pizza\",\"arguments\":\"{}\"}"));

        assertTrue(ran.isEmpty());
        assertTrue(frame(sent.get(sent.size() - 2)).getAsJsonObject("item").get("output").getAsString()
                .contains("no tool called order_a_pizza"));
    }

    @Test
    void aBrokenConnectionIsReportedOnceAndClosed() {
        events.accept(new OpenAiRealtime().parse("{\"type\":\"error\",\"error\":{\"message\":\"quota gone\"}}"));
        events.accept(new OpenAiRealtime().parse("{\"type\":\"error\",\"error\":{\"message\":\"again\"}}"));

        assertEquals(List.of("quota gone"), reported, "once, not once a frame");
        assertEquals(1, closes.get());
        assertFalse(conversation.isOpen());
    }

    @Test
    void nothingIsSentOnceTheConversationIsClosed() {
        conversation.close();
        sent.clear();

        conversation.hear("u1", "Victor", audio(480));

        assertTrue(sent.isEmpty());
    }

    @Test
    void closingTwiceIsHarmlessAndSoIsClosingOneThatNeverOpened() {
        assertDoesNotThrow(() -> {
            conversation.close();
            conversation.close();
            new RealtimeConversation(LoggerFactory.getLogger("x"), new OpenAiRealtime(), () -> NOW, List.of(),
                    played::add, stops::incrementAndGet, remembered::add, reported::add).close();
        });
    }

    @Test
    void silenceIsNotSent() {
        conversation.hear("u1", "Victor", null);
        conversation.hear("u1", "Victor", new PcmAudio(new byte[0], OpenAiRealtime.SAMPLE_RATE, 1));

        assertTrue(sent.isEmpty());
    }
}
