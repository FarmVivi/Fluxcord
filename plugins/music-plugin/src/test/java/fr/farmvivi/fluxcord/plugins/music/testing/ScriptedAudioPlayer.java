package fr.farmvivi.fluxcord.plugins.music.testing;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * A LavaPlayer {@link AudioPlayer} mock backed by a single "currently playing" slot.
 *
 * <p>It reproduces {@code startTrack(track, noInterrupt)} and {@code stopTrack()} without any
 * playback thread. A real player would try to decode the track and immediately end it (test tracks
 * cannot be decoded), which makes the queue drain on its own — and the assertions racy.
 */
public final class ScriptedAudioPlayer {

    private ScriptedAudioPlayer() {
    }

    /** @return a player mock whose playing track can be read back with {@link #playingTrack} */
    public static AudioPlayer create() {
        AtomicReference<AudioTrack> playing = new AtomicReference<>();
        AudioPlayer player = mock(AudioPlayer.class);
        when(player.startTrack(any(), anyBoolean())).thenAnswer(invocation -> {
            AudioTrack track = invocation.getArgument(0);
            boolean noInterrupt = invocation.getArgument(1);
            if (noInterrupt && playing.get() != null) {
                return false;
            }
            playing.set(track);
            return true;
        });
        when(player.getPlayingTrack()).thenAnswer(invocation -> playing.get());
        doAnswer(invocation -> {
            playing.set(null);
            return null;
        }).when(player).stopTrack();
        return player;
    }

    /** @return the track the scripted player is holding, or {@code null} */
    public static AudioTrack playingTrack(AudioPlayer player) {
        return player.getPlayingTrack();
    }
}
