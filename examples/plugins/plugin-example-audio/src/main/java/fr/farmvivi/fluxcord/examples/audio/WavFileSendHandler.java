package fr.farmvivi.fluxcord.examples.audio;

import net.dv8tion.jda.api.audio.AudioSendHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Plays an audio file into a voice channel, one 20 ms frame at a time.
 *
 * <p>Whatever the file's own format is, it is converted to what Discord expects: 48 kHz, 16-bit,
 * stereo, signed, little-endian — which is also what the Fluxcord mixer works with. The last frame
 * is padded with silence so every frame handed to JDA has the same size.
 *
 * <p>This handler provides PCM ({@link #isOpus()} returns false), so the core can mix it with the
 * other plugins playing in the same guild and apply the volume.
 */
public class WavFileSendHandler implements AudioSendHandler {
    /** 20 ms of 48 kHz, 16-bit, stereo audio. */
    static final int FRAME_SIZE = 3840;

    private static final Logger LOG = LoggerFactory.getLogger(WavFileSendHandler.class);
    private static final AudioFormat DISCORD_FORMAT = new AudioFormat(48000f, 16, 2, true, false);

    private final byte[] frameBuffer = new byte[FRAME_SIZE];
    private AudioInputStream pcmStream;
    private ByteBuffer pendingFrame;
    private boolean done;

    /**
     * @param audioFile the file to play; an unreadable or unsupported file simply plays nothing
     */
    public WavFileSendHandler(File audioFile) {
        try {
            AudioInputStream source = AudioSystem.getAudioInputStream(audioFile);
            this.pcmStream = AudioSystem.getAudioInputStream(DISCORD_FORMAT, source);
            LOG.info("Playing '{}' converted to {} Hz, {}-bit, {} channels, little-endian",
                    audioFile.getName(), (int) DISCORD_FORMAT.getSampleRate(),
                    DISCORD_FORMAT.getSampleSizeInBits(), DISCORD_FORMAT.getChannels());
        } catch (UnsupportedAudioFileException | IOException e) {
            LOG.error("Failed to open audio file for playback: {}", audioFile.getAbsolutePath(), e);
            done = true;
        }
    }

    @Override
    public boolean canProvide() {
        if (done || pcmStream == null) {
            return false;
        }
        try {
            int read = readFully(pcmStream, frameBuffer);
            if (read < 0) {
                done = true;
                return false;
            }
            if (read < FRAME_SIZE) {
                // Pad the tail of the file with silence: JDA expects full frames.
                java.util.Arrays.fill(frameBuffer, read, FRAME_SIZE, (byte) 0);
                done = true;
            }
            // The array is reused for the next frame, so hand out a copy.
            pendingFrame = ByteBuffer.wrap(frameBuffer.clone()).order(ByteOrder.LITTLE_ENDIAN);
            return true;
        } catch (IOException e) {
            LOG.error("I/O error while reading audio data", e);
            done = true;
            return false;
        }
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        ByteBuffer frame = pendingFrame;
        pendingFrame = null;
        return frame;
    }

    @Override
    public boolean isOpus() {
        // PCM, so the core can mix this source with the others and apply the volume.
        return false;
    }

    /** Closes the underlying stream; call it when the plugin stops using the handler. */
    public void cleanup() {
        try {
            if (pcmStream != null) {
                pcmStream.close();
            }
        } catch (IOException e) {
            LOG.warn("Error while closing audio stream", e);
        }
    }

    /**
     * Reads until the buffer is full or the stream ends, because a single {@code read} may return
     * less than what was asked for.
     *
     * @return the number of bytes read, or -1 at the end of the stream
     */
    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return total == 0 ? -1 : total;
    }
}
