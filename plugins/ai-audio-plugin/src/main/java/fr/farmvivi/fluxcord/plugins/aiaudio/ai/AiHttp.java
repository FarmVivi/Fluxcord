package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * The little bit of HTTP that both AI clients need: the shared headers, and turning every failure into
 * one {@link AiRequestException} carrying a short reason a user can read.
 *
 * <p>Deliberately built on the JDK client: the plugin bundles no HTTP library, so its jar stays a few
 * kilobytes and nothing has to be shaded past the plugin class loader.
 */
final class AiHttp {

    private AiHttp() {
    }

    /** @return a request builder with the endpoint's URI, timeout and authorisation already set */
    static HttpRequest.Builder request(AiEndpoint endpoint, String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint.uri(path)).timeout(endpoint.timeout());
        if (endpoint.hasApiKey()) {
            builder.header("Authorization", "Bearer " + endpoint.apiKey());
        }
        return builder;
    }

    /**
     * Sends the request and returns the body, failing on any non-2xx status.
     *
     * @param what a few words naming the call, used in the error message
     * @return the raw response body
     * @throws AiRequestException on a transport error, a timeout or an error status
     */
    static byte[] send(HttpClient http, HttpRequest request, String what) {
        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) {
            throw new AiRequestException(what + " timed out after " + request.timeout().orElseThrow(), e);
        } catch (ConnectException e) {
            throw new AiRequestException(what + " could not reach " + request.uri().getHost()
                    + ":" + request.uri().getPort(), e);
        } catch (IOException e) {
            throw new AiRequestException(what + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiRequestException(what + " was interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new AiRequestException(what + " returned HTTP " + response.statusCode()
                    + ": " + errorMessage(response.body()));
        }
        return response.body();
    }

    /**
     * Extracts something readable from an error body. OpenAI-compatible servers answer
     * {@code {"error": {"message": "..."}}}, but a proxy in front of one answers HTML, and a wrong URL
     * answers a 404 page — so the body is truncated rather than trusted.
     */
    private static String errorMessage(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).trim();
        try {
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            if (json.has("error")) {
                var error = json.get("error");
                if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
                    return error.getAsJsonObject().get("message").getAsString();
                }
                return error.getAsString();
            }
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException e) {
            // Not the JSON shape we hoped for; fall through to the truncated body.
        }
        if (text.isEmpty()) {
            return "<empty response>";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
