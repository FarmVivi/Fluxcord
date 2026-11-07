package fr.farmvivi.fluxcord.plugins.aiaudio.transcription;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The measurement that tells microphone noise from speech, which is the last place the two still differ once
 * a transcriber has written the bot's own name over the noise.
 *
 * <p>The numbers below are not preferences: full-scale RMS is 0 dBFS by definition, and a sine at a tenth of
 * full scale is 20·log₁₀(0.1/√2) ≈ -23 dBFS. They are checked because the threshold in {@code config.yml} is
 * expressed in this unit, so an error of a factor of two here silently moves the gate by 6 dB.
 */
class LoudnessTest {

    private static final int RATE = 16_000;

    @Test
    void digitalSilenceReadsAsTheFloorRatherThanNegativeInfinity() {
        // Not a cosmetic choice: a log line reading "-Infinity dBFS" is what a dropped segment would have
        // explained itself with.
        assertEquals(Loudness.SILENT_DBFS, Loudness.dbfs(tone(0.0, 1000)));
    }

    @Test
    void emptyAudioIsSilent() {
        assertEquals(Loudness.SILENT_DBFS, Loudness.dbfs(new PcmAudio(new byte[0], RATE, 1)));
        assertEquals(Loudness.SILENT_DBFS, Loudness.dbfs(null));
    }

    @Test
    void aSingleOddByteIsNotHalfASample() {
        // Read as a sample it would pair with whatever follows it, which is nothing - and the level of a
        // one-byte buffer would come out of uninitialised memory rather than out of the audio.
        assertEquals(Loudness.SILENT_DBFS, Loudness.dbfs(new PcmAudio(new byte[]{0x7F}, RATE, 1)));
    }

    @Test
    void fullScaleIsZeroDbfs() {
        // A square wave at full scale: every sample is the maximum, so RMS is full scale.
        byte[] samples = new byte[2000];
        ByteBuffer buffer = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            buffer.putShort((short) 32767);
        }
        assertEquals(0.0, Loudness.dbfs(new PcmAudio(samples, RATE, 1)), 0.01);
    }

    @Test
    void aSineAtATenthOfFullScaleIsAboutMinusTwentyThreeDbfs() {
        assertEquals(-23.0, Loudness.dbfs(tone(0.1, 8000)), 0.5);
    }

    @Test
    void speechLevelPassesTheDefaultFloorAndNoiseDoesNot() {
        // The two sides of the gate, at the amplitudes they actually occur at. -50 dBFS is the shipped
        // floor; a quiet speaker at a hundredth of full scale is -43 dBFS and must survive it, while the
        // sub-second noise burst this exists for sits far below.
        assertTrue(Loudness.dbfs(tone(0.01, 8000)) > -50.0, "a quiet question must still be transcribed");
        assertTrue(Loudness.dbfs(tone(0.001, 8000)) < -50.0, "noise at a thousandth of full scale must not");
    }

    @Test
    void negativeSamplesCountAsMuchAsPositiveOnes() {
        // Squared, so sign cannot matter - but the sample is decoded by hand from two bytes, and reading
        // the high byte unsigned would make every negative sample a large positive one.
        byte[] samples = new byte[400];
        ByteBuffer buffer = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            buffer.putShort((short) -16384);
        }
        assertEquals(-6.0, Loudness.dbfs(new PcmAudio(samples, RATE, 1)), 0.1);
    }

    /** A sine wave of the given amplitude, as a fraction of full scale. */
    private static PcmAudio tone(double amplitude, int frames) {
        byte[] samples = new byte[frames * 2];
        ByteBuffer buffer = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            double value = Math.sin(2 * Math.PI * 440 * i / RATE) * amplitude * 32767;
            buffer.putShort((short) Math.round(value));
        }
        return new PcmAudio(samples, RATE, 1);
    }
}
