package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The socket itself, against a real WebSocket server on a loopback port.
 *
 * <p>This class was left untested on the grounds that it is too thin to be worth it, and that is exactly how
 * a whole provider came to fail in silence: Google sends every frame as <em>binary</em>, this client
 * implemented only {@code onText}, and the result was a conversation that opened, stayed open and never
 * spoke — with nothing in any log. Every test below would have caught it.
 *
 * <p>The fragmentation cases are the other half. A realtime audio frame is far too large to arrive in one
 * piece, and both the text and the binary path have to put it back together — the binary one from
 * <em>bytes</em>, because a UTF-8 character can straddle the boundary between two fragments.
 */
@Timeout(20)
class RealtimeSessionTest {

    private LoopbackWebSocket server;
    private HttpClient http;
    private RealtimeSession session;
    private final List<RealtimeProtocol.Event> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = new LoopbackWebSocket();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (session != null) {
            session.close();
        }
        server.close();
    }

    private void open(RealtimeProtocol protocol) throws InterruptedException {
        session = RealtimeSession.open(http, protocol, server.url(), "sk-test", events::add);
        server.awaitConnection();
    }

    /** Polls rather than sleeps: the frame arrives on the socket's own thread. */
    private void waitFor(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        fail("timed out; events so far: " + events + ", server received: " + server.received());
    }

    private <T> T firstOf(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).findFirst()
                .orElseGet(() -> fail("no " + type.getSimpleName() + " in " + events));
    }

    // ---------------------------------------------------------------- text, which is OpenAI's shape

    @Test
    void aTextFrameIsParsedAndForwarded() throws Exception {
        open(new OpenAiRealtime());

        server.sendText("{\"type\":\"input_audio_buffer.speech_started\"}");

        waitFor(() -> events.stream().anyMatch(RealtimeProtocol.Event.SpeechStarted.class::isInstance));
    }

    @Test
    void aTextFrameArrivingInPiecesIsPutBackTogether() throws Exception {
        // A realtime audio delta is far too big to arrive in one piece, so this is the normal case and not
        // an edge one: parsing each fragment on its own fails on every long frame.
        open(new OpenAiRealtime());

        server.sendFragmented(0x1,
                "{\"type\":\"conversation.item.input_audio_transcription.completed\","
                        + "\"transcript\":\"il fait vraiment beau aujourd'hui\"}", 6);

        waitFor(() -> events.stream()
                .anyMatch(RealtimeProtocol.Event.HeardFromSomebody.class::isInstance));
        assertEquals("il fait vraiment beau aujourd'hui",
                firstOf(RealtimeProtocol.Event.HeardFromSomebody.class).text());
    }

    // ---------------------------------------------------------------- binary, which is Google's shape

    @Test
    void aBinaryFrameIsParsedAndForwarded() throws Exception {
        // THE regression. Google sends everything this way; a client with onText alone hears nothing at all
        // from it, and reports no error either.
        open(new GeminiRealtime("gemini-2.5-flash-native-audio-latest"));

        server.sendBinary("{\"serverContent\":{\"inputTranscription\":{\"text\":\"quelle heure\"}}}");

        waitFor(() -> events.stream().anyMatch(RealtimeProtocol.Event.HeardDelta.class::isInstance));
        assertEquals("quelle heure", firstOf(RealtimeProtocol.Event.HeardDelta.class).text());
    }

    @Test
    void aBinaryFrameArrivingInPiecesIsPutBackTogetherFromBytes() throws Exception {
        // Split on bytes, so a multi-byte character lands across the boundary: accumulating decoded text
        // instead of bytes corrupts it, and the corruption is one character deep and easy to miss.
        open(new GeminiRealtime("gemini-2.5-flash-native-audio-latest"));

        server.sendFragmented(0x2,
                "{\"serminiContent\":1,\"serverContent\":{\"inputTranscription\":"
                        + "{\"text\":\"à Noël, où est passé l'été ?\"}}}", 9);

        waitFor(() -> events.stream().anyMatch(RealtimeProtocol.Event.HeardDelta.class::isInstance));
        assertEquals("à Noël, où est passé l'été ?",
                firstOf(RealtimeProtocol.Event.HeardDelta.class).text());
    }

    @Test
    void oneBinaryFrameSayingThreeThingsForwardsThreeEvents() throws Exception {
        // parseAll, through the socket: returning only the first would mean a turn that is never recorded.
        open(new GeminiRealtime("gemini-2.5-flash-native-audio-latest"));
        String voice = java.util.Base64.getEncoder().encodeToString(new byte[480]);

        server.sendBinary("{\"serverContent\":{"
                + "\"modelTurn\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/pcm;rate=24000\","
                + "\"data\":\"" + voice + "\"}}]},"
                + "\"outputTranscription\":{\"text\":\"bonjour\"},\"turnComplete\":true}}");

        waitFor(() -> events.size() >= 3);
        assertInstanceOf(RealtimeProtocol.Event.AudioDelta.class, events.get(0));
        assertInstanceOf(RealtimeProtocol.Event.TranscriptDelta.class, events.get(1));
        assertInstanceOf(RealtimeProtocol.Event.ResponseDone.class, events.get(2));
    }

    // ---------------------------------------------------------------- the other direction, and failure

    @Test
    void whatIsSentReachesTheServerUnchanged() throws Exception {
        open(new OpenAiRealtime());

        session.send("{\"type\":\"response.create\"}");

        waitFor(() -> !server.received().isEmpty());
        assertEquals("{\"type\":\"response.create\"}", server.received().get(0));
    }

    @Test
    void aFrameTooLargeForOneLengthByteStillArrives() throws Exception {
        // 16-bit and 64-bit length encodings are a client concern too; an audio append is well past 125.
        open(new OpenAiRealtime());

        String big = "{\"type\":\"input_audio_buffer.append\",\"audio\":\"" + "A".repeat(5_000) + "\"}";
        session.send(big);

        waitFor(() -> !server.received().isEmpty());
        assertEquals(big, server.received().get(0));
    }

    @Test
    void theServiceHangingUpIsReportedAsAFailure() throws Exception {
        // Otherwise the bot simply stops answering, with the reason nowhere a user can see it.
        open(new OpenAiRealtime());

        server.hangUp(1011, "deadline expired");

        waitFor(() -> events.stream().anyMatch(RealtimeProtocol.Event.Failure.class::isInstance));
        assertTrue(firstOf(RealtimeProtocol.Event.Failure.class).message().contains("1011"),
                firstOf(RealtimeProtocol.Event.Failure.class).message());
    }

    @Test
    void sendingOnAClosedSessionIsIgnoredRatherThanThrown() throws Exception {
        // The audio thread is a caller here, and it must not be the one to discover the socket died.
        open(new OpenAiRealtime());
        session.close();

        assertDoesNotThrow(() -> session.send("{\"type\":\"response.create\"}"));
        assertFalse(session.isOpen());
    }

    @Test
    void closingTwiceIsHarmless() throws Exception {
        open(new OpenAiRealtime());

        session.close();

        assertDoesNotThrow(() -> session.close());
    }

    @Test
    void aFrameThatMakesTheDialectThrowDoesNotKillTheSocket() throws Exception {
        // A bug in parsing must not take the connection with it: the next frame still has to arrive.
        open(new ExplodingOnBoom());

        server.sendText("{\"type\":\"boom\"}");
        server.sendText("{\"type\":\"input_audio_buffer.speech_started\"}");

        waitFor(() -> events.stream().anyMatch(RealtimeProtocol.Event.SpeechStarted.class::isInstance));
    }

    @Test
    void theDialectDecidesHowTheHandshakeAuthenticates() throws Exception {
        // One service wants a bearer header, the other the key in the URL; the session asks rather than
        // assumes, which is what let a second provider arrive without touching this class.
        assertEquals(Map.of("Authorization", "Bearer sk-test"), new OpenAiRealtime().headers("sk-test"));
        assertEquals(Map.of(), new GeminiRealtime("m").headers("sk-test"));

        open(new OpenAiRealtime());
        assertTrue(session.isOpen());
    }

    @Test
    void aUrlNothingIsListeningOnFailsWithSomethingAUserCanRead() {
        RealtimeException thrown = assertThrows(RealtimeException.class, () -> RealtimeSession.open(
                http, new OpenAiRealtime(), "ws://127.0.0.1:1/realtime", "sk-test", events::add));

        assertTrue(thrown.getMessage().contains("realtime connection"), thrown.getMessage());
    }

    @Test
    void aUrlThatIsNotOneIsRefusedBeforeAnySocketIsOpened() {
        assertThrows(RealtimeException.class, () -> RealtimeSession.open(
                http, new OpenAiRealtime(), "not a url at all", "sk-test", events::add));
    }
    /**
     * A dialect that throws on one frame, by delegation because the real ones are final.
     *
     * <p>Only {@code parseAll} misbehaves; everything else is the OpenAI dialect, so the test exercises the
     * real handshake and the real frames.
     */
    private static final class ExplodingOnBoom implements RealtimeProtocol {

        private final OpenAiRealtime real = new OpenAiRealtime();

        @Override
        public List<Event> parseAll(String frame) {
            if (frame.contains("boom")) {
                throw new IllegalStateException("boom");
            }
            return real.parseAll(frame);
        }

        @Override
        public Event parse(String frame) {
            return real.parse(frame);
        }

        @Override
        public int inputSampleRate() {
            return real.inputSampleRate();
        }

        @Override
        public String session(SessionConfig config) {
            return real.session(config);
        }

        @Override
        public String appendAudio(fr.farmvivi.fluxcord.api.audio.PcmAudio audio) {
            return real.appendAudio(audio);
        }

        @Override
        public String commitAudio() {
            return real.commitAudio();
        }

        @Override
        public String createResponse() {
            return real.createResponse();
        }

        @Override
        public String cancelResponse() {
            return real.cancelResponse();
        }

        @Override
        public String userText(String text) {
            return real.userText(text);
        }

        @Override
        public String userAsked(String text) {
            return real.userAsked(text);
        }

        @Override
        public String toolResult(
                fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel.ToolCall call, String output) {
            return real.toolResult(call, output);
        }

        @Override
        public Map<String, String> headers(String apiKey) {
            return real.headers(apiKey);
        }

        @Override
        public String endpoint(String url, String apiKey) {
            return real.endpoint(url, apiKey);
        }
    }

}
