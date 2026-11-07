package fr.farmvivi.fluxcord.plugins.aiaudio.transcription;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gate on the stream the hosted service bills by the second.
 *
 * <p>Every case here is one of the three things that make it a gate rather than a threshold: it opens on
 * voice, it <em>stays</em> open across the quiet moments inside a word, and it hands over what it was
 * holding so the first consonant of a sentence is not the part it eats.
 */
class VoiceGateTest {

    private static final double FLOOR = -50.0;
    private static final PcmAudio LOUD = frame(0.2);
    private static final PcmAudio QUIET = frame(0.0);

    @Test
    void aLoudPacketOpensTheGateAndGoesThrough() {
        VoiceGate gate = new VoiceGate(FLOOR);

        assertEquals(List.of(LOUD), gate.pass(LOUD, 1000));
        assertTrue(gate.isOpen(1000));
    }

    @Test
    void silenceAloneNeverOpensIt() {
        VoiceGate gate = new VoiceGate(FLOOR);

        for (int i = 0; i < 50; i++) {
            assertTrue(gate.pass(QUIET, i * 20).isEmpty(), "a silent room costs nothing to listen to");
        }
        assertFalse(gate.isOpen(1000));
    }

    @Test
    void theQuietInsideAWordStillGoesThrough() {
        // The whole reason this is not a per-packet threshold: a plosive and the gap between two syllables
        // are quieter than the vowel before them, and a gate that shut on them would deliver a word in
        // pieces.
        VoiceGate gate = new VoiceGate(FLOOR, 400, 0);
        gate.pass(LOUD, 1000);

        assertEquals(List.of(QUIET), gate.pass(QUIET, 1100));
        assertEquals(List.of(QUIET), gate.pass(QUIET, 1399), "399 ms is inside the 400 ms hold");
    }

    @Test
    void theHoldExpiresSoAPauseCostsNothing() {
        VoiceGate gate = new VoiceGate(FLOOR, 400, 0);
        gate.pass(LOUD, 1000);

        assertTrue(gate.pass(QUIET, 1400).isEmpty(), "the hold is over at exactly 400 ms");
        assertFalse(gate.isOpen(1400));
    }

    @Test
    void aLoudPacketInsideTheHoldPushesItBack() {
        VoiceGate gate = new VoiceGate(FLOOR, 400, 0);
        gate.pass(LOUD, 1000);
        gate.pass(LOUD, 1300);

        assertTrue(gate.isOpen(1600), "measured from the last loud packet, not the first");
        assertFalse(gate.isOpen(1700));
    }

    @Test
    void theGateHandsOverWhatItWasHoldingWhenItOpens() {
        // Without this the gate clips the attack of every sentence: the consonant that opens a word is
        // quieter than the vowel after it, so it is always the part that fails the test.
        VoiceGate gate = new VoiceGate(FLOOR, 400, 3);
        assertTrue(gate.pass(QUIET, 0).isEmpty());
        assertTrue(gate.pass(QUIET, 20).isEmpty());

        List<PcmAudio> out = gate.pass(LOUD, 40);

        assertEquals(List.of(QUIET, QUIET, LOUD), out, "oldest first, with the voice last");
    }

    @Test
    void theHeldPacketsAreBoundedSoASilentHourCostsTen() {
        VoiceGate gate = new VoiceGate(FLOOR, 400, 3);
        for (int i = 0; i < 1000; i++) {
            gate.pass(QUIET, i * 20);
        }

        assertEquals(4, gate.pass(LOUD, 20_000).size(), "three kept plus the loud one");
    }

    @Test
    void openingAgainAfterTheHoldStillCarriesThePreRoll() {
        VoiceGate gate = new VoiceGate(FLOOR, 400, 2);
        gate.pass(LOUD, 1000);
        gate.pass(QUIET, 1500);  // past the hold, so this one is kept rather than sent

        assertEquals(List.of(QUIET, LOUD), gate.pass(LOUD, 1520));
    }

    @Test
    void resetDropsWhatWasHeldAndShutsTheGate() {
        VoiceGate gate = new VoiceGate(FLOOR, 400, 3);
        gate.pass(LOUD, 1000);
        gate.pass(QUIET, 1500);

        gate.reset();

        assertFalse(gate.isOpen(1501));
        assertEquals(List.of(LOUD), gate.pass(LOUD, 1520),
                "a reopened session must not start with the tail of the last one");
    }

    @Test
    void anEmptyOrMissingPacketIsNotAudio() {
        VoiceGate gate = new VoiceGate(FLOOR);

        assertTrue(gate.pass(null, 0).isEmpty());
        assertTrue(gate.pass(new PcmAudio(new byte[0], 48_000, 2), 0).isEmpty());
    }

    @Test
    void aFloorAtTheSilenceValueLetsEverythingThrough() {
        // How the class behaves for a caller that says nothing about a gate, which has to be the behaviour
        // there was before there was one.
        VoiceGate open = new VoiceGate(Loudness.SILENT_DBFS);

        assertEquals(List.of(QUIET), open.pass(QUIET, 0));
    }

    /** One 20 ms Discord packet: 48 kHz stereo, a sine at the given fraction of full scale. */
    private static PcmAudio frame(double amplitude) {
        byte[] samples = new byte[PcmAudio.DISCORD_FRAME_SIZE];
        ByteBuffer buffer = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; buffer.remaining() >= 2; i++) {
            buffer.putShort((short) Math.round(
                    Math.sin(2 * Math.PI * 220 * i / 48_000.0) * amplitude * 32767));
        }
        return new PcmAudio(samples, PcmAudio.DISCORD_SAMPLE_RATE, PcmAudio.DISCORD_CHANNELS);
    }
}
