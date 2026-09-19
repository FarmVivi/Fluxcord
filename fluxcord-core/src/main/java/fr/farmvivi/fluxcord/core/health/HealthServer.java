package fr.farmvivi.fluxcord.core.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Very small HTTP server exposing /healthz, /readyz, /version
 */
public class HealthServer {
    private static final Logger logger = LoggerFactory.getLogger(HealthServer.class);

    private final int port;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private volatile String version = "unknown";
    private ServerSocket serverSocket;
    private ExecutorService executor;

    public HealthServer(int port) {
        this.port = port;
    }

    private static String parsePath(String requestLine) {
        // format: GET /path HTTP/1.1
        String[] parts = requestLine.split(" ");
        return parts.length >= 2 ? parts[1] : "/";
    }

    private static void writeResponse(OutputStream out, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + "\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: " + payload.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    public void start() throws IOException {
        if (running.get()) return;
        serverSocket = new ServerSocket(port);
        executor = Executors.newCachedThreadPool();
        running.set(true);
        executor.submit(() -> {
            logger.info("Health server listening on {}", port);
            while (running.get()) {
                try {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handle(client));
                } catch (IOException e) {
                    if (running.get()) {
                        logger.debug("Health server accept error: {}", e.getMessage());
                    }
                }
            }
        });
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            logger.debug("Error while closing health server socket: {}", e.getMessage());
        }
        if (executor != null) executor.shutdownNow();
        logger.info("Health server stopped");
    }

    /**
     * @return the port actually bound (useful when constructed with port 0), or -1 if not started
     */
    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }

    public void setReady(boolean value) {
        this.ready.set(value);
    }

    public void setVersion(String v) {
        this.version = v;
    }

    private void handle(Socket client) {
        try (client;
             BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
             OutputStream out = client.getOutputStream()) {
            String line = in.readLine();
            if (line == null) return;
            String path = parsePath(line);
            int status = 200;
            String body = "ok";
            if ("/healthz".equals(path)) {
                status = 200;
                body = "ok";
            } else if ("/readyz".equals(path)) {
                if (!ready.get()) {
                    status = 503;
                    body = "not-ready";
                }
            } else if ("/version".equals(path)) {
                body = version;
            } else {
                status = 404;
                body = "not-found";
            }
            writeResponse(out, status, body);
        } catch (Exception e) {
            logger.debug("Health request handling error: {}", e.getMessage());
        }
    }
}
