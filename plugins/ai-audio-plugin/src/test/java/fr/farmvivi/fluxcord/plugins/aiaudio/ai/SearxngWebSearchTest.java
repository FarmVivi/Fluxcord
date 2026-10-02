package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The SearxNG client, against a real HTTP server on a loopback port.
 *
 * <p>What is pinned here is mostly what a real instance taught: that a fresh SearxNG serves HTML and has to be
 * told to serve JSON, so that failure needs to name its own fix; that an engine failing to answer is the normal
 * state of a healthy instance and must not look like an error; and that {@code number_of_results} may simply
 * not be there. The rest is the mapping, which is where a field-name typo would hide silently.
 */
class SearxngWebSearchTest {

    private HttpServer server;
    private HttpClient http;
    private String baseUrl;

    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http = HttpClient.newHttpClient();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        http.close();
    }

    private void answer(int status, String contentType, String body) {
        server.createContext("/search", exchange -> {
            record(exchange);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private void record(HttpExchange exchange) {
        lastQuery.set(exchange.getRequestURI().getQuery());
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    }

    private WebSearch search() {
        return searchAt(baseUrl, "");
    }

    private WebSearch searchAt(String url, String apiKey) {
        return new SearxngWebSearch(url, apiKey, Duration.ofSeconds(5), http, "fr", 1);
    }

    private static final String TWO_RESULTS = """
            {"query":"rhubarbe","results":[
              {"title":"La rhubarbe","url":"https://example.test/a","content":"Une plante.","engine":"x"},
              {"title":"Tarte","url":"https://example.test/b","content":"Un dessert.","engine":"y"}],
             "answers":[],"infoboxes":[],"suggestions":[],"unresponsive_engines":[]}""";

    @Test
    void theQueryIsSentAsJsonWithTheConfiguredLanguageAndSafeSearch() {
        answer(200, "application/json", TWO_RESULTS);

        search().search("tarte à la rhubarbe", 5);

        // getQuery() percent-decodes, so the accents come back readable; the + for a space does not, which is
        // what shows the value was encoded. Both forms were checked against a real instance, which decodes
        // + as a space exactly as the form encoding says it should.
        String query = lastQuery.get();
        assertTrue(query.contains("format=json"), query);
        assertTrue(query.contains("q=tarte+à+la+rhubarbe"), "the query is encoded: " + query);
        assertTrue(query.contains("language=fr"), query);
        assertTrue(query.contains("safesearch=1"), query);
    }

    @Test
    void theResultsAreMappedFromTitleUrlAndContent() {
        answer(200, "application/json", TWO_RESULTS);

        List<WebSearch.Result> results = search().search("rhubarbe", 5);

        assertEquals(2, results.size());
        assertEquals("La rhubarbe", results.get(0).title());
        assertEquals("https://example.test/a", results.get(0).url());
        assertEquals("Une plante.", results.get(0).snippet(), "the snippet is SearxNG's \"content\"");
    }

    @Test
    void noMoreThanTheRequestedNumberComesBack() {
        answer(200, "application/json", TWO_RESULTS);

        assertEquals(1, search().search("rhubarbe", 1).size());
    }

    @Test
    void aResultWithoutAUrlIsDroppedRatherThanOfferedAsSomethingToRead() {
        answer(200, "application/json", """
                {"results":[{"title":"Sans lien","content":"Rien à ouvrir."},
                            {"title":"Avec","url":"https://example.test/a","content":"Bon."}]}""");

        List<WebSearch.Result> results = search().search("x", 5);

        assertEquals(1, results.size());
        assertEquals("https://example.test/a", results.get(0).url());
    }

    @Test
    void aMissingFieldIsEmptyRatherThanAFailure() {
        // Engines disagree on what they fill in; an image result has no content at all.
        answer(200, "application/json",
                "{\"results\":[{\"url\":\"https://example.test/a\",\"title\":\"Juste un titre\"}]}");

        List<WebSearch.Result> results = search().search("x", 5);

        assertEquals(1, results.size());
        assertEquals("", results.get(0).snippet());
    }

    @Test
    void aVeryLongExtractIsTruncatedSoTenOfThemStayCheap() {
        answer(200, "application/json", "{\"results\":[{\"url\":\"https://example.test/a\","
                + "\"title\":\"t\",\"content\":\"" + "x".repeat(900) + "\"}]}");

        String snippet = search().search("x", 5).get(0).snippet();

        assertTrue(snippet.length() < 400, "was " + snippet.length());
        assertTrue(snippet.endsWith("..."), "and it says it was cut");
    }

    @Test
    void anEngineThatDidNotAnswerIsNotAFailure() {
        // Measured: a healthy instance names a rate-limited or CAPTCHA-serving engine on nearly every call.
        answer(200, "application/json", """
                {"results":[{"title":"t","url":"https://example.test/a","content":"c"}],
                 "unresponsive_engines":[["brave","Suspended: too many requests"],["duckduckgo","CAPTCHA"]]}""");

        assertEquals(1, search().search("x", 5).size());
    }

    @Test
    void nothingDependsOnNumberOfResults() {
        // The instance this was written against never sent it.
        answer(200, "application/json",
                "{\"results\":[{\"title\":\"t\",\"url\":\"https://example.test/a\",\"content\":\"c\"}]}");

        assertEquals(1, search().search("x", 5).size());
    }

    @Test
    void nothingFoundIsAnEmptyListAndNotAnError() {
        answer(200, "application/json", "{\"results\":[],\"unresponsive_engines\":[]}");

        assertTrue(search().search("qzkxjvhqwe", 5).isEmpty());
    }

    @Test
    void anInstanceServingHtmlIsToldWhatToChange() {
        // The first thing anybody hits: SearxNG only serves HTML until settings.yml says otherwise.
        answer(200, "text/html", "<!DOCTYPE html><html><body>results</body></html>");

        AiRequestException failure = assertThrows(AiRequestException.class,
                () -> search().search("x", 5));

        assertTrue(failure.getMessage().contains("search.formats"), failure.getMessage());
    }

    @Test
    void jsonWithoutAResultsArrayIsToldTheSameThing() {
        answer(200, "application/json", "{\"detail\":\"Not Found\"}");

        AiRequestException failure = assertThrows(AiRequestException.class,
                () -> search().search("x", 5));

        assertTrue(failure.getMessage().contains("search.formats"), failure.getMessage());
    }

    @Test
    void aRefusedApiCallCarriesTheStatus() {
        answer(403, "application/json", "{\"error\":\"Forbidden\"}");

        AiRequestException failure = assertThrows(AiRequestException.class,
                () -> search().search("x", 5));

        assertTrue(failure.getMessage().contains("403"), failure.getMessage());
    }

    @Test
    void aBlankQueryIsRefusedWithoutAskingTheInstance() {
        // SearxNG answers 400 {"error": "No query"}; there is no reason to learn that over the network.
        answer(200, "application/json", TWO_RESULTS);

        assertThrows(IllegalArgumentException.class, () -> search().search("   ", 5));
        assertNull(lastQuery.get(), "nothing was sent");
    }

    @Test
    void aTrailingSlashOnTheInstanceUrlDoesNotProduceADoubleSlash() {
        answer(200, "application/json", TWO_RESULTS);

        searchAt(baseUrl + "///", "").search("x", 5);

        assertNotNull(lastQuery.get(), "the request reached /search");
    }

    @Test
    void aTokenIsSentForAnInstanceBehindAProxy() {
        answer(200, "application/json", TWO_RESULTS);

        searchAt(baseUrl, "secret").search("x", 5);

        assertEquals("Bearer secret", lastAuthorization.get());
    }

    @Test
    void anInstanceUrlIsRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new SearxngWebSearch("", "", Duration.ofSeconds(5), http, "", 1));
        assertThrows(IllegalArgumentException.class,
                () -> new SearxngWebSearch(baseUrl, "", Duration.ZERO, http, "", 1));
    }
}
