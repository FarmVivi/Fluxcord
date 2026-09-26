package fr.farmvivi.fluxcord.api.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The format bridge between the AI providers and Discord. Everything here is arithmetic on bytes, so it
 * is checked exactly rather than approximately — a silent mistake in this class comes out as noise in a
 * voice channel, which no other test would catch.
 */
class PcmAudioTest {

    /** A WAV holding the given 16-bit samples, the way a provider would answer. */
    private static byte[] wav(int sampleRate, int channels, short... samples) {
        ByteBuffer pcm = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) {
            pcm.putShort(sample);
        }
        return new PcmAudio(pcm.array(), sampleRate, channels).toWav();
    }

    private static short[] samplesOf(PcmAudio audio) {
        ByteBuffer buffer = ByteBuffer.wrap(audio.samples()).order(ByteOrder.LITTLE_ENDIAN);
        short[] samples = new short[audio.samples().length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = buffer.getShort();
        }
        return samples;
    }

    @Test
    void aWavWrittenHereReadsBackIdentically() {
        PcmAudio parsed = PcmAudio.fromWav(wav(24_000, 1, (short) 1, (short) -2, (short) 300));

        assertEquals(24_000, parsed.sampleRate());
        assertEquals(1, parsed.channels());
        assertArrayEquals(new short[]{1, -2, 300}, samplesOf(parsed));
    }

    @Test
    void aChunkBetweenFmtAndDataIsSkippedInsteadOfPlayed() {
        // Servers routinely insert a LIST/INFO chunk. Reading it as audio is a burst of noise.
        byte[] plain = wav(48_000, 1, (short) 7, (short) 8);
        ByteBuffer withList = ByteBuffer.allocate(plain.length + 12).order(ByteOrder.LITTLE_ENDIAN);
        withList.put(plain, 0, 36);                  // up to the end of the fmt chunk
        withList.putInt(0x5453494c);                 // "LIST"
        withList.putInt(4);
        withList.putInt(0x4f464e49);                 // "INFO"
        withList.put(plain, 36, plain.length - 36);  // then the real data chunk
        // The RIFF size is now stale, which is exactly what a streamed answer looks like.
        PcmAudio parsed = PcmAudio.fromWav(withList.array());

        assertArrayEquals(new short[]{7, 8}, samplesOf(parsed));
    }

    @Test
    void aDataChunkLongerThanTheFileIsTruncatedRatherThanThrowing() {
        byte[] file = wav(48_000, 1, (short) 5, (short) 6);
        ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 4096); // lie about the data size

        assertArrayEquals(new short[]{5, 6}, samplesOf(PcmAudio.fromWav(file)));
    }

    @Test
    void anythingThatIsNotSixteenBitPcmIsRefusedWithAReason() {
        byte[] eightBit = wav(48_000, 1, (short) 1);
        ByteBuffer.wrap(eightBit).order(ByteOrder.LITTLE_ENDIAN).putShort(34, (short) 8);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> PcmAudio.fromWav(eightBit))
                .getMessage().contains("16-bit"));

        byte[] compressed = wav(48_000, 1, (short) 1);
        ByteBuffer.wrap(compressed).order(ByteOrder.LITTLE_ENDIAN).putShort(20, (short) 3);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> PcmAudio.fromWav(compressed))
                .getMessage().contains("PCM"));

        assertThrows(IllegalArgumentException.class, () -> PcmAudio.fromWav("not audio at all".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> PcmAudio.fromWav(null));
    }

    @Test
    void doublingTheRateAndTheChannelsIsWhatDiscordNeeds() {
        // 24 kHz mono is what OpenAI answers: twice the frames, each duplicated across both channels.
        PcmAudio mono = new PcmAudio(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 100).putShort((short) 200).array(), 24_000, 1);

        PcmAudio discord = mono.toDiscordFormat();

        assertEquals(PcmAudio.DISCORD_SAMPLE_RATE, discord.sampleRate());
        assertEquals(PcmAudio.DISCORD_CHANNELS, discord.channels());
        // Two input frames at half the rate give four output frames, stereo: eight samples.
        assertArrayEquals(new short[]{100, 100, 150, 150, 200, 200, 200, 200}, samplesOf(discord));
    }

    @Test
    void audioAlreadyInTheTargetFormatIsNotCopied() {
        PcmAudio ready = new PcmAudio(new byte[8], PcmAudio.DISCORD_SAMPLE_RATE, PcmAudio.DISCORD_CHANNELS);

        assertSame(ready, ready.toDiscordFormat());
    }

    @Test
    void stereoBecomesMonoByAveragingRatherThanBySumming() {
        // Summing two loud channels would clip; averaging keeps the level.
        PcmAudio stereo = new PcmAudio(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 30_000).putShort((short) 20_000).array(), 16_000, 2);

        assertArrayEquals(new short[]{25_000}, samplesOf(stereo.resample(16_000, 1)));
    }

    @Test
    void downsamplingToWhatWhisperWantsKeepsOneFrameInThree() {
        short[] input = new short[48 * 2]; // 48 stereo frames at 48 kHz
        for (int i = 0; i < input.length; i++) {
            input[i] = (short) i;
        }
        PcmAudio discord = PcmAudio.fromWav(wav(48_000, 2, input));

        PcmAudio forWhisper = discord.resample(16_000, 1);

        assertEquals(16_000, forWhisper.sampleRate());
        assertEquals(1, forWhisper.channels());
        assertEquals(16, samplesOf(forWhisper).length, "a third of the frames, mono");
        // First output frame is the average of the first input frame's two channels: (0 + 1) / 2.
        assertEquals(1, samplesOf(forWhisper)[0]);
    }

    @Test
    void emptyAudioConvertsToEmptyAudioInsteadOfFailing() {
        PcmAudio empty = new PcmAudio(new byte[0], 24_000, 1);

        assertTrue(empty.isEmpty());
        assertTrue(empty.toDiscordFormat().isEmpty());
        assertEquals(java.time.Duration.ZERO, empty.duration());
    }

    @Test
    void receivedAudioIsByteSwappedBecauseJdaDeliversBigEndian() {
        // 0x0102 big-endian must read as 0x0102 little-endian, not 0x0201.
        PcmAudio swapped = PcmAudio.fromBigEndian(new byte[]{0x01, 0x02, (byte) 0xFF, (byte) 0xFE},
                PcmAudio.DISCORD_SAMPLE_RATE, PcmAudio.DISCORD_CHANNELS);

        assertArrayEquals(new short[]{0x0102, (short) 0xFFFE}, samplesOf(swapped));
    }

    @Test
    void anOddTrailingByteIsDroppedRatherThanShiftingEverySample() {
        PcmAudio swapped = PcmAudio.fromBigEndian(new byte[]{0x01, 0x02, 0x03},
                PcmAudio.DISCORD_SAMPLE_RATE, PcmAudio.DISCORD_CHANNELS);

        assertArrayEquals(new short[]{0x0102}, samplesOf(swapped));
    }

    @Test
    void durationIsReadFromTheFormatAndNotFromTheByteCount() {
        // One second of 48 kHz stereo: 48000 frames x 2 channels x 2 bytes.
        PcmAudio second = new PcmAudio(new byte[48_000 * 4], 48_000, 2);

        assertEquals(java.time.Duration.ofSeconds(1), second.duration());
    }

    @Test
    void anImpossibleFormatIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new PcmAudio(new byte[2], 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PcmAudio(new byte[2], 48_000, 3));
        assertThrows(IllegalArgumentException.class, () -> new PcmAudio(null, 48_000, 1));
        PcmAudio audio = new PcmAudio(new byte[2], 48_000, 1);
        assertThrows(IllegalArgumentException.class, () -> audio.resample(0, 1));
        assertThrows(IllegalArgumentException.class, () -> audio.resample(48_000, 5));
    }

    @Test
    void twoBlocksCarryingTheSameAudioAreEqual() {
        // A record holding an array compares it by identity, which makes a value type behave like a
        // reference: two identical blocks would be unequal, and a set would hold both.
        PcmAudio first = new PcmAudio(new byte[]{1, 2, 3, 4}, 48_000, 2);
        PcmAudio same = new PcmAudio(new byte[]{1, 2, 3, 4}, 48_000, 2);

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        // Set.of would throw on the duplicate, which is itself proof; a HashSet states it as a size.
        assertEquals(1, new java.util.HashSet<>(java.util.List.of(first, same)).size());
    }

    @Test
    void blocksDifferingInAnyWayAreNotEqual() {
        PcmAudio reference = new PcmAudio(new byte[]{1, 2, 3, 4}, 48_000, 2);

        assertNotEquals(reference, new PcmAudio(new byte[]{1, 2, 3, 5}, 48_000, 2));
        assertNotEquals(reference, new PcmAudio(new byte[]{1, 2, 3, 4}, 24_000, 2));
        assertNotEquals(reference, new PcmAudio(new byte[]{1, 2, 3, 4}, 48_000, 1));
        assertNotEquals(reference, "not audio");
        assertNotEquals(null, reference);
    }

    @Test
    void printingAudioSummarisesItInsteadOfDumpingIt() {
        // The generated toString would print the array's identity; printing its contents would put
        // megabytes in a log line.
        String printed = new PcmAudio(new byte[48_000 * 4], 48_000, 2).toString();

        assertTrue(printed.contains("48000 Hz"), printed);
        assertTrue(printed.contains("2 ch"), printed);
        assertTrue(printed.contains("192000 bytes"), printed);
        assertTrue(printed.length() < 100, "a summary, not the samples");
    }
}

