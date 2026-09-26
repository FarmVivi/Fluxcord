package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import java.net.URI;
import java.time.Duration;

/**
 * One OpenAI-compatible HTTP endpoint.
 *
 * <p>The plugin never talks to a vendor SDK: it speaks the OpenAI audio HTTP API, which is the de
 * facto interface every self-hosted server exposes too. Pointing {@code baseUrl} elsewhere is all it
 * takes to run the feature locally — see the plugin README for the servers that are known to work.
 *
 * @param baseUrl the API root, with or without a trailing slash (e.g. {@code https://api.openai.com/v1}
 *                or {@code http://localhost:8000/v1})
 * @param apiKey  sent as {@code Authorization: Bearer ...}; empty means no header, which is what a
 *                local server without authentication expects
 * @param model   the model name to ask for
 * @param timeout how long to wait for a response
 */
public record AiEndpoint(String baseUrl, String apiKey, String model, Duration timeout) {

    public AiEndpoint {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        apiKey = apiKey == null ? "" : apiKey.trim();
        baseUrl = baseUrl.trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    /**
     * @param path the path relative to the API root, starting with a slash (e.g. {@code /audio/speech})
     * @return the absolute URI to call
     */
    public URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    /** @return true when an {@code Authorization} header must be sent */
    public boolean hasApiKey() {
        return !apiKey.isEmpty();
    }

    /** Keeps the key out of logs: {@code toString} is what ends up in a debug line. */
    @Override
    public String toString() {
        return "AiEndpoint[baseUrl=" + baseUrl + ", model=" + model
                + ", apiKey=" + (hasApiKey() ? "<set>" : "<none>") + ", timeout=" + timeout + "]";
    }
}
