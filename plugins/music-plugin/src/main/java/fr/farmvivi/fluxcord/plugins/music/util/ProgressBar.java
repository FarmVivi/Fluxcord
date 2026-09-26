package fr.farmvivi.fluxcord.plugins.music.util;

/**
 * The little "▬▬🔘▬▬" bar showing where a track is.
 *
 * <p>One implementation, because there were two and they had drifted: the player message divided by the
 * duration with no guard (a zero or unknown duration — which is what LavaPlayer reports for a live stream
 * — threw from inside the refresh loop) and drew the played and remaining halves with the same character,
 * so the bar never actually showed progress.
 */
public final class ProgressBar {

    /** Characters in the bar. Twenty fits a Discord embed line at any client width. */
    public static final int LENGTH = 20;

    private static final String KNOB = "🔘";
    private static final String TRACK = "▬";

    private ProgressBar() {
    }

    /**
     * Renders the bar for a position inside a duration.
     *
     * @param positionMs how far into the track, in milliseconds
     * @param durationMs the track's length in milliseconds; zero, negative or
     *                   {@code Long.MAX_VALUE}-style unknown durations keep the knob at the start, since a
     *                   live stream has no end to be a fraction of
     * @return a bar of exactly {@link #LENGTH} characters
     */
    public static String of(long positionMs, long durationMs) {
        StringBuilder bar = new StringBuilder(LENGTH);
        int knob = knobIndex(positionMs, durationMs);
        for (int i = 0; i < LENGTH; i++) {
            bar.append(i == knob ? KNOB : TRACK);
        }
        return bar.toString();
    }

    /**
     * @return where the knob goes, always within the bar
     */
    static int knobIndex(long positionMs, long durationMs) {
        if (durationMs <= 0 || positionMs <= 0) {
            return 0;
        }
        // Clamped to the last character: at position == duration the knob would otherwise fall off the end
        // and the bar would show no knob at all.
        long index = (positionMs * LENGTH) / durationMs;
        return (int) Math.min(LENGTH - 1L, Math.max(0L, index));
    }
}
