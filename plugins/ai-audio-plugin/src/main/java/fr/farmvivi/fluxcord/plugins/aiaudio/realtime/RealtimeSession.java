package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The WebSocket, and nothing else.
 *
 * <p>Deliberately the thinnest class in the plugin: it opens the socket, reassembles text frames, hands them to
 * {@link RealtimeProtocol#parse} and forwards the result. Every decision lives in
 * {@link RealtimeConversation}, which is tested against {@link RealtimeLink} instead. This is the same bargain
 * the core makes with {@code JDADiscordAPI} — the part that cannot be exercised without the real service is
 * kept small enough to read.
 *
 * <p><strong>A text frame can arrive in pieces.</strong> {@link WebSocket.Listener#onText} is called with
 * {@code last == false} for every part but the final one, and a Realtime audio delta is large enough that this
 * happens routinely. Parsing each part on its own would fail on every long frame, so parts are accumulated
 * until the message is complete — the single trap in this class, and the reason it exists at all.
 *
 * <p>No dependency is added for any of this: {@code java.net.http} has had a WebSocket client since Java 11.
 */
public class RealtimeSession implements RealtimeLink {

    private static final Logger LOG = LoggerFactory.getLogger(RealtimeSession.class);

    /** Nothing in a voice conversation is worth waiting longer than this for. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    private final WebSocket socket;
    private final AtomicBoolean open = new AtomicBoolean(true);

    private RealtimeSession(WebSocket socket) {
        this.socket = socket;
    }

    /**
     * Opens a conversation.
     *
     * @param http     the shared client
     * @param url      the service's WebSocket URL, model included
     * @param apiKey   the bearer token; a Realtime service without one is not a case that exists yet
     * @param events   where parsed events go, on the socket's own thread
     * @return the open session
     * @throws RealtimeException if the socket could not be opened
     */
    public static RealtimeSession open(HttpClient http, String url, String apiKey,
                                       Consumer<RealtimeProtocol.Event> events) {
        StringBuilder partial = new StringBuilder();
        WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.strip());
        }
        try {
            WebSocket socket = builder.buildAsync(URI.create(url), new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    partial.append(data);
                    if (last) {
                        String frame = partial.toString();
                        partial.setLength(0);
                        deliver(events, frame);
                    }
                    webSocket.request(1);
                    return null;
                }

                @Override
                public void onError(WebSocket webSocket, Throwable error) {
                    events.accept(new RealtimeProtocol.Event.Failure(
                            "the realtime connection failed: " + error.getMessage()));
                }

                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
                    LOG.debug("The realtime connection closed ({} {})", status, reason);
                    events.accept(new RealtimeProtocol.Event.Failure(
                            "the realtime connection closed: " + status + " " + reason));
                    return null;
                }
            }).join();
            LOG.debug("Realtime connection open to {}", url);
            return new RealtimeSession(socket);
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new RealtimeException("Could not open a realtime connection: " + cause.getMessage(), cause);
        } catch (IllegalArgumentException e) {
            throw new RealtimeException("The realtime URL is not usable: " + e.getMessage(), e);
        }
    }

    /** Parsing is the protocol's job; a bug in it must not kill the socket's thread. */
    private static void deliver(Consumer<RealtimeProtocol.Event> events, String frame) {
        try {
            events.accept(RealtimeProtocol.parse(frame));
        } catch (RuntimeException e) {
            LOG.warn("Could not handle a realtime frame: {}", e.getMessage());
        }
    }

    @Override
    public void send(String frame) {
        if (!open.get()) {
            return;
        }
        try {
            socket.sendText(frame, true);
        } catch (RuntimeException e) {
            // The audio thread is a caller here, and it must not be the one to find out the socket died.
            LOG.debug("Could not send a realtime frame: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        } catch (RuntimeException e) {
            socket.abort();
        }
    }

    @Override
    public boolean isOpen() {
        return open.get() && !socket.isOutputClosed();
    }
}
