package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Google's Live API dialect, frame by frame.
 *
 * <p>This is where the risk of the second provider lives: the conversation logic did not change, so what can
 * still be wrong is the spelling of a field, and only a test that reads the JSON catches that. Nothing here
 * has been run against the real service — these tests pin the protocol as published, which is the most that
 * can be verified from this repository, and they are the first thing to re-read if it misbehaves.
 *
 * <p>Four of them exist because of a difference from OpenAI that could silently half-work: the rates differ
 * per direction, the model is named in the frame, the key goes in the URL, and one frame says several things.
 */
class GeminiRealtimeTest {

    private static final ChatModel.Tool A_TOOL = new ChatModel.Tool("search_the_web", "Search the web",
            new LinkedHashMap<>(Map.of("query", ChatModel.Tool.Parameter.requiredString("what to look for"))));

    private final GeminiRealtime gemini = new GeminiRealtime("gemini-3.8-live");

    private static JsonObject frame(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ---------------------------------------------------------------- the setup frame

    @Test
    void theModelIsNamedInTheFrameBecauseThisApiDoesNotTakeItInTheUrl() {
        JsonObject setup = frame(gemini.session("You are Fluxcord.", "Puck", List.of()))
                .getAsJsonObject("setup");

        assertEquals("models/gemini-3.8-live", setup.get("model").getAsString());
    }

    @Test
    void theResourcePrefixIsAddedWhenItWasLeftOutAndNotDoubled() {
        assertEquals("models/x", new GeminiRealtime("x").model());
        assertEquals("models/x", new GeminiRealtime("models/x").model());
        assertEquals("models/x", new GeminiRealtime("  models/x  ").model());
    }

    @Test
    void theSessionAsksForSpeechAndNamesTheVoiceWhereThisApiPutsIt() {
        JsonObject setup = frame(gemini.session("You are Fluxcord.", "Puck", List.of()))
                .getAsJsonObject("setup");
        JsonObject config = setup.getAsJsonObject("generationConfig");

        assertEquals("AUDIO", config.getAsJsonArray("responseModalities").get(0).getAsString());
        assertEquals("Puck", config.getAsJsonObject("speechConfig")
                .getAsJsonObject("voiceConfig").getAsJsonObject("prebuiltVoiceConfig")
                .get("voiceName").getAsString());
    }

    @Test
    void theInstructionsTravelAsAContentObject() {
        JsonObject setup = frame(gemini.session("You are Fluxcord.", "Puck", List.of()))
                .getAsJsonObject("setup");

        assertEquals("You are Fluxcord.", setup.getAsJsonObject("systemInstruction")
                .getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void bothSidesAreTranscribedBecauseTheMemoryKeepsWords() {
        // Not optional for this plugin: a session that only exchanged audio would leave nothing behind.
        JsonObject setup = frame(gemini.session("x", "Puck", List.of())).getAsJsonObject("setup");

        assertTrue(setup.has("inputAudioTranscription"));
        assertTrue(setup.has("outputAudioTranscription"));
    }

    @Test
    void toolsGoInOneGroupOfFunctionDeclarations() {
        // One Tool carrying every function, not one entry per function: the other shape is OpenAI's.
        JsonObject setup = frame(gemini.session("x", "Puck", List.of(A_TOOL))).getAsJsonObject("setup");

        JsonObject declaration = setup.getAsJsonArray("tools").get(0).getAsJsonObject()
                .getAsJsonArray("functionDeclarations").get(0).getAsJsonObject();
        assertEquals("search_the_web", declaration.get("name").getAsString());
        assertEquals("object", declaration.getAsJsonObject("parameters").get("type").getAsString());
        assertEquals("query", declaration.getAsJsonObject("parameters")
                .getAsJsonArray("required").get(0).getAsString());
    }

    @Test
    void noVoiceMeansNoSpeechConfigRatherThanAnEmptyOne() {
        JsonObject config = frame(gemini.session("x", "  ", List.of()))
                .getAsJsonObject("setup").getAsJsonObject("generationConfig");

        assertFalse(config.has("speechConfig"));
    }

    // ---------------------------------------------------------------- audio out

    @Test
    void audioIsSentAtSixteenKilohertzWhichIsNotWhatItComesBackAt() {
        // The difference from OpenAI that would be silent: there, both directions are 24 kHz.
        PcmAudio discord = new PcmAudio(new byte[PcmAudio.DISCORD_FRAME_SIZE],
                PcmAudio.DISCORD_SAMPLE_RATE, PcmAudio.DISCORD_CHANNELS);

        JsonObject blob = frame(gemini.appendAudio(discord))
                .getAsJsonObject("realtimeInput").getAsJsonObject("audio");

        assertEquals("audio/pcm;rate=16000", blob.get("mimeType").getAsString());
        assertEquals(GeminiRealtime.INPUT_SAMPLE_RATE, gemini.inputSampleRate());
        byte[] sent = Base64.getDecoder().decode(blob.get("data").getAsString());
        // 20 ms of 16 kHz mono 16-bit: a third of what Discord delivered, resampled and downmixed.
        assertEquals(16_000 / 50 * 2, sent.length);
    }

    @Test
    void audioAlreadyAtTheRightRateIsNotResampledForNothing() {
        PcmAudio ready = new PcmAudio(new byte[320], GeminiRealtime.INPUT_SAMPLE_RATE, 1);

        byte[] sent = Base64.getDecoder().decode(frame(gemini.appendAudio(ready))
                .getAsJsonObject("realtimeInput").getAsJsonObject("audio").get("data").getAsString());

        assertEquals(320, sent.length);
    }

    @Test
    void theEndOfAnUtteranceIsAFlagRatherThanAnEventName() {
        assertTrue(frame(gemini.commitAudio()).getAsJsonObject("realtimeInput")
                .get("audioStreamEnd").getAsBoolean());
    }

    @Test
    void thereIsNothingToSendForAnswerNowOrForCancel() {
        // Both are the server's decision here. An empty frame is how a dialect says so, and the
        // conversation drops it rather than inventing a frame this API does not define.
        assertEquals("", gemini.createResponse());
        assertEquals("", gemini.cancelResponse());
    }

    @Test
    void theSpeakerIsNamedWithoutEndingTheirTurn() {
        JsonObject content = frame(gemini.speakerChanged("Victor")).getAsJsonObject("clientContent");

        JsonObject turn = content.getAsJsonArray("turns").get(0).getAsJsonObject();
        assertEquals("user", turn.get("role").getAsString());
        assertEquals("\"Victor\" is speaking now.",
                turn.getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString());
        // Ending it here would make the model answer the label instead of the sentence that follows.
        assertFalse(content.get("turnComplete").getAsBoolean());
    }

    @Test
    void aToolResultCarriesTheFunctionsNameAndNotOnlyItsId() {
        // The reason toolResult takes the whole call: OpenAI matches on the id alone, this one does not.
        JsonObject response = frame(gemini.toolResult(
                        new ChatModel.ToolCall("call_abc", "search_the_web", "{}"), "it says Spain won"))
                .getAsJsonObject("toolResponse").getAsJsonArray("functionResponses").get(0).getAsJsonObject();

        assertEquals("call_abc", response.get("id").getAsString());
        assertEquals("search_the_web", response.get("name").getAsString());
        assertEquals("it says Spain won", response.getAsJsonObject("response").get("result").getAsString());
    }

    // ---------------------------------------------------------------- the handshake

    @Test
    void theKeyGoesInTheUrlAndNotInAHeader() {
        assertEquals(Map.of(), gemini.headers("AIza-secret"));
        assertEquals(GeminiRealtime.DEFAULT_URL + "?key=AIza-secret",
                gemini.endpoint(GeminiRealtime.DEFAULT_URL, "AIza-secret"));
    }

    @Test
    void aUrlThatAlreadyHasAQueryGainsTheKeyWithAnAmpersand() {
        assertEquals("wss://host/path?x=1&key=k", gemini.endpoint("wss://host/path?x=1", "k"));
    }

    @Test
    void aUrlThatCarriesItsOwnKeyIsLeftAlone() {
        assertEquals("wss://host/path?key=mine", gemini.endpoint("wss://host/path?key=mine", "other"));
    }

    @Test
    void noKeyMeansTheUrlAsConfigured() {
        assertEquals("wss://host/path", gemini.endpoint("wss://host/path", ""));
        assertEquals(GeminiRealtime.DEFAULT_URL, gemini.endpoint("", "   "));
    }

    // ---------------------------------------------------------------- reading frames

    @Test
    void audioComesBackAtTheRateTheFrameDeclares() {
        String voice = Base64.getEncoder().encodeToString(new byte[480]);
        String json = "{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":"
                + "{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"" + voice + "\"}}]}}}";

        var event = assertInstanceOf(RealtimeProtocol.Event.AudioDelta.class, gemini.parse(json));

        assertEquals(24_000, event.audio().sampleRate());
        assertEquals(480, event.audio().samples().length);
    }

    @Test
    void severalAudioPartsOfOneTurnAreJoinedRatherThanLosingAll_butTheFirst() {
        String half = Base64.getEncoder().encodeToString(new byte[100]);
        String json = "{\"serverContent\":{\"modelTurn\":{\"parts\":["
                + "{\"inlineData\":{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"" + half + "\"}},"
                + "{\"inlineData\":{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"" + half + "\"}}]}}}";

        var event = assertInstanceOf(RealtimeProtocol.Event.AudioDelta.class, gemini.parse(json));

        assertEquals(200, event.audio().samples().length, "consecutive samples of the same sentence");
    }

    @Test
    void oneFrameCanSayThreeThingsAndAllThreeAreActedOn() {
        // The reason parseAll exists. Returning only the audio would mean a turn that is never recorded.
        String voice = Base64.getEncoder().encodeToString(new byte[48]);
        String json = "{\"serverContent\":{"
                + "\"modelTurn\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/pcm;rate=24000\","
                + "\"data\":\"" + voice + "\"}}]},"
                + "\"outputTranscription\":{\"text\":\"bonjour\"},"
                + "\"turnComplete\":true}}";

        List<RealtimeProtocol.Event> events = gemini.parseAll(json);

        assertEquals(3, events.size(), String.valueOf(events));
        assertInstanceOf(RealtimeProtocol.Event.AudioDelta.class, events.get(0));
        assertInstanceOf(RealtimeProtocol.Event.TranscriptDelta.class, events.get(1));
        assertInstanceOf(RealtimeProtocol.Event.ResponseDone.class, events.get(2));
    }

    @Test
    void anInterruptionIsReportedFirstBecauseWhatFollowsItWasAbandoned() {
        List<RealtimeProtocol.Event> events = gemini.parseAll(
                "{\"serverContent\":{\"interrupted\":true,\"turnComplete\":true}}");

        assertInstanceOf(RealtimeProtocol.Event.SpeechStarted.class, events.get(0));
        assertInstanceOf(RealtimeProtocol.Event.ResponseDone.class, events.get(1));
    }

    @Test
    void whatSomebodySaidArrivesAsItsOwnTranscription() {
        var event = assertInstanceOf(RealtimeProtocol.Event.HeardFromSomebody.class,
                gemini.parse("{\"serverContent\":{\"inputTranscription\":{\"text\":\"quelle heure\"}}}"));

        assertEquals("quelle heure", event.text());
    }

    @Test
    void aToolCallCarriesItsArgumentsAsTextTheWayEveryToolSourceReadsThem() {
        var event = assertInstanceOf(RealtimeProtocol.Event.ToolCalled.class, gemini.parse(
                "{\"toolCall\":{\"functionCalls\":[{\"id\":\"c1\",\"name\":\"search_the_web\","
                        + "\"args\":{\"query\":\"qui a gagné\"}}]}}"));

        assertEquals("c1", event.call().id());
        assertEquals("search_the_web", event.call().name());
        assertEquals("{\"query\":\"qui a gagné\"}", event.call().arguments());
    }

    @Test
    void severalToolCallsInOneFrameAreAllReported() {
        List<RealtimeProtocol.Event> events = gemini.parseAll(
                "{\"toolCall\":{\"functionCalls\":["
                        + "{\"id\":\"a\",\"name\":\"one\",\"args\":{}},"
                        + "{\"id\":\"b\",\"name\":\"two\",\"args\":{}}]}}");

        assertEquals(2, events.size());
    }

    @Test
    void aCallWithoutArgumentsStillHasAnEmptyObjectToParse() {
        var event = assertInstanceOf(RealtimeProtocol.Event.ToolCalled.class,
                gemini.parse("{\"toolCall\":{\"functionCalls\":[{\"id\":\"c\",\"name\":\"n\"}]}}"));

        assertEquals("{}", event.call().arguments());
    }

    @Test
    void theHandshakeBeingCompleteIsNotSomethingToActOn() {
        assertInstanceOf(RealtimeProtocol.Event.Ignored.class, gemini.parse("{\"setupComplete\":{}}"));
    }

    @Test
    void anythingUnrecognisedIsIgnoredRatherThanTreatedAsBroken() {
        // These APIs gain features; a dialect that fails on an unknown frame breaks every time they do.
        assertInstanceOf(RealtimeProtocol.Event.Ignored.class,
                gemini.parse("{\"sessionResumptionUpdate\":{\"newHandle\":\"h\"}}"));
        assertInstanceOf(RealtimeProtocol.Event.Ignored.class,
                gemini.parse("{\"serverContent\":{\"generationComplete\":true}}"));
    }

    @Test
    void anErrorIsReportedInWordsAUserCouldBeShown() {
        var event = assertInstanceOf(RealtimeProtocol.Event.Failure.class,
                gemini.parse("{\"error\":{\"code\":429,\"message\":\"quota exceeded\"}}"));

        assertEquals("quota exceeded", event.message());
    }

    @Test
    void somethingThatIsNotJsonIsAFailureAndNotAnException() {
        assertInstanceOf(RealtimeProtocol.Event.Failure.class, gemini.parse("<html>502</html>"));
        assertInstanceOf(RealtimeProtocol.Event.Ignored.class, gemini.parse("   "));
    }

    @Test
    void audioThatIsNotBase64IsReportedRatherThanThrown() {
        assertInstanceOf(RealtimeProtocol.Event.Failure.class, gemini.parse(
                "{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":{\"data\":\"!!!\"}}]}}}"));
    }

    @Test
    void aRateTheFrameDoesNotStateFallsBackToWhatTheApiPublishes() {
        assertEquals(GeminiRealtime.OUTPUT_SAMPLE_RATE,
                GeminiRealtime.rateOf("audio/pcm", GeminiRealtime.OUTPUT_SAMPLE_RATE));
        assertEquals(GeminiRealtime.OUTPUT_SAMPLE_RATE,
                GeminiRealtime.rateOf(null, GeminiRealtime.OUTPUT_SAMPLE_RATE));
        assertEquals(GeminiRealtime.OUTPUT_SAMPLE_RATE,
                GeminiRealtime.rateOf("audio/pcm;rate=", GeminiRealtime.OUTPUT_SAMPLE_RATE));
        assertEquals(8_000, GeminiRealtime.rateOf("audio/pcm;rate=8000;channels=1", 24_000));
    }
}
