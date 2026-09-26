package fr.farmvivi.fluxcord.api.audio;

import net.dv8tion.jda.api.audio.AudioSendHandler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.Objects;

/**
 * Plays already-decoded audio into a voice channel, 20 ms at a time.
 *
 * <p>A queue rather than a single clip: two {@code /speak} calls in a row must both be heard, and the
 * plugin keeps one handler registered per guild instead of registering and deregistering around every
 * sentence — that would make the voice pipeline drop the connection between two answers.
 *
 * <p>Provides PCM ({@link #isOpus()} is false), so the core mixer can apply the volume and duck the
 * music plugin while the bot speaks.
 */
public class PcmSendHandler implements AudioSendHandler {

    private final Deque<byte[]> queue = new ArrayDeque<>();
    private byte[] current;
    private int offset;
    private ByteBuffer pendingFrame;

    /**
     * Adds audio to the end of the queue.
     *
     * @param audio the audio to play; converted to 48 kHz stereo if it is not already
     */
    public synchronized void enqueue(PcmAudio audio) {
        Objects.requireNonNull(audio, "audio");
        PcmAudio converted = audio.toDiscordFormat();
        if (!converted.isEmpty()) {
            queue.add(converted.samples());
        }
    }

    /** Drops everything still queued, including the clip being played. */
    public synchronized void clear() {
        queue.clear();
        current = null;
        offset = 0;
        pendingFrame = null;
    }

    /** @return true when nothing is playing and nothing is queued */
    public synchronized boolean isIdle() {
        return current == null && queue.isEmpty() && pendingFrame == null;
    }

    /** @return how many clips are waiting behind the one being played */
    public synchronized int queuedClips() {
        return queue.size();
    }

    @Override
    public synchronized boolean canProvide() {
        if (current == null || offset >= current.length) {
            current = queue.poll();
            offset = 0;
        }
        if (current == null) {
            return false;
        }
        int remaining = current.length - offset;
        byte[] frame = new byte[PcmAudio.DISCORD_FRAME_SIZE];
        int length = Math.min(remaining, PcmAudio.DISCORD_FRAME_SIZE);
        System.arraycopy(current, offset, frame, 0, length);
        if (length < PcmAudio.DISCORD_FRAME_SIZE) {
            // JDA expects full frames: pad the tail of a clip with silence.
            Arrays.fill(frame, length, PcmAudio.DISCORD_FRAME_SIZE, (byte) 0);
        }
        offset += length;
        pendingFrame = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        return true;
    }

    @Override
    public synchronized ByteBuffer provide20MsAudio() {
        ByteBuffer frame = pendingFrame;
        pendingFrame = null;
        return frame;
    }

    @Override
    public boolean isOpus() {
        // PCM, so the core can mix this with the music plugin and apply the volume.
        return false;
    }
}
