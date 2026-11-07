package fr.farmvivi.fluxcord.plugins.aiaudio.transcription;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;

/**
 * How loud a block of audio is, in dBFS.
 *
 * <p>This exists because of a failure that no amount of text processing could have caught. MEASURED twice,
 * 2026-11-07, on two separate sessions: a 0.52 s and a 0.58 s segment of microphone noise were sent to the
 * local transcriber, and both came back as exactly {@code "Fluxcord Chris"} — which is, word for word and in
 * order, the vocabulary list the request had just told the model to expect ("These names may occur, spelled
 * exactly like this: Fluxcord, Chris."). A general chat model handed almost no signal answers with the only
 * text it was given. That woke the hosted session, which was told somebody had said the bot's name and
 * nothing else, and the bot answered "Je suis là. Qu'y a-t-il ?" into an empty room, 92 seconds after the
 * previous session had closed.
 *
 * <p>Nothing downstream can tell that transcription from a real one: the words are the bot's own name, which
 * is precisely what it is listening for. The only place the difference is still visible is the audio, and the
 * difference is amplitude — so the measurement has to happen before the request, not after it.
 *
 * <p>RMS rather than peak, because a single loud click is not speech and a peak reading cannot tell the two
 * apart. The reference is full scale for signed 16-bit samples, so the result is zero or negative: speech on
 * Discord sits around -30 to -20 dBFS, and the noise this exists to drop is far below that.
 */
public final class Loudness {

    /**
     * What silence reads as, instead of negative infinity.
     *
     * <p>Digital silence has no logarithm, and a threshold comparison against {@code -Infinity} works while
     * the log line that should explain the drop reads "-Infinity dBFS". A floor keeps both honest: no real
     * signal reaches it, so it is unambiguous, and it formats.
     */
    public static final double SILENT_DBFS = -100.0;

    /** Full scale for signed 16-bit PCM. */
    private static final double FULL_SCALE = 32768.0;

    private Loudness() {
    }

    /**
     * @param audio the audio to measure, in signed 16-bit little-endian samples
     * @return its RMS level in dBFS, or {@link #SILENT_DBFS} for empty or silent audio
     */
    public static double dbfs(PcmAudio audio) {
        if (audio == null) {
            return SILENT_DBFS;
        }
        byte[] samples = audio.samples();
        // A trailing odd byte is not half a sample: it is dropped rather than read as one, which would
        // pair it with whatever follows it in memory.
        int count = samples.length / 2;
        if (count == 0) {
            return SILENT_DBFS;
        }
        // double rather than long: 25 seconds of 16 kHz audio is 400 000 squared samples of up to 2^30,
        // which fits a long - but only just, and the maximum segment length is configurable.
        double sumOfSquares = 0;
        for (int i = 0; i < count; i++) {
            int sample = (samples[2 * i] & 0xFF) | (samples[2 * i + 1] << 8);
            sumOfSquares += (double) sample * sample;
        }
        double rms = Math.sqrt(sumOfSquares / count) / FULL_SCALE;
        if (rms <= 0) {
            return SILENT_DBFS;
        }
        return Math.max(SILENT_DBFS, 20 * Math.log10(rms));
    }
}
