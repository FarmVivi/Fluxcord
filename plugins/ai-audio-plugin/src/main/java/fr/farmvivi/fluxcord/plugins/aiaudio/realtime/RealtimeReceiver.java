package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.UserAudio;

import java.util.function.Function;

/**
 * Sends what is said straight to a realtime conversation, packet by packet.
 *
 * <p>The counterpart of {@code TranscriptionSession}, and the difference is the whole point of this path:
 * there, audio accumulates until somebody falls silent and only then goes anywhere, because a transcription
 * endpoint is billed per request. Here every 20 ms packet is forwarded as it arrives, and the service decides
 * when the turn is over — which is what lets it start answering before the sentence has finished.
 *
 * <p>Still per user ({@link #canReceiveUser()}), even though the session has one input buffer, because the
 * conversation needs to know when the speaker changes in order to say so. The combined stream would make that
 * unknowable.
 *
 * <p>Does no I/O and never blocks: it converts the byte order and hands over. The 20 ms budget on this thread
 * is why {@link RealtimeLink#send} is forbidden to throw.
 */
public class RealtimeReceiver implements AudioReceiveHandler {

    private final RealtimeConversation conversation;
    private final Function<String, String> displayNames;

    /**
     * @param conversation where the audio goes
     * @param displayNames a user id to the name the server shows, resolved from cache only — a REST lookup on
     *                     this thread would blow the frame budget
     */
    public RealtimeReceiver(RealtimeConversation conversation, Function<String, String> displayNames) {
        this.conversation = conversation;
        this.displayNames = displayNames;
    }

    @Override
    public boolean canReceiveUser() {
        return true;
    }

    @Override
    public boolean canReceiveCombined() {
        // One mixed stream could not tell the conversation who started talking.
        return false;
    }

    @Override
    public void handleUserAudio(UserAudio userAudio) {
        String userId = userAudio.getUser().getId();
        PcmAudio audio = PcmAudio.fromBigEndian(userAudio.getAudioData(1.0),
                PcmAudio.DISCORD_SAMPLE_RATE, PcmAudio.DISCORD_CHANNELS);
        conversation.hear(userId, displayNames.apply(userId), audio);
    }
}
