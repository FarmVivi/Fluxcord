package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Search through a <a href="https://docs.searxng.org/dev/search_api.html">SearxNG</a> instance.
 *
 * <p>SearxNG is a metasearch front end somebody runs themselves: it asks the engines on the operator's behalf,
 * so a query carries the instance's address rather than the speaker's, and with a local instance nothing a
 * voice channel says leaves the network. That is why it is the backend this plugin ships first, and why
 * <strong>no default instance is configured</strong> — pointing the bot at a public one by default would
 * quietly send private conversations to a stranger's server.
 *
 * <p>Measured against a real instance, and three things are worth knowing before changing this class:
 *
 * <ul>
 *   <li><strong>{@code format=json} is off by default.</strong> A fresh SearxNG serves only HTML and answers
 *       {@code 403} to an API call; the operator has to add {@code json} to {@code search.formats} in
 *       {@code settings.yml}. That failure is reported with the fix in the message, because it is the first
 *       thing anybody hits.</li>
 *   <li><strong>{@code unresponsive_engines} is almost never empty</strong> — a rate-limited or
 *       CAPTCHA-serving engine appeared on nearly every call of a healthy instance. It is a partial
 *       degradation, never a failure, so it is logged at debug and nothing more.</li>
 *   <li><strong>{@code number_of_results} is not always sent</strong> (it was absent from every answer of the
 *       instance this was written against), so nothing here depends on it. An empty {@code results} array is
 *       the only reliable way to know there was nothing.</li>
 * </ul>
 *
 * <p>Latency measured on a local instance: 0.6 to 1.1 s per query, which is the same order as the model round
 * it is feeding.
 */
public class SearxngWebSearch implements WebSearch {

    private static final Logger LOG = LoggerFactory.getLogger(SearxngWebSearch.class);

    /** How much of a snippet is kept. Enough to answer from, short enough that ten of them stay cheap. */
    private static final int MAX_SNIPPET = 320;

    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;
    private final HttpClient http;
    private final String language;
    private final int safeSearch;

    /**
     * @param baseUrl    the instance's root, with or without a trailing slash
     * @param apiKey     a bearer token when the instance sits behind an authenticating proxy, or empty
     * @param timeout    how long to wait for the whole query
     * @param http       the shared client
     * @param language   the language code to search in, or empty to let the instance decide
     * @param safeSearch 0 off, 1 moderate, 2 strict, as SearxNG numbers it
     */
    public SearxngWebSearch(String baseUrl, String apiKey, Duration timeout, HttpClient http,
                            String language, int safeSearch) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        String trimmed = baseUrl.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.baseUrl = trimmed;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.timeout = timeout;
        this.http = http;
        this.language = language == null ? "" : language.strip();
        this.safeSearch = Math.clamp(safeSearch, 0, 2);
    }

    @Override
    public List<Result> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            // SearxNG answers 400 {"error": "No query"}; there is no reason to find that out over the network.
            throw new IllegalArgumentException("query is required");
        }
        int wanted = Math.max(1, limit);
        StringBuilder url = new StringBuilder(baseUrl).append("/search?format=json&q=")
                .append(URLEncoder.encode(query.strip(), StandardCharsets.UTF_8));
        if (!language.isEmpty()) {
            url.append("&language=").append(URLEncoder.encode(language, StandardCharsets.UTF_8));
        }
        url.append("&safesearch=").append(safeSearch);

        HttpRequest.Builder request = HttpRequest.newBuilder(java.net.URI.create(url.toString()))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET();
        if (!apiKey.isEmpty()) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        LOG.debug("Searching {} for {} result(s)", baseUrl, wanted);
        return read(AiHttp.send(http, request.build(), "Web search"), wanted);
    }

    private List<Result> read(byte[] response, int limit) {
        String raw = new String(response, StandardCharsets.UTF_8).trim();
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            logUnresponsive(json);
            JsonArray results = json.getAsJsonArray("results");
            if (results == null) {
                throw new AiRequestException("The search instance answered without a results array."
                        + " Check that \"json\" is listed under search.formats in its settings.yml");
            }
            List<Result> read = new ArrayList<>();
            for (JsonElement element : results) {
                if (read.size() >= limit) {
                    break;
                }
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject result = element.getAsJsonObject();
                Result candidate = new Result(string(result, "title"), string(result, "url"),
                        truncate(string(result, "content")));
                if (!candidate.isEmpty()) {
                    read.add(candidate);
                }
            }
            return List.copyOf(read);
        } catch (JsonParseException | IllegalStateException | ClassCastException e) {
            throw new AiRequestException("The search instance did not answer JSON. Check that \"json\" is"
                    + " listed under search.formats in its settings.yml; it serves HTML only by default", e);
        }
    }

    /**
     * An engine that did not answer is normal, not an error.
     *
     * <p>Every call to the healthy instance this was measured against named at least one engine as rate
     * limited or serving a CAPTCHA. Treating that as a failure would make search look permanently broken.
     */
    private void logUnresponsive(JsonObject json) {
        JsonArray unresponsive = json.getAsJsonArray("unresponsive_engines");
        if (unresponsive != null && !unresponsive.isEmpty() && LOG.isDebugEnabled()) {
            LOG.debug("{} engine(s) did not answer this query: {}", unresponsive.size(), unresponsive);
        }
    }

    private static String string(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    private static String truncate(String snippet) {
        if (snippet.length() <= MAX_SNIPPET) {
            return snippet;
        }
        return snippet.substring(0, MAX_SNIPPET) + "...";
    }
}
