package fr.farmvivi.fluxcord.core.health;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class HealthServerTest {

    private HealthServer server;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeEach
    void start() throws IOException {
        server = new HealthServer(0); // ephemeral port
        server.setVersion("1.2.3");
        server.start();
        assertTrue(server.getPort() > 0);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path))
                .timeout(Duration.ofSeconds(3)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthzIsAlwaysOk() throws Exception {
        HttpResponse<String> response = get("/healthz");
        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());
        assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("text/plain"));
    }

    @Test
    void readyzReflectsTheReadyFlag() throws Exception {
        assertEquals(503, get("/readyz").statusCode());
        assertEquals("not-ready", get("/readyz").body());

        server.setReady(true);
        assertEquals(200, get("/readyz").statusCode());
        assertEquals("ok", get("/readyz").body());

        server.setReady(false);
        assertEquals(503, get("/readyz").statusCode());
    }

    @Test
    void versionIsExposed() throws Exception {
        HttpResponse<String> response = get("/version");
        assertEquals(200, response.statusCode());
        assertEquals("1.2.3", response.body());
    }

    @Test
    void unknownPathIs404() throws Exception {
        HttpResponse<String> response = get("/nope");
        assertEquals(404, response.statusCode());
        assertEquals("not-found", response.body());
    }

    @Test
    void serverHandlesSeveralSequentialRequests() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(200, get("/healthz").statusCode());
        }
    }

    @Test
    void stopReleasesThePortAndStartIsIdempotent() throws Exception {
        int port = server.getPort();
        server.start(); // second start is a no-op
        assertEquals(port, server.getPort());

        server.stop();

        assertThrows(ConnectException.class, () -> get("/healthz"));
    }
}
