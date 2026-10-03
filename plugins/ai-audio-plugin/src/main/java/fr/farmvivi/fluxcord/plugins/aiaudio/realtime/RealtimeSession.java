package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The WebSocket, and nothing else.
 *
 * <p>Deliberately the thinnest class in the plugin: it opens the socket, reassembles text frames, hands them to
 * {@link RealtimeProtocol#parseAll} and forwards the results. Every decision lives in
 * {@link RealtimeConversation}, which is tested against {@link RealtimeLink} instead. This is the same bargain
 * the core makes with {@code JDADiscordAPI} — the part that cannot be exercised without the real service is
 * kept small enough to read.
 *
 * <p><strong>Frames arrive as text from one service and as binary from the other.</strong> Google's Live
 * API sends binary frames holding UTF-8 JSON; OpenAI sends text. Both are handled, because a client that
 * implements only one of them receives absolutely nothing from the other and says nothing about it.
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
     * @param protocol the dialect, which decides both how the handshake authenticates and how a frame reads
     * @param url      the service's WebSocket URL as configured
     * @param apiKey   the key; one service sends it as a bearer header, the other in the URL
     * @param events   where parsed events go, on the socket's own thread
     * @return the open session
     * @throws RealtimeException if the socket could not be opened
     */
    public static RealtimeSession open(HttpClient http, RealtimeProtocol protocol, String url, String apiKey,
                                       Consumer<RealtimeProtocol.Event> events) {
        StringBuilder partial = new StringBuilder();
        // Binary frames are accumulated as bytes and not as text: a multi-byte character can be split
        // across two frames, and decoding each piece on its own would corrupt it.
        ByteArrayOutputStream binary = new ByteArrayOutputStream();
        WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT);
        protocol.headers(apiKey).forEach(builder::header);
        String endpoint = protocol.endpoint(url, apiKey);
        try {
            WebSocket socket = builder.buildAsync(URI.create(endpoint), new WebSocket.Listener() {
                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    partial.append(data);
                    if (last) {
                        String frame = partial.toString();
                        partial.setLength(0);
                        deliver(protocol, events, frame);
                    }
                    webSocket.request(1);
                    return null;
                }

                /**
                 * MEASURED, 2026-10-03: Google's Live API sends <strong>every</strong> frame as a binary
                 * frame holding UTF-8 JSON, where OpenAI sends text. A client implementing only
                 * {@code onText} therefore receives nothing from it at all - no setupComplete, no audio, no
                 * error - and the conversation opens, stays open and never speaks. Nothing in a log says
                 * why, which is what made this worth a comment this long.
                 */
                @Override
                public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                    byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    binary.write(bytes, 0, bytes.length);
                    if (last) {
                        String frame = binary.toString(StandardCharsets.UTF_8);
                        binary.reset();
                        deliver(protocol, events, frame);
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
            // The configured URL and not the endpoint: one dialect puts the key in the latter.
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
    private static void deliver(RealtimeProtocol protocol, Consumer<RealtimeProtocol.Event> events,
                                String frame) {
        try {
            protocol.parseAll(frame).forEach(events::accept);
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
