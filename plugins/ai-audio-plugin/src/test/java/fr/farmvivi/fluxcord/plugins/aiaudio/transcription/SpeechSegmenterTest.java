package fr.farmvivi.fluxcord.plugins.aiaudio.transcription;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Where the cost of transcription is decided: one request per closed utterance. Time is injected, so
 * these tests describe seconds of conversation without waiting for any.
 */
class SpeechSegmenterTest {

    private static final Duration SILENCE = Duration.ofMillis(1000);
    private static final Duration MAX = Duration.ofSeconds(10);
    private static final Duration MIN = Duration.ofMillis(400);

    private final SpeechSegmenter segmenter = new SpeechSegmenter(SILENCE, MAX, MIN);

    /** {@code millis} of 48 kHz stereo audio, as JDA would deliver it. */
    private static PcmAudio speech(int millis) {
        return new PcmAudio(new byte[48 * millis * 4], PcmAudio.DISCORD_SAMPLE_RATE, PcmAudio.DISCORD_CHANNELS);
    }

    @Test
    void nothingIsReadyWhileSomeoneIsStillTalking() {
        segmenter.accept("u1", speech(500), 1000);

        assertTrue(segmenter.poll(1500).isEmpty(), "only half the silence has passed");
        assertEquals(1, segmenter.pendingSpeakers());
    }

    @Test
    void anUtteranceIsClosedOnceTheSpeakerHasBeenQuietLongEnough() {
        segmenter.accept("u1", speech(600), 1000);

        List<SpeechSegmenter.Segment> ready = segmenter.poll(2000);

        assertEquals(1, ready.size());
        assertEquals("u1", ready.get(0).userId());
        assertEquals(SpeechSegmenter.TRANSCRIPTION_SAMPLE_RATE, ready.get(0).audio().sampleRate());
        assertEquals(SpeechSegmenter.TRANSCRIPTION_CHANNELS, ready.get(0).audio().channels());
        assertEquals(0, segmenter.pendingSpeakers(), "and it is not handed out twice");
        assertTrue(segmenter.poll(3000).isEmpty());
    }

    @Test
    void twoPeopleTalkingAtOnceProduceTwoSeparateUtterances() {
        // The reason reception is per user: one shared buffer would interleave them into nonsense.
        segmenter.accept("u1", speech(600), 1000);
        segmenter.accept("u2", speech(600), 1000);

        List<SpeechSegmenter.Segment> ready = segmenter.poll(2100);

        assertEquals(2, ready.size());
        assertEquals(List.of("u1", "u2"), ready.stream().map(SpeechSegmenter.Segment::userId).sorted().toList());
    }

    @Test
    void aSpeakerWhoNeverPausesIsStillTranscribed() {
        // Without this, someone reading a text out loud would be buffered forever.
        for (int elapsed = 0; elapsed <= 10_000; elapsed += 500) {
            segmenter.accept("u1", speech(500), 1000 + elapsed);
        }

        List<SpeechSegmenter.Segment> ready = segmenter.poll(11_100);

        assertEquals(1, ready.size());
        assertTrue(ready.get(0).audio().duration().compareTo(Duration.ofSeconds(9)) > 0);
    }

    @Test
    void aClickOrACoughIsDroppedInsteadOfCostingARequest() {
        segmenter.accept("u1", speech(100), 1000);

        assertTrue(segmenter.poll(2100).isEmpty(), "shorter than the minimum");
        assertEquals(0, segmenter.pendingSpeakers(), "and the buffer is released");
    }

    @Test
    void stoppingTranscriptionStillTranscribesTheLastSentence() {
        segmenter.accept("u1", speech(600), 1000);

        List<SpeechSegmenter.Segment> ready = segmenter.flush();

        assertEquals(1, ready.size());
        assertEquals(0, segmenter.pendingSpeakers());
    }

    @Test
    void flushingStillDropsWhatIsTooShortToBeSpeech() {
        segmenter.accept("u1", speech(50), 1000);

        assertTrue(segmenter.flush().isEmpty());
    }

    @Test
    void clearingThrowsAwayWhatWasBufferedWithoutTranscribingIt() {
        segmenter.accept("u1", speech(600), 1000);

        segmenter.clear();

        assertEquals(0, segmenter.pendingSpeakers());
        assertTrue(segmenter.flush().isEmpty());
    }

    @Test
    void silentPacketsAreStillAudioAndCountTowardsTheUtterance() {
        // JDA delivers frames while someone holds an open mic; the model decides whether it is speech.
        segmenter.accept("u1", speech(600), 1000);
        segmenter.accept("u1", speech(600), 1400);

        List<SpeechSegmenter.Segment> ready = segmenter.poll(2500);

        assertEquals(1, ready.size());
        assertEquals(Duration.ofMillis(1200), ready.get(0).audio().duration());
    }

    @Test
    void emptyAudioIsIgnoredRatherThanOpeningAnUtterance() {
        segmenter.accept("u1", new PcmAudio(new byte[0], 48_000, 2), 1000);

        assertEquals(0, segmenter.pendingSpeakers());
    }

    @Test
    void anUtteranceThatCouldNeverEndIsRefusedAtConstruction() {
        // maxSegment <= silence would cut every utterance before its silence could close it.
        assertThrows(IllegalArgumentException.class,
                () -> new SpeechSegmenter(Duration.ofSeconds(2), Duration.ofSeconds(1), MIN));
        assertThrows(IllegalArgumentException.class,
                () -> new SpeechSegmenter(Duration.ZERO, MAX, MIN));
    }
}
