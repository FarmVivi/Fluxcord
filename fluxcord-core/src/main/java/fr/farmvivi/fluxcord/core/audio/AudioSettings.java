package fr.farmvivi.fluxcord.core.audio;

import fr.farmvivi.fluxcord.api.config.Configuration;

/**
 * Tunables of the send pipeline, read once from the {@code audio:} section of {@code config.yml}.
 *
 * @param fadeDurationMs how long a fade-out/fade-in takes when a high-priority source starts or stops
 *                       (rounded down to whole 20 ms frames, minimum one frame)
 * @param duckingLevel   volume (0-100) the other sources are lowered to while a high-priority source
 *                       plays: 20 by default keeps them audible in the background, 0 mutes them
 */
public record AudioSettings(int fadeDurationMs, int duckingLevel) {

    public static final int FRAME_DURATION_MS = 20;
    public static final AudioSettings DEFAULTS = new AudioSettings(200, 20);

    public AudioSettings {
        if (fadeDurationMs < 0) {
            throw new IllegalArgumentException("audio.fade-duration-ms must be >= 0");
        }
        if (duckingLevel < 0 || duckingLevel > 100) {
            throw new IllegalArgumentException("audio.ducking-level must be between 0 and 100");
        }
    }

    public static AudioSettings fromConfig(Configuration config) {
        return new AudioSettings(
                config.getInt("audio.fade-duration-ms", DEFAULTS.fadeDurationMs()),
                config.getInt("audio.ducking-level", DEFAULTS.duckingLevel()));
    }

    /** Number of frames a full fade spans (at least 1, so a 0 ms fade is an immediate switch). */
    public int fadeSteps() {
        return Math.max(1, fadeDurationMs / FRAME_DURATION_MS);
    }

    /** Ducking level as a multiplier in [0, 1]. */
    public float duckingFloor() {
        return duckingLevel / 100.0f;
    }
}
