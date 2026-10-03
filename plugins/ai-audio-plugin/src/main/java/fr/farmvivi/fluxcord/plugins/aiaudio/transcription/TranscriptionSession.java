package fr.farmvivi.fluxcord.plugins.aiaudio.transcription;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.UserAudio;

/**
 * Receives what is said in one guild's voice channel and hands it to a {@link SpeechSegmenter}.
 *
 * <p>Per-user reception is what makes attribution possible: {@link #canReceiveUser()} asks JDA for one
 * call per speaker per 20 ms packet, so two people talking at once end up in two separate buffers and
 * two separate transcriptions. Combined audio would give a single stream and no way to tell who said
 * what.
 *
 * <p>This class does no I/O: it converts the byte order and forwards. Deciding when an utterance is over
 * belongs to the segmenter, and transcribing it to the service — which keeps the JDA audio thread free,
 * since it must return well within 20 ms.
 *
 * <p><strong>The audio can be diverted</strong> ({@link #divertTo}), and that is how the plugin avoids
 * paying a hosted service to listen to a conversation it is not part of. While a diversion is in place the
 * packets go there and the segmenter sees nothing — not <em>also</em> there, because transcribing the same
 * sentence twice would cost twice and remember it twice. The core allows one receive handler per plugin per
 * guild, so this is the seam that lets two listeners share it.
 */
public class TranscriptionSession implements AudioReceiveHandler {

    private final SpeechSegmenter segmenter;
    private final java.util.function.LongSupplier clock;
    /** Volatile: written by whichever thread engages, read by JDA's audio thread on every packet. */
    private volatile java.util.function.BiConsumer<String, PcmAudio> diversion;

    /**
     * @param segmenter where the received audio accumulates
     * @param clock     the current time in milliseconds, injected so tests need no real time
     */
    public TranscriptionSession(SpeechSegmenter segmenter, java.util.function.LongSupplier clock) {
        this.segmenter = segmenter;
        this.clock = clock;
    }

    /**
     * Sends the audio somewhere else instead of to the segmenter.
     *
     * @param diversion where the packets go, as (speaker id, audio); null to transcribe locally again
     */
    public void divertTo(java.util.function.BiConsumer<String, PcmAudio> diversion) {
        this.diversion = diversion;
    }

    /** @return true while the audio is going somewhere other than the segmenter */
    public boolean isDiverted() {
        return diversion != null;
    }

    @Override
    public boolean canReceiveUser() {
        // The whole point: one call per speaker, so a transcription can carry a name.
        return true;
    }

    @Override
    public boolean canReceiveCombined() {
        // A single mixed stream cannot be attributed to anyone.
        return false;
    }

    @Override
    public void handleUserAudio(UserAudio userAudio) {
        // JDA hands out big-endian samples at 48 kHz stereo; the volume is left untouched here because
        // the transcription model is the listener, not a human.
        PcmAudio audio = PcmAudio.fromBigEndian(userAudio.getAudioData(1.0),
                PcmAudio.DISCORD_SAMPLE_RATE, PcmAudio.DISCORD_CHANNELS);
        String userId = userAudio.getUser().getId();
        java.util.function.BiConsumer<String, PcmAudio> elsewhere = diversion;
        if (elsewhere != null) {
            elsewhere.accept(userId, audio);
            return;
        }
        segmenter.accept(userId, audio, clock.getAsLong());
    }
}
