package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A WebSocket server, by hand, so that {@link RealtimeSession} can be tested at all.
 *
 * <p>It exists because of a specific bug. Google's Live API sends every frame as a <em>binary</em> frame
 * holding UTF-8 JSON, OpenAI sends text, and {@code RealtimeSession} originally implemented only
 * {@code onText} — so it received nothing at all from one of the two services, with no error anywhere. That
 * class had been left deliberately untested as "the thin layer that cannot be exercised", and this is the
 * bill for that decision: a whole provider failing in silence. The JDK ships a WebSocket <em>client</em> and
 * no server, hence the hundred lines below rather than a dependency.
 *
 * <p>Only what the test needs: one connection, the RFC 6455 handshake, text and binary frames, fragmentation,
 * and reading back what the client sent (which is masked, as the spec requires of a client). No ping, no
 * extensions, no TLS.
 */
final class LoopbackWebSocket implements AutoCloseable {

    /** The magic string RFC 6455 appends to the client's key before hashing it. */
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket listener;
    private final Thread acceptor;
    private final AtomicReference<OutputStream> out = new AtomicReference<>();
    private final CountDownLatch connected = new CountDownLatch(1);
    /** Everything the client sent, unmasked and decoded. */
    private final List<String> received = new CopyOnWriteArrayList<>();
    private volatile boolean closing;

    LoopbackWebSocket() throws IOException {
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress("127.0.0.1", 0));
        acceptor = new Thread(this::serve, "loopback-websocket");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String url() {
        return "ws://127.0.0.1:" + listener.getLocalPort() + "/realtime";
    }

    /** @return what the client sent, in order */
    List<String> received() {
        return List.copyOf(received);
    }

    /** Waits for the client's handshake to complete. */
    void awaitConnection() throws InterruptedException {
        if (!connected.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the client never connected");
        }
    }

    void sendText(String payload) throws IOException {
        sendFrame(0x1, payload.getBytes(StandardCharsets.UTF_8), true);
    }

    void sendBinary(String payload) throws IOException {
        sendFrame(0x2, payload.getBytes(StandardCharsets.UTF_8), true);
    }

    /**
     * Sends one message in pieces, the way a large frame really arrives.
     *
     * <p>Split on <strong>bytes</strong>, deliberately: a UTF-8 character can straddle the boundary, which is
     * exactly the case that a client accumulating text rather than bytes gets wrong.
     *
     * @param opcode 0x1 for text, 0x2 for binary
     * @param pieces how many fragments to split it into
     */
    void sendFragmented(int opcode, String payload, int pieces) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        int size = Math.max(1, bytes.length / pieces);
        for (int at = 0; at < bytes.length; at += size) {
            int end = Math.min(at + size, bytes.length);
            boolean last = end == bytes.length;
            // The first fragment carries the opcode; every continuation carries 0x0.
            sendFrame(at == 0 ? opcode : 0x0, java.util.Arrays.copyOfRange(bytes, at, end), last);
        }
    }

    /** Closes the connection the way a service hanging up does. */
    void hangUp(int status, String reason) throws IOException {
        closing = true;
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write((status >> 8) & 0xFF);
        payload.write(status & 0xFF);
        payload.writeBytes(reason.getBytes(StandardCharsets.UTF_8));
        sendFrame(0x8, payload.toByteArray(), true);
    }

    private synchronized void sendFrame(int opcode, byte[] payload, boolean last) throws IOException {
        OutputStream stream = out.get();
        if (stream == null) {
            throw new IllegalStateException("nobody is connected");
        }
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write((last ? 0x80 : 0x00) | opcode);
        if (payload.length < 126) {
            frame.write(payload.length);
        } else if (payload.length < 65_536) {
            frame.write(126);
            frame.write((payload.length >> 8) & 0xFF);
            frame.write(payload.length & 0xFF);
        } else {
            frame.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame.write((int) ((long) payload.length >> shift) & 0xFF);
            }
        }
        // Never masked: a server must not mask, and a client that unmasks anyway would hide a bug.
        frame.writeBytes(payload);
        stream.write(frame.toByteArray());
        stream.flush();
    }

    private void serve() {
        try (Socket socket = listener.accept()) {
            InputStream in = socket.getInputStream();
            out.set(socket.getOutputStream());
            handshake(in, socket.getOutputStream());
            connected.countDown();
            readFrames(in);
        } catch (IOException | RuntimeException e) {
            if (!closing && !listener.isClosed()) {
                // Printed rather than thrown: this is a daemon thread and a test must not hang on it.
                System.out.println("loopback websocket stopped: " + e);
            }
        }
    }

    private void handshake(InputStream in, OutputStream stream) throws IOException {
        StringBuilder headers = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            headers.append((char) c);
            if (headers.length() >= 4 && headers.substring(headers.length() - 4).equals("\r\n\r\n")) {
                break;
            }
        }
        String key = null;
        for (String line : headers.toString().split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:")) {
                key = line.substring(line.indexOf(':') + 1).strip();
            }
        }
        if (key == null) {
            throw new IOException("no Sec-WebSocket-Key in the request");
        }
        String accept;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            accept = Base64.getEncoder().encodeToString(
                    sha1.digest((key + GUID).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        stream.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        stream.flush();
    }

    /** Reads client frames, which are always masked, and records the text ones. */
    private void readFrames(InputStream in) throws IOException {
        while (!closing) {
            int first = in.read();
            if (first < 0) {
                return;
            }
            int opcode = first & 0x0F;
            int second = in.read();
            if (second < 0) {
                return;
            }
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;
            if (length == 126) {
                length = (read(in) << 8) | read(in);
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | read(in);
                }
            }
            byte[] mask = new byte[4];
            if (masked) {
                for (int i = 0; i < 4; i++) {
                    mask[i] = (byte) read(in);
                }
            }
            byte[] payload = new byte[(int) length];
            int read = 0;
            while (read < payload.length) {
                int n = in.read(payload, read, payload.length - read);
                if (n < 0) {
                    return;
                }
                read += n;
            }
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                }
            }
            if (opcode == 0x8) {
                return;
            }
            if (opcode == 0x1 || opcode == 0x2 || opcode == 0x0) {
                received.add(new String(payload, StandardCharsets.UTF_8));
            }
        }
    }

    private static int read(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new IOException("the connection ended mid-frame");
        }
        return value;
    }

    @Override
    public void close() throws IOException {
        closing = true;
        listener.close();
        acceptor.interrupt();
    }
}
