package fr.farmvivi.fluxcord.plugins.music;

import fr.farmvivi.fluxcord.api.audio.events.AudioDuckingChangedEvent;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pausing the music while somebody more important talks, and — the part that is easy to get wrong —
 * knowing whose pause it is.
 */
class DuckingPauseListenerTest {

    private static final String GUILD_ID = "g1";
    private static final String MUSIC = "music-plugin";
    private static final String AI = "ai-audio-plugin";

    private MusicManager musicManager;
    private MusicPlayer player;
    private Guild guild;
    private DuckingPauseListener listener;

    @BeforeEach
    void setUp() {
        musicManager = mock(MusicManager.class);
        player = mock(MusicPlayer.class);
        guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        when(musicManager.findPlayer(GUILD_ID)).thenReturn(Optional.of(player));
        listener = new DuckingPauseListener(MUSIC, musicManager);
    }

    private void ducking(String plugin, boolean on) {
        listener.onDuckingChanged(new AudioDuckingChangedEvent(guild, plugin, on));
    }

    /** The whole point: the seconds the answer covers are given back, not lost. */
    @Test
    void theTrackIsPausedWhileSomebodyElseTalksAndResumedAfter() {
        ducking(AI, true);
        verify(player).setPaused(true);

        when(player.isPaused()).thenReturn(true);
        ducking(AI, false);
        verify(player).setPaused(false);
        assertFalse(listener.pausedFor(GUILD_ID));
    }

    /**
     * Somebody who ran {@code /pause} before the bot spoke meant it. Resuming on their behalf would be the
     * bot overruling a person through a side effect, which is worse than missing a few seconds of a song.
     */
    @Test
    void aPauseSomebodyElseAskedForIsNeverUndone() {
        when(player.isPaused()).thenReturn(true);

        ducking(AI, true);
        ducking(AI, false);

        verify(player, never()).setPaused(true);
        verify(player, never()).setPaused(false);
        assertFalse(listener.pausedFor(GUILD_ID), "this listener never claimed that pause");
    }

    /** The music having the floor is not a reason for the music to get out of its own way. */
    @Test
    void theMusicDoesNotDuckItself() {
        ducking(MUSIC, true);

        verify(player, never()).setPaused(true);
        assertFalse(listener.pausedFor(GUILD_ID));
    }

    /** Creating a player here would open a voice connection for a guild that has no music at all. */
    @Test
    void aGuildWithoutAPlayerIsLeftAlone() {
        when(musicManager.findPlayer(GUILD_ID)).thenReturn(Optional.empty());

        ducking(AI, true);

        verify(musicManager, never()).getPlayer(guild);
        assertFalse(listener.pausedFor(GUILD_ID));
    }

    /** Two announcements in a row must not leave the guild paused twice, nor resume it early. */
    @Test
    void repeatedAnnouncementsDoNotStack() {
        ducking(AI, true);
        ducking(AI, true);
        assertTrue(listener.pausedFor(GUILD_ID));

        when(player.isPaused()).thenReturn(true);
        ducking(AI, false);
        ducking(AI, false);

        verify(player).setPaused(false);
        assertFalse(listener.pausedFor(GUILD_ID));
    }
}
