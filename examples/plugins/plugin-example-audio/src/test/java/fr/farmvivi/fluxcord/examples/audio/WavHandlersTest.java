package fr.farmvivi.fluxcord.examples.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two handlers of the audio example, checked against real files: they are what a plugin author
 * copies to play and record audio, and both sit on a byte-order boundary that is easy to get wrong
 * (JDA hands out big-endian PCM, WAV stores little-endian).
 */
class WavHandlersTest {

    @TempDir Path dir;

    /** Writes a WAV file of {@code frames} × 20 ms at Discord's format, with a recognisable ramp. */
    private File writeSample(String name, int frames) throws Exception {
        byte[] pcm = new byte[frames * WavFileSendHandler.FRAME_SIZE];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i % 251);
        }
        return writeWav(name, pcm);
    }

    private File writeWav(String name, byte[] pcm) throws Exception {
        AudioFormat format = new AudioFormat(48000f, 16, 2, true, false);
        File file = dir.resolve(name).toFile();
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, pcm.length / format.getFrameSize())) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file);
        }
        return file;
    }

    @Test
    void theSendHandlerCutsTheFileIntoTwentyMillisecondFrames() throws Exception {
        WavFileSendHandler handler = new WavFileSendHandler(writeSample("two-frames.wav", 2));

        assertFalse(handler.isOpus(), "PCM, so the core can mix and scale it");

        assertTrue(handler.canProvide());
        ByteBuffer first = handler.provide20MsAudio();
        assertEquals(WavFileSendHandler.FRAME_SIZE, first.remaining());
        assertEquals(ByteOrder.LITTLE_ENDIAN, first.order());

        assertTrue(handler.canProvide());
        ByteBuffer second = handler.provide20MsAudio();
        assertNotEquals(first, second, "each frame is a fresh copy, the buffer is reused");

        assertFalse(handler.canProvide(), "end of the file");
        handler.cleanup();
    }

    @Test
    void theLastPartialFrameIsPaddedWithSilence() throws Exception {
        byte[] pcm = new byte[WavFileSendHandler.FRAME_SIZE / 2];
        java.util.Arrays.fill(pcm, (byte) 7);
        WavFileSendHandler handler = new WavFileSendHandler(writeWav("half.wav", pcm));

        assertTrue(handler.canProvide());
        ByteBuffer frame = handler.provide20MsAudio();

        byte[] bytes = new byte[frame.remaining()];
        frame.get(bytes);
        assertEquals(WavFileSendHandler.FRAME_SIZE, bytes.length, "JDA always gets a full frame");
        assertEquals(7, bytes[pcm.length - 1]);
        assertEquals(0, bytes[pcm.length], "padded with silence");
        assertFalse(handler.canProvide());
        handler.cleanup();
    }

    @Test
    void anUnreadableFilePlaysNothingInsteadOfThrowing() {
        WavFileSendHandler handler = new WavFileSendHandler(dir.resolve("does-not-exist.wav").toFile());

        assertFalse(handler.canProvide());
        assertDoesNotThrow(handler::cleanup);
    }

    @Test
    void theRecorderWritesAPlayableWavFile() throws Exception {
        File recording = dir.resolve("out").resolve("recording.wav").toFile();
        WavRecordingReceiveHandler handler = new WavRecordingReceiveHandler(recording);

        assertTrue(handler.canReceiveCombined());
        assertFalse(handler.canReceiveUser(), "one mixed stream, not one file per speaker");

        // Two big-endian samples, as JDA delivers them.
        handler.write(new byte[]{0x12, 0x34, 0x56, 0x78});
        handler.cleanup();

        assertEquals(WavRecordingReceiveHandler.HEADER_SIZE + 4, Files.size(recording.toPath()));

        try (AudioInputStream in = AudioSystem.getAudioInputStream(recording)) {
            AudioFormat format = in.getFormat();
            assertEquals(48000f, format.getSampleRate());
            assertEquals(16, format.getSampleSizeInBits());
            assertEquals(2, format.getChannels());
            assertFalse(format.isBigEndian(), "WAV stores little-endian samples");

            byte[] samples = in.readAllBytes();
            assertArrayEquals(new byte[]{0x34, 0x12, 0x78, 0x56}, samples,
                    "each 16-bit sample is byte-swapped from JDA's big-endian PCM");
        }
    }

    @Test
    void aRecordingThatWasNeverStoppedKeepsAZeroLengthHeader() throws Exception {
        File recording = dir.resolve("unfinished.wav").toFile();
        WavRecordingReceiveHandler handler = new WavRecordingReceiveHandler(recording);

        handler.write(new byte[]{1, 2, 3, 4});
        // no cleanup() on purpose

        byte[] header = Files.readAllBytes(recording.toPath());
        assertEquals(0, header[40] | header[41] | header[42] | header[43],
                "the data length is only written by cleanup(): that is why it must be called");
        handler.cleanup();
    }

    @Test
    void cleanupIsIdempotentAndCountsWhatWasWritten() throws Exception {
        File recording = dir.resolve("counted.wav").toFile();
        WavRecordingReceiveHandler handler = new WavRecordingReceiveHandler(recording);

        handler.write(new byte[64]);
        handler.write(new byte[32]);
        assertEquals(96, handler.getDataSize());

        handler.cleanup();
        assertDoesNotThrow(handler::cleanup);
        handler.write(new byte[8]);
        assertEquals(96, handler.getDataSize(), "nothing is written after the file is closed");
    }

    @Test
    void anImpossibleOutputFileDisablesTheRecorderInsteadOfThrowing() {
        // A directory cannot be opened as a file.
        WavRecordingReceiveHandler handler = new WavRecordingReceiveHandler(dir.toFile());

        assertFalse(handler.canReceiveCombined());
        assertDoesNotThrow(() -> handler.write(new byte[]{1, 2}));
        assertDoesNotThrow(handler::cleanup);
    }
}
