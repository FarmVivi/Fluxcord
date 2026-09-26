package fr.farmvivi.fluxcord.plugins.aiaudio.persona;

import java.time.Duration;

/**
 * How the bot currently feels, on two axes, with the time it was last touched.
 *
 * <p>Two axes rather than a list of named emotions: a name ("angry") cannot be moved a little, and a
 * conversation moves a mood gradually. {@link #energy()} runs from calm to excited and {@link #warmth()}
 * from cold to friendly, both in {@code [-1, 1]}, and {@link #label()} names the quadrant for anyone who
 * needs a word.
 *
 * <p>A mood <strong>decays back to neutral</strong>: without that, one heated exchange would colour every
 * conversation in that channel for good. Decay is computed from the elapsed time when the mood is read, not
 * by a timer — nothing has to run while a channel is quiet.
 *
 * <p>Immutable: every change returns a new mood, so a stored value can never be mutated by whoever reads it.
 *
 * @param energy      calm (-1) to excited (+1)
 * @param warmth      cold (-1) to friendly (+1)
 * @param updatedAtMs when it was last nudged, epoch milliseconds
 */
public record Mood(double energy, double warmth, long updatedAtMs) {

    /** How long a mood takes to fade halfway back to neutral. */
    public static final Duration HALF_LIFE = Duration.ofMinutes(20);

    /** Below this distance from neutral, a mood is not worth mentioning. */
    private static final double NEUTRAL_THRESHOLD = 0.15;

    public Mood {
        energy = clamp(energy);
        warmth = clamp(warmth);
    }

    /**
     * @param nowMs the current time in milliseconds
     * @return a mood with nothing in it yet
     */
    public static Mood neutral(long nowMs) {
        return new Mood(0, 0, nowMs);
    }

    /**
     * Moves the mood, from the current one as it stands <em>after</em> decay.
     *
     * <p>Nudging a stale mood without decaying it first would let an old state compound; the deltas are
     * meant to be small, so several turns in the same direction add up while a single one does not swing it.
     *
     * @param energyDelta how much more excited (positive) or calm (negative)
     * @param warmthDelta how much friendlier (positive) or colder (negative)
     * @param nowMs       the current time in milliseconds
     * @return the moved mood
     */
    public Mood nudged(double energyDelta, double warmthDelta, long nowMs) {
        Mood current = at(nowMs);
        return new Mood(current.energy + energyDelta, current.warmth + warmthDelta, nowMs);
    }

    /**
     * This mood as it stands at {@code nowMs}, faded towards neutral.
     *
     * @param nowMs the current time in milliseconds
     * @return the decayed mood, keeping the original timestamp
     */
    public Mood at(long nowMs) {
        long elapsed = nowMs - updatedAtMs;
        if (elapsed <= 0) {
            return this;
        }
        double factor = Math.pow(0.5, (double) elapsed / HALF_LIFE.toMillis());
        return new Mood(energy * factor, warmth * factor, updatedAtMs);
    }

    /** @return true when the mood is close enough to neutral that it says nothing */
    public boolean isNeutral() {
        return Math.abs(energy) < NEUTRAL_THRESHOLD && Math.abs(warmth) < NEUTRAL_THRESHOLD;
    }

    /**
     * A word for the current quadrant.
     *
     * <p>Only for logs, {@code /persona show} and as one field of what a model is told — never as the mood
     * itself, which stays the two numbers.
     *
     * @return one of {@code neutral}, {@code enthusiastic}, {@code tense}, {@code warm} or {@code distant}
     */
    public String label() {
        if (isNeutral()) {
            return "neutral";
        }
        if (Math.abs(energy) >= Math.abs(warmth)) {
            return energy > 0 ? (warmth < -NEUTRAL_THRESHOLD ? "tense" : "enthusiastic") : "subdued";
        }
        return warmth > 0 ? "warm" : "distant";
    }

    private static double clamp(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return Math.max(-1, Math.min(1, value));
    }
}
