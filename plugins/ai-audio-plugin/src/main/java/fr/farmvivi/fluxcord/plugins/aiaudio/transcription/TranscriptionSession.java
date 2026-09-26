package fr.farmvivi.fluxcord.plugins.aiaudio.transcription;

import fr.farmvivi.fluxcord.plugins.aiaudio.audio.PcmAudio;
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
 */
public class TranscriptionSession implements AudioReceiveHandler {

    private final SpeechSegmenter segmenter;
    private final java.util.function.LongSupplier clock;

    /**
     * @param segmenter where the received audio accumulates
     * @param clock     the current time in milliseconds, injected so tests need no real time
     */
    public TranscriptionSession(SpeechSegmenter segmenter, java.util.function.LongSupplier clock) {
        this.segmenter = segmenter;
        this.clock = clock;
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
        segmenter.accept(userAudio.getUser().getId(), audio, clock.getAsLong());
    }
}
