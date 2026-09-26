package fr.farmvivi.fluxcord.plugins.aiaudio.transcription;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cuts a continuous voice stream into utterances worth transcribing, one buffer per speaker.
 *
 * <p>Transcription endpoints are billed and rate-limited per request, so sending every 20 ms packet is
 * out of the question: audio accumulates per user and a segment is closed when that user has been quiet
 * for {@code silence}, or when the segment reaches {@code maxSegment} — otherwise someone talking
 * without pause would never be transcribed.
 *
 * <p>Time is passed in rather than read from a clock, which keeps this class pure and its tests
 * instantaneous. It is the caller's job to poll: JDA only delivers packets while someone is speaking,
 * so silence is an absence of calls, never an event.
 *
 * <p>Audio is converted to the transcription format as it arrives. Keeping 48 kHz stereo until the end
 * would buffer 192 KB per second and per speaker for no benefit — speech models resample to 16 kHz mono
 * anyway.
 */
public class SpeechSegmenter {

    /** What speech models work with, and 6 times smaller than what Discord delivers. */
    public static final int TRANSCRIPTION_SAMPLE_RATE = 16_000;
    /** Mono: which ear heard the speaker is of no use to a transcription. */
    public static final int TRANSCRIPTION_CHANNELS = 1;

    private final Duration silence;
    private final Duration maxSegment;
    private final Duration minSegment;
    private final Map<String, Utterance> utterances = new HashMap<>();

    /**
     * @param silence    how long a speaker must be quiet before their utterance is considered over
     * @param maxSegment the longest utterance to accumulate before cutting it anyway
     * @param minSegment utterances shorter than this are dropped instead of transcribed — a click, a
     *                   keyboard or a cough would otherwise cost a request and produce noise
     */
    public SpeechSegmenter(Duration silence, Duration maxSegment, Duration minSegment) {
        if (silence.isNegative() || silence.isZero()) {
            throw new IllegalArgumentException("silence must be positive");
        }
        if (maxSegment.compareTo(silence) <= 0) {
            throw new IllegalArgumentException("maxSegment must be longer than silence");
        }
        this.silence = silence;
        this.maxSegment = maxSegment;
        this.minSegment = minSegment.isNegative() ? Duration.ZERO : minSegment;
    }

    /**
     * Adds audio from one speaker.
     *
     * @param userId the speaker
     * @param audio  their audio, in any format
     * @param nowMs  the current time in milliseconds
     */
    public synchronized void accept(String userId, PcmAudio audio, long nowMs) {
        PcmAudio converted = audio.resample(TRANSCRIPTION_SAMPLE_RATE, TRANSCRIPTION_CHANNELS);
        if (converted.isEmpty()) {
            return;
        }
        Utterance utterance = utterances.computeIfAbsent(userId, id -> new Utterance(nowMs));
        utterance.buffer.writeBytes(converted.samples());
        utterance.lastPacketMs = nowMs;
    }

    /**
     * Closes the utterances that are finished.
     *
     * @param nowMs the current time in milliseconds
     * @return the segments to transcribe, in no particular order; empty most of the time
     */
    public synchronized List<Segment> poll(long nowMs) {
        List<Segment> ready = new ArrayList<>();
        utterances.entrySet().removeIf(entry -> {
            Utterance utterance = entry.getValue();
            boolean quiet = nowMs - utterance.lastPacketMs >= silence.toMillis();
            boolean tooLong = nowMs - utterance.startedMs >= maxSegment.toMillis();
            if (!quiet && !tooLong) {
                return false;
            }
            close(entry.getKey(), utterance).ifPresent(ready::add);
            return true;
        });
        return ready;
    }

    /**
     * Closes every pending utterance, whatever its length — used when transcription is stopped and the
     * last sentence should still be transcribed.
     *
     * @return the segments to transcribe
     */
    public synchronized List<Segment> flush() {
        List<Segment> ready = new ArrayList<>();
        utterances.forEach((userId, utterance) -> close(userId, utterance).ifPresent(ready::add));
        utterances.clear();
        return ready;
    }

    /** Drops everything buffered without transcribing it. */
    public synchronized void clear() {
        utterances.clear();
    }

    /** @return how many speakers currently have audio buffered */
    public synchronized int pendingSpeakers() {
        return utterances.size();
    }

    private java.util.Optional<Segment> close(String userId, Utterance utterance) {
        PcmAudio audio = new PcmAudio(utterance.buffer.toByteArray(),
                TRANSCRIPTION_SAMPLE_RATE, TRANSCRIPTION_CHANNELS);
        if (audio.duration().compareTo(minSegment) < 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Segment(userId, audio));
    }

    /**
     * One speaker's utterance, ready to be transcribed.
     *
     * @param userId the Discord id of whoever said it
     * @param audio  what they said, at {@link #TRANSCRIPTION_SAMPLE_RATE} mono
     */
    public record Segment(String userId, PcmAudio audio) {
    }

    /** Mutable accumulator for one speaker. */
    private static final class Utterance {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final long startedMs;
        private long lastPacketMs;

        private Utterance(long startedMs) {
            this.startedMs = startedMs;
            this.lastPacketMs = startedMs;
        }
    }
}
