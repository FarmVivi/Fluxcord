package fr.farmvivi.fluxcord.plugins.aiaudio.transcription;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Keeps what is not somebody's voice out of a stream that is billed by the second.
 *
 * <p>{@link Loudness} measures a whole utterance, after the local segmenter has cut one — which is the wrong
 * shape for the hosted session, because there the packets go straight out as they arrive and every one of
 * them is paid for as audio input. So the same question has to be asked per packet, and asking it per packet
 * is a different problem: speech is not continuously loud. A plosive, the gap between two syllables and the
 * tail of a word all dip far below the level of the vowel before them, so a bare threshold on a 20 ms frame
 * does not separate voice from noise — it chops words into pieces.
 *
 * <p>Hence the two things around the threshold, which are what makes this a gate rather than a comparison:
 *
 * <ul>
 *   <li><strong>hold</strong> — once a loud packet has been seen, everything is let through for a while
 *       after the last one. The dips inside a word are shorter than the hold, so a word crosses the gate
 *       whole.
 *   <li><strong>pre-roll</strong> — the packets below the threshold are kept, a bounded few, and released
 *       the moment the gate opens. Without it the gate itself clips the attack of every sentence: the
 *       consonant that opens a word is quieter than the vowel that follows, so it is always the part that
 *       fails the test, and a service handed "onjour" has been given a worse recording than one handed the
 *       noise.
 * </ul>
 *
 * <p>MEASURED, 2026-11-07: with the audio ungated, a session held open on microphone noise alone transcribed
 * {@code <noise>}, Thai and Telugu into an empty channel six times in 40 seconds, each one resetting the idle
 * window — about 49 seconds of a paid session bought with nothing said. The noise was not a transcription
 * problem: it was audio nobody should have paid to send. Discord's own voice detection is what decides that
 * a packet is delivered at all, and it is happy to call a chair or a fan speech.
 */
public final class VoiceGate {

    /**
     * How long everything is let through after the last loud packet.
     *
     * <p>Longer than the gaps inside speech and shorter than the pause that ends a sentence. 400 ms sits
     * between the two: no plosive is that long, and the turn-closing silence is three times it.
     */
    public static final long DEFAULT_HOLD_MS = 400;

    /**
     * How many quiet packets are kept to be released when the gate opens.
     *
     * <p>Ten, which at Discord's 20 ms packets is 200 ms — enough to carry the consonant and the breath
     * before a word, bounded so a silent hour costs ten packets of memory rather than an hour of them.
     */
    public static final int DEFAULT_PREROLL_PACKETS = 10;

    private final double thresholdDbfs;
    private final long holdMs;
    private final int prerollPackets;
    private final Deque<PcmAudio> preroll = new ArrayDeque<>();

    /** When the hold expires; 0 before the first loud packet. */
    private long openUntilMs;

    /**
     * @param thresholdDbfs the level below which a packet is not voice, as {@link Loudness#dbfs}
     * @param holdMs        how long to keep letting packets through after the last loud one
     * @param prerollPackets how many quiet packets to keep for the moment the gate opens
     */
    public VoiceGate(double thresholdDbfs, long holdMs, int prerollPackets) {
        this.thresholdDbfs = thresholdDbfs;
        this.holdMs = Math.max(0, holdMs);
        this.prerollPackets = Math.max(0, prerollPackets);
    }

    /** A gate with the shipped hold and pre-roll, which is what every caller so far wants. */
    public VoiceGate(double thresholdDbfs) {
        this(thresholdDbfs, DEFAULT_HOLD_MS, DEFAULT_PREROLL_PACKETS);
    }

    /**
     * Offers one packet to the gate.
     *
     * @param packet the captured chunk, in any format
     * @param nowMs  the current time in milliseconds
     * @return what to send on, oldest first: nothing while the gate is shut, the packet while it is open,
     *         and the pre-roll followed by the packet at the moment it opens
     */
    public List<PcmAudio> pass(PcmAudio packet, long nowMs) {
        if (packet == null || packet.isEmpty()) {
            return List.of();
        }
        if (Loudness.dbfs(packet) >= thresholdDbfs) {
            openUntilMs = nowMs + holdMs;
            if (preroll.isEmpty()) {
                return List.of(packet);
            }
            List<PcmAudio> out = new ArrayList<>(preroll.size() + 1);
            out.addAll(preroll);
            preroll.clear();
            out.add(packet);
            return out;
        }
        if (nowMs < openUntilMs) {
            // Inside a word, not at the end of one. The hold is the whole reason this is not a per-packet
            // threshold, so a quiet packet here is sent without being remembered as pre-roll.
            return List.of(packet);
        }
        if (prerollPackets > 0) {
            preroll.addLast(packet);
            while (preroll.size() > prerollPackets) {
                preroll.removeFirst();
            }
        }
        return List.of();
    }

    /** @return true while packets are being let through, which is what a caller logs rather than acts on */
    public boolean isOpen(long nowMs) {
        return nowMs < openUntilMs;
    }

    /**
     * Forgets everything, for a session that is starting over.
     *
     * <p>The pre-roll especially: packets kept from before a diversion belong to a conversation that is
     * over, and releasing them into the next one would open it with somebody else's half-word.
     */
    public void reset() {
        preroll.clear();
        openUntilMs = 0;
    }
}
