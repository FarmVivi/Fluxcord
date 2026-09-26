package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two AI clients, against a real HTTP server on a loopback port.
 *
 * <p>A mocked {@code HttpClient} would prove nothing here: what matters is the shape of what goes on the
 * wire — a multipart body a Whisper server will accept, the right JSON field names — and that only a real
 * server can observe. It also means these tests cover the local-server case exactly as they cover the
 * hosted one, since the only difference between the two is this base URL.
 */
class OpenAiClientsTest {

    private HttpServer server;
    private HttpClient http;
    private String baseUrl;

    /** What the last request carried, so the assertions can read it after the call returned. */
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<byte[]> lastBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http = HttpClient.newHttpClient();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        http.close();
    }

    /** Registers a handler answering {@code body} with {@code status}, recording the request. */
    private void answer(String path, int status, String contentType, byte[] body) {
        server.createContext(path, exchange -> {
            record(exchange);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private void record(HttpExchange exchange) throws IOException {
        lastPath.set(exchange.getRequestURI().getPath());
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastBody.set(exchange.getRequestBody().readAllBytes());
    }

    private AiEndpoint endpoint(String apiKey, String model) {
        return new AiEndpoint(baseUrl, apiKey, model, Duration.ofSeconds(5));
    }

    /** Ollama is addressed at the server root: its routes are /api/..., not /v1/api/.... */
    private AiEndpoint ollamaEndpoint(String model) {
        return new AiEndpoint(baseUrl.substring(0, baseUrl.lastIndexOf("/v1")), "", model,
                Duration.ofSeconds(5));
    }

    private static byte[] wav(int sampleRate, int channels, short... samples) {
        ByteBuffer pcm = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) {
            pcm.putShort(sample);
        }
        return new PcmAudio(pcm.array(), sampleRate, channels).toWav();
    }

    // Text to speech

    @Test
    void synthesisAsksForWavAndDecodesWhatComesBack() {
        answer("/v1/audio/speech", 200, "audio/wav", wav(24_000, 1, (short) 11, (short) 22));

        PcmAudio audio = new OpenAiTextToSpeech(endpoint("sk-test", "tts-1"), http)
                .synthesize("bonjour", "alloy");

        assertEquals("/v1/audio/speech", lastPath.get());
        assertEquals("Bearer sk-test", lastAuthorization.get());
        JsonObject sent = JsonParser.parseString(new String(lastBody.get(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("tts-1", sent.get("model").getAsString());
        assertEquals("bonjour", sent.get("input").getAsString());
        assertEquals("alloy", sent.get("voice").getAsString());
        assertEquals("wav", sent.get("response_format").getAsString(),
                "anything else would need a decoder the plugin does not have");
        assertEquals(24_000, audio.sampleRate());
        assertFalse(audio.isEmpty());
    }

    @Test
    void aLocalServerWithoutAKeyGetsNoAuthorizationHeader() {
        // What a Kokoro or openedai-speech container expects: an empty key means no header at all.
        answer("/v1/audio/speech", 200, "audio/wav", wav(22_050, 1, (short) 1));

        new OpenAiTextToSpeech(endpoint("", "kokoro"), http).synthesize("salut", "af_heart");

        assertNull(lastAuthorization.get());
    }

    @Test
    void anAnswerThatIsNotAWavIsReportedAsSuchRatherThanPlayedAsNoise() {
        // A proxy in front of the server, or a server ignoring response_format.
        answer("/v1/audio/speech", 200, "text/html", "<html>gateway</html>".getBytes(StandardCharsets.UTF_8));

        AiRequestException failure = assertThrows(AiRequestException.class,
                () -> new OpenAiTextToSpeech(endpoint("k", "tts-1"), http).synthesize("hi", "alloy"));

        assertTrue(failure.getMessage().contains("usable WAV"), failure.getMessage());
    }

    @Test
    void aProviderErrorIsTurnedIntoItsOwnMessage() {
        answer("/v1/audio/speech", 401,
                "application/json",
                "{\"error\":{\"message\":\"Incorrect API key provided\"}}".getBytes(StandardCharsets.UTF_8));

        AiRequestException failure = assertThrows(AiRequestException.class,
                () -> new OpenAiTextToSpeech(endpoint("wrong", "tts-1"), http).synthesize("hi", "alloy"));

        assertTrue(failure.getMessage().contains("HTTP 401"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Incorrect API key provided"), failure.getMessage());
    }

    @Test
    void anErrorBodyThatIsNotJsonIsTruncatedIntoTheMessage() {
        answer("/v1/audio/speech", 502, "text/html", "x".repeat(5000).getBytes(StandardCharsets.UTF_8));

        AiRequestException failure = assertThrows(AiRequestException.class,
                () -> new OpenAiTextToSpeech(endpoint("k", "tts-1"), http).synthesize("hi", "alloy"));

        assertTrue(failure.getMessage().length() < 400, "the whole page must not end up in a Discord reply");
        assertTrue(failure.getMessage().contains("..."));
    }

    @Test
    void anUnreachableServerSaysWhereItTriedToGo() {
        // The most likely failure in a cluster: the URL points at nothing.
        AiEndpoint dead = new AiEndpoint("http://127.0.0.1:1/v1", "", "tts-1", Duration.ofSeconds(2));

        AiRequestException failure = assertThrows(AiRequestException.class,
                () -> new OpenAiTextToSpeech(dead, http).synthesize("hi", "alloy"));

        assertTrue(failure.getMessage().contains("127.0.0.1"), failure.getMessage());
    }

    @Test
    void speakingNothingIsRefusedBeforeAnyRequestIsMade() {
        OpenAiTextToSpeech tts = new OpenAiTextToSpeech(endpoint("k", "tts-1"), http);

        assertThrows(IllegalArgumentException.class, () -> tts.synthesize("  ", "alloy"));
        assertThrows(IllegalArgumentException.class, () -> tts.synthesize(null, "alloy"));
        assertNull(lastPath.get(), "nothing was sent");
    }

    // Speech to text

    @Test
    void transcriptionSendsAMultipartWavAndReadsTheText() {
        answer("/v1/audio/transcriptions", 200, "application/json",
                "{\"text\":\"  bonjour tout le monde \"}".getBytes(StandardCharsets.UTF_8));
        PcmAudio audio = new PcmAudio(new byte[16_000 * 2], 16_000, 1);

        String text = new OpenAiSpeechToText(endpoint("sk-test", "whisper-1"), http)
                .transcribe(audio, "fr-FR");

        assertEquals("bonjour tout le monde", text, "trimmed");
        String body = new String(lastBody.get(), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("name=\"model\""), body.substring(0, Math.min(400, body.length())));
        assertTrue(body.contains("whisper-1"));
        assertTrue(body.contains("name=\"file\"; filename=\"audio.wav\""));
        assertTrue(body.contains("Content-Type: audio/wav"));
        assertTrue(body.contains("RIFF"), "the audio itself must be a WAV file");
        assertTrue(body.contains("name=\"language\""));
        assertTrue(body.contains("\r\nfr\r\n"), "the region has to be dropped, providers want 'fr'");
    }

    @Test
    void anAutomaticLanguageSendsNoLanguageFieldAtAll() {
        answer("/v1/audio/transcriptions", 200, "application/json",
                "{\"text\":\"hello\"}".getBytes(StandardCharsets.UTF_8));

        new OpenAiSpeechToText(endpoint("", "whisper-1"), http)
                .transcribe(new PcmAudio(new byte[3200], 16_000, 1), "auto");

        assertFalse(new String(lastBody.get(), StandardCharsets.ISO_8859_1).contains("name=\"language\""),
                "letting the provider detect the language is an absent field, not the word 'auto'");
    }

    @Test
    void theLanguageTagIsReducedToWhatProvidersAccept() {
        assertEquals("fr", OpenAiSpeechToText.languageCode("fr-FR"));
        assertEquals("en", OpenAiSpeechToText.languageCode("en_US"));
        assertEquals("de", OpenAiSpeechToText.languageCode("DE"));
        assertEquals("", OpenAiSpeechToText.languageCode("auto"));
        assertEquals("", OpenAiSpeechToText.languageCode(" "));
        assertEquals("", OpenAiSpeechToText.languageCode(null));
    }

    @Test
    void aServerAnsweringPlainTextIsToleratedRatherThanFailing() {
        // whisper.cpp's server answers text/plain unless asked otherwise.
        answer("/v1/audio/transcriptions", 200, "text/plain", " just words ".getBytes(StandardCharsets.UTF_8));

        String text = new OpenAiSpeechToText(endpoint("", "base.en"), http)
                .transcribe(new PcmAudio(new byte[3200], 16_000, 1), "en");

        assertEquals("just words", text);
    }

    @Test
    void transcribingNothingIsRefusedBeforeAnyRequestIsMade() {
        OpenAiSpeechToText stt = new OpenAiSpeechToText(endpoint("k", "whisper-1"), http);
        PcmAudio empty = new PcmAudio(new byte[0], 16_000, 1);

        assertThrows(IllegalArgumentException.class, () -> stt.transcribe(empty, "fr"));
        assertThrows(IllegalArgumentException.class, () -> stt.transcribe(null, "fr"));
        assertNull(lastPath.get());
    }

    // Endpoint

    @Test
    void aBaseUrlIsUsableWithOrWithoutItsTrailingSlash() {
        AiEndpoint slashed = new AiEndpoint("http://host:8000/v1///", "", "m", Duration.ofSeconds(1));

        assertEquals("http://host:8000/v1/audio/speech", slashed.uri("/audio/speech").toString());
    }

    @Test
    void anEndpointNeverPrintsItsApiKey() {
        String printed = new AiEndpoint(baseUrl, "sk-very-secret", "m", Duration.ofSeconds(1)).toString();

        assertFalse(printed.contains("sk-very-secret"), printed);
        assertTrue(printed.contains("<set>"));
        assertTrue(new AiEndpoint(baseUrl, "  ", "m", Duration.ofSeconds(1)).toString().contains("<none>"));
    }

    @Test
    void anUnusableEndpointIsRefusedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiEndpoint("", "", "m", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new AiEndpoint(baseUrl, "", " ", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new AiEndpoint(baseUrl, "", "m", Duration.ZERO));
    }

    // Transcription through Ollama, which has no transcription route

    @Test
    void ollamaTranscriptionPutsTheWavInImagesBecauseThatIsWhereMediaGoes() {
        // The trap this test exists for: an "audio" field is accepted and silently ignored, and the
        // model then answers that it was given nothing to transcribe.
        answer("/api/chat", 200, "application/json",
                "{\"message\":{\"role\":\"assistant\",\"content\":\"  bonjour tout le monde \"}}"
                        .getBytes(StandardCharsets.UTF_8));

        String text = new OllamaSpeechToText(ollamaEndpoint("gemma4:e4b-it-qat"), http)
                .transcribe(new PcmAudio(new byte[16_000 * 2], 16_000, 1), "fr-FR");

        assertEquals("bonjour tout le monde", text);
        assertEquals("/api/chat", lastPath.get());
        JsonObject sent = JsonParser.parseString(new String(lastBody.get(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("gemma4:e4b-it-qat", sent.get("model").getAsString());
        assertFalse(sent.get("stream").getAsBoolean());
        assertFalse(sent.get("think").getAsBoolean(), "a thinking block is pure latency here");
        JsonObject message = sent.getAsJsonArray("messages").get(0).getAsJsonObject();
        assertTrue(message.has("images"), "media travels in 'images', never in 'audio'");
        assertFalse(message.has("audio"));
        assertEquals(1, message.getAsJsonArray("images").size());
        // What is sent is a real WAV, base64-encoded.
        byte[] decoded = java.util.Base64.getDecoder()
                .decode(message.getAsJsonArray("images").get(0).getAsString());
        assertEquals("RIFF", new String(decoded, 0, 4, StandardCharsets.US_ASCII));
        assertTrue(message.get("content").getAsString().contains("fr"),
                "naming the language helps a general-purpose model");
    }

    @Test
    void ollamaTranscriptionLetsTheModelDetectTheLanguageWhenAsked() {
        answer("/api/chat", 200, "application/json",
                "{\"message\":{\"content\":\"hello\"}}".getBytes(StandardCharsets.UTF_8));

        new OllamaSpeechToText(ollamaEndpoint("gemma4:e4b-it-qat"), http)
                .transcribe(new PcmAudio(new byte[3200], 16_000, 1), "auto");

        JsonObject sent = JsonParser.parseString(new String(lastBody.get(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        String instruction = sent.getAsJsonArray("messages").get(0).getAsJsonObject()
                .get("content").getAsString();
        assertFalse(instruction.contains("The audio is in"), "no language is imposed");
    }

    @Test
    void ollamaTranscriptionReportsAnAnswerItCannotRead() {
        answer("/api/chat", 200, "text/html", "<html>not ollama</html>".getBytes(StandardCharsets.UTF_8));

        AiRequestException failure = assertThrows(AiRequestException.class,
                () -> new OllamaSpeechToText(ollamaEndpoint("gemma4"), http)
                        .transcribe(new PcmAudio(new byte[3200], 16_000, 1), "fr"));

        assertTrue(failure.getMessage().contains("did not answer JSON"), failure.getMessage());
    }

    @Test
    void ollamaTranscriptionRefusesEmptyAudioBeforeSendingAnything() {
        OllamaSpeechToText stt = new OllamaSpeechToText(ollamaEndpoint("gemma4"), http);
        PcmAudio empty = new PcmAudio(new byte[0], 16_000, 1);

        assertThrows(IllegalArgumentException.class, () -> stt.transcribe(empty, "fr"));
        assertNull(lastPath.get());
    }

    // Chat, the one client that covers OpenAI and Ollama alike

    private static final String A_REPLY =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"  il est six heures  \"}}]}";

    @Test
    void aChatRequestCarriesTheMessagesInOrderWithTheirRoles() {
        answer("/v1/chat/completions", 200, "application/json", A_REPLY.getBytes(StandardCharsets.UTF_8));

        String reply = new OpenAiChatModel(endpoint("sk-test", "gpt-4o-mini"), http, 0.7, "none")
                .reply(List.of(ChatModel.Message.system("tu es bref"),
                        ChatModel.Message.user("quelle heure ?"),
                        ChatModel.Message.assistant("il est cinq heures"),
                        ChatModel.Message.user("et maintenant ?")), List.of(), 120).content();

        assertEquals("il est six heures", reply, "trimmed");
        assertEquals("/v1/chat/completions", lastPath.get());
        assertEquals("Bearer sk-test", lastAuthorization.get());
        JsonObject sent = JsonParser.parseString(new String(lastBody.get(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("gpt-4o-mini", sent.get("model").getAsString());
        assertEquals(120, sent.get("max_tokens").getAsInt());
        assertEquals(0.7, sent.get("temperature").getAsDouble(), 1e-9);
        assertFalse(sent.get("stream").getAsBoolean(), "the answer is synthesised whole");
        assertEquals("none", sent.get("reasoning_effort").getAsString(),
                "and not Ollama's think:false, which this route ignores - the content then comes back empty");
        assertFalse(sent.has("think"));
        var messages = sent.getAsJsonArray("messages");
        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals("assistant", messages.get(2).getAsJsonObject().get("role").getAsString());
        assertEquals("et maintenant ?", messages.get(3).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void anOllamaEndpointNeedsNoKeyAndUsesTheSameRoute() {
        // Ollama serves the OpenAI-compatible route under /v1, so one client covers both.
        answer("/v1/chat/completions", 200, "application/json", A_REPLY.getBytes(StandardCharsets.UTF_8));

        new OpenAiChatModel(endpoint("", "gemma4:e4b-it-qat"), http, 0.5, "none")
                .reply(List.of(ChatModel.Message.user("salut")), List.of(), 60);

        assertNull(lastAuthorization.get());
    }

    @Test
    void theTemperatureIsClampedToWhatTheApiAccepts() {
        answer("/v1/chat/completions", 200, "application/json", A_REPLY.getBytes(StandardCharsets.UTF_8));

        new OpenAiChatModel(endpoint("k", "m"), http, 9, "none")
                .reply(List.of(ChatModel.Message.user("a")), List.of(), 60);

        JsonObject sent = JsonParser.parseString(new String(lastBody.get(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(2.0, sent.get("temperature").getAsDouble(), 1e-9);
    }

    @Test
    void anEmptyContentIsReportedAsWhatItUsuallyIs() {
        // A reasoning model that spent its whole budget thinking. Reporting it as silence would send the
        // reader looking in the wrong place.
        answer("/v1/chat/completions", 200, "application/json",
                "{\"choices\":[{\"message\":{\"content\":null}}]}".getBytes(StandardCharsets.UTF_8));

        AiRequestException failure = assertThrows(AiRequestException.class,
                () -> new OpenAiChatModel(endpoint("k", "m"), http, 0.7, "none")
                        .reply(List.of(ChatModel.Message.user("a")), List.of(), 60));

        assertTrue(failure.getMessage().contains("thinking"), failure.getMessage());
    }

    @Test
    void ananswerWithNoChoiceIsRefused() {
        answer("/v1/chat/completions", 200, "application/json",
                "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8));

        assertThrows(AiRequestException.class, () -> new OpenAiChatModel(endpoint("k", "m"), http, 0.7, "none")
                .reply(List.of(ChatModel.Message.user("a")), List.of(), 60));
    }

    @Test
    void aChatAnswerThatIsNotJsonIsReported() {
        answer("/v1/chat/completions", 200, "text/html", "<html>nope</html>".getBytes(StandardCharsets.UTF_8));

        AiRequestException failure = assertThrows(AiRequestException.class,
                () -> new OpenAiChatModel(endpoint("k", "m"), http, 0.7, "none")
                        .reply(List.of(ChatModel.Message.user("a")), List.of(), 60));

        assertTrue(failure.getMessage().contains("expected JSON"), failure.getMessage());
    }

    @Test
    void askingWithNoMessagesIsRefusedBeforeAnythingIsSent() {
        OpenAiChatModel model = new OpenAiChatModel(endpoint("k", "m"), http, 0.7, "none");

        assertThrows(IllegalArgumentException.class, () -> model.reply(List.of(), List.of(), 60));
        assertThrows(IllegalArgumentException.class, () -> model.reply(null, List.of(), 60));
        assertNull(lastPath.get());
    }

    @Test
    void anEmptyReasoningEffortOmitsTheParameterAltogether() {
        // A model that rejects the parameter needs it absent, not empty.
        answer("/v1/chat/completions", 200, "application/json", A_REPLY.getBytes(StandardCharsets.UTF_8));

        new OpenAiChatModel(endpoint("k", "m"), http, 0.7, "")
                .reply(List.of(ChatModel.Message.user("a")), List.of(), 60);

        JsonObject sent = JsonParser.parseString(new String(lastBody.get(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertFalse(sent.has("reasoning_effort"));
    }

    // Tool calls: the wire shape of a tool, and the two ways a provider answers with one

    private static final ChatModel.Tool A_TOOL = new ChatModel.Tool("recall_person",
            "What one person said", new java.util.LinkedHashMap<>(java.util.Map.of(
            "name", ChatModel.Tool.Parameter.requiredString("who"))));

    @Test
    void aToolIsSentAsTheJsonSchemaTheApiExpects() {
        answer("/v1/chat/completions", 200, "application/json", A_REPLY.getBytes(StandardCharsets.UTF_8));

        new OpenAiChatModel(endpoint("k", "m"), http, 0.7, "none")
                .reply(List.of(ChatModel.Message.user("a")), List.of(A_TOOL), 60);

        JsonObject sent = JsonParser.parseString(new String(lastBody.get(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject tool = sent.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("function", tool.get("type").getAsString());
        JsonObject function = tool.getAsJsonObject("function");
        assertEquals("recall_person", function.get("name").getAsString());
        assertEquals("What one person said", function.get("description").getAsString());
        JsonObject parameters = function.getAsJsonObject("parameters");
        assertEquals("object", parameters.get("type").getAsString());
        assertEquals("string",
                parameters.getAsJsonObject("properties").getAsJsonObject("name").get("type").getAsString());
        assertEquals("name", parameters.getAsJsonArray("required").get(0).getAsString());
    }

    @Test
    void aRoundThatOffersToolsAsksForADifferentReasoningEffort() {
        // Measured on Ollama: with reasoning_effort "none" no tool call ever comes back - one model answered
        // "I have no memory of that", another invented a memory, a third said out loud that it should call the
        // tool. With "low" both called it. So the effort depends on whether tools were offered.
        answer("/v1/chat/completions", 200, "application/json", A_REPLY.getBytes(StandardCharsets.UTF_8));

        new OpenAiChatModel(endpoint("k", "m"), http, 0.7, "none", "low")
                .reply(List.of(ChatModel.Message.user("a")), List.of(A_TOOL), 60);

        assertEquals("low", JsonParser.parseString(new String(lastBody.get(), StandardCharsets.UTF_8))
                .getAsJsonObject().get("reasoning_effort").getAsString());
    }

    @Test
    void theAnsweringRoundKeepsTheFastEffortSinceItsLatencyIsTheOneHeard() {
        answer("/v1/chat/completions", 200, "application/json", A_REPLY.getBytes(StandardCharsets.UTF_8));

        new OpenAiChatModel(endpoint("k", "m"), http, 0.7, "none", "low")
                .reply(List.of(ChatModel.Message.user("a")), List.of(), 60);

        assertEquals("none", JsonParser.parseString(new String(lastBody.get(), StandardCharsets.UTF_8))
                .getAsJsonObject().get("reasoning_effort").getAsString());
    }

    @Test
    void anEmptyToolEffortOmitsTheParameterOnAToolRoundToo() {
        answer("/v1/chat/completions", 200, "application/json", A_REPLY.getBytes(StandardCharsets.UTF_8));

        new OpenAiChatModel(endpoint("k", "m"), http, 0.7, "none", "")
                .reply(List.of(ChatModel.Message.user("a")), List.of(A_TOOL), 60);

        assertFalse(JsonParser.parseString(new String(lastBody.get(), StandardCharsets.UTF_8))
                .getAsJsonObject().has("reasoning_effort"));
    }

    @Test
    void noToolMeansNoToolsFieldAtAll() {
        // A provider that does not support tools must not be sent an empty list.
        answer("/v1/chat/completions", 200, "application/json", A_REPLY.getBytes(StandardCharsets.UTF_8));

        new OpenAiChatModel(endpoint("k", "m"), http, 0.7, "none")
                .reply(List.of(ChatModel.Message.user("a")), List.of(), 60);

        assertFalse(JsonParser.parseString(new String(lastBody.get(), StandardCharsets.UTF_8))
                .getAsJsonObject().has("tools"));
    }

    @Test
    void openAiSendsTheArgumentsAsAJsonString() {
        answer("/v1/chat/completions", 200, "application/json", ("""
                {"choices":[{"message":{"content":"","tool_calls":[
                  {"id":"call_abc","type":"function","function":{"name":"recall_person",
                   "arguments":"{\\"name\\":\\"Victor\\"}"}}]}}]}""").getBytes(StandardCharsets.UTF_8));

        ChatModel.Answer answer = new OpenAiChatModel(endpoint("k", "m"), http, 0.7, "none")
                .reply(List.of(ChatModel.Message.user("a")), List.of(A_TOOL), 60);

        assertTrue(answer.hasToolCalls());
        assertEquals("call_abc", answer.toolCalls().get(0).id());
        assertEquals("recall_person", answer.toolCalls().get(0).name());
        assertEquals("{\"name\":\"Victor\"}", answer.toolCalls().get(0).arguments());
    }

    @Test
    void ollamaSendsThemAsTheObjectAndSometimesWithoutAnId() {
        // Both shapes are real; a plugin cannot choose which provider it is talking to.
        answer("/v1/chat/completions", 200, "application/json", ("""
                {"choices":[{"message":{"content":null,"tool_calls":[
                  {"function":{"name":"recall_person","arguments":{"name":"Victor"}}}]}}]}""")
                .getBytes(StandardCharsets.UTF_8));

        ChatModel.Answer answer = new OpenAiChatModel(endpoint("k", "m"), http, 0.7, "none")
                .reply(List.of(ChatModel.Message.user("a")), List.of(A_TOOL), 60);

        assertEquals("{\"name\":\"Victor\"}", answer.toolCalls().get(0).arguments());
        // The results must quote an id back, so one is made up when the provider omitted it.
        assertEquals("call_0", answer.toolCalls().get(0).id());
    }

    @Test
    void aToolCallWithoutANameIsSkippedRatherThanFailingTheTurn() {
        answer("/v1/chat/completions", 200, "application/json", ("""
                {"choices":[{"message":{"content":"voila","tool_calls":[{"id":"x","function":{}}]}}]}""")
                .getBytes(StandardCharsets.UTF_8));

        ChatModel.Answer answer = new OpenAiChatModel(endpoint("k", "m"), http, 0.7, "none")
                .reply(List.of(ChatModel.Message.user("a")), List.of(A_TOOL), 60);

        assertFalse(answer.hasToolCalls());
        assertEquals("voila", answer.content());
    }

    @Test
    void anAnswerWithNeitherContentNorToolCallIsStillReported() {
        answer("/v1/chat/completions", 200, "application/json",
                "{\"choices\":[{\"message\":{\"content\":\"\",\"tool_calls\":[]}}]}"
                        .getBytes(StandardCharsets.UTF_8));

        assertThrows(AiRequestException.class, () -> new OpenAiChatModel(endpoint("k", "m"), http, 0.7, "none")
                .reply(List.of(ChatModel.Message.user("a")), List.of(A_TOOL), 60));
    }

    @Test
    void theResultsAreSentBackWithTheCallTheyAnswerAndTheTurnThatAskedForIt() {
        answer("/v1/chat/completions", 200, "application/json", A_REPLY.getBytes(StandardCharsets.UTF_8));
        ChatModel.ToolCall call = new ChatModel.ToolCall("call_abc", "recall_person", "{\"name\":\"V\"}");

        new OpenAiChatModel(endpoint("k", "m"), http, 0.7, "none").reply(List.of(
                ChatModel.Message.user("a"),
                ChatModel.Message.assistantToolCalls(List.of(call)),
                ChatModel.Message.toolResult("call_abc", "V said hello")), List.of(A_TOOL), 60);

        var messages = JsonParser.parseString(new String(lastBody.get(), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("messages");
        JsonObject asked = messages.get(1).getAsJsonObject();
        assertEquals("assistant", asked.get("role").getAsString());
        JsonObject wireCall = asked.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("call_abc", wireCall.get("id").getAsString());
        assertEquals("function", wireCall.get("type").getAsString());
        assertEquals("{\"name\":\"V\"}",
                wireCall.getAsJsonObject("function").get("arguments").getAsString(),
                "the arguments go back as the model wrote them, as a JSON string");
        JsonObject result = messages.get(2).getAsJsonObject();
        assertEquals("tool", result.get("role").getAsString());
        assertEquals("call_abc", result.get("tool_call_id").getAsString());
        assertEquals("V said hello", result.get("content").getAsString());
    }
}

