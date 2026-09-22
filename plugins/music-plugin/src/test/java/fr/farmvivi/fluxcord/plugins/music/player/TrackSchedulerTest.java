package fr.farmvivi.fluxcord.plugins.music.player;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

/**
 * {@link TrackScheduler}: queue ordering and the three playback modes. The LavaPlayer
 * {@link AudioPlayer} is a mock backed by a single "currently playing" slot, which is enough to
 * reproduce {@code startTrack(track, noInterrupt)} semantics.
 */
class TrackSchedulerTest {

    private final AtomicReference<AudioTrack> playing = new AtomicReference<>();
    private AudioPlayer player;
    private MusicPlayer musicPlayer;
    private TrackScheduler scheduler;

    @BeforeEach
    void setUp() {
        player = mock(AudioPlayer.class);
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

        Guild guild = mock(Guild.class);
        when(guild.getName()).thenReturn("guild");
        musicPlayer = mock(MusicPlayer.class);
        when(musicPlayer.getGuild()).thenReturn(guild);

        scheduler = new TrackScheduler(musicPlayer, player);
    }

    /** A track whose clones are distinct instances but keep the same title. */
    private AudioTrack track(String title) {
        AudioTrack track = mock(AudioTrack.class, title);
        AudioTrackInfo info = new AudioTrackInfo(title, "author", 1000, title, false, "https://example.test/" + title);
        when(track.getInfo()).thenReturn(info);
        when(track.makeClone()).thenAnswer(invocation -> {
            AudioTrack clone = mock(AudioTrack.class, title + "-clone");
            when(clone.getInfo()).thenReturn(info);
            when(clone.makeClone()).thenReturn(clone);
            return clone;
        });
        return track;
    }

    private List<String> titles(List<AudioTrack> tracks) {
        return tracks.stream().map(t -> t.getInfo().title).toList();
    }

    private String playingTitle() {
        return playing.get().getInfo().title;
    }

    @Test
    void theFirstTrackPlaysImmediatelyAndTheNextOnesAreQueued() {
        AudioTrack first = track("first");
        AudioTrack second = track("second");

        assertTrue(scheduler.queue(first), "nothing was playing");
        assertFalse(scheduler.queue(second), "queued behind the playing track");

        assertSame(first, playing.get());
        assertEquals(List.of("second"), titles(scheduler.getQueue()));
    }

    @Test
    void nextTrackConsumesTheQueueInOrder() {
        scheduler.queue(track("a"));
        scheduler.queue(track("b"));
        scheduler.queue(track("c"));

        scheduler.nextTrack();
        assertEquals("b", playingTitle());
        scheduler.nextTrack();
        assertEquals("c", playingTitle());

        AudioTrack last = playing.get();
        scheduler.nextTrack();
        assertSame(last, playing.get(), "an empty queue leaves the player untouched");
        assertEquals(0, scheduler.getQueueSize());
    }

    @Test
    void playNowInterruptsAndPushesTheCurrentTrackBackToTheFront() {
        scheduler.queue(track("current"));
        scheduler.queue(track("queued"));

        scheduler.playNow(track("urgent"));

        assertEquals("urgent", playingTitle());
        assertEquals(List.of("current", "queued"), titles(scheduler.getQueue()));
    }

    @Test
    void skipDiscardsTheCurrentTrack() {
        scheduler.queue(track("a"));
        scheduler.queue(track("b"));

        scheduler.skip();

        assertEquals("b", playingTitle());
        assertEquals(0, scheduler.getQueueSize(), "the skipped track is gone");
    }

    @Test
    void skipInLoopModeSendsTheTrackToTheEndOfTheQueue() {
        scheduler.queue(track("a"));
        scheduler.queue(track("b"));
        scheduler.queue(track("c"));
        scheduler.setLoopMode(true);

        scheduler.skip();

        assertEquals("b", playingTitle());
        assertEquals(List.of("c", "a"), titles(scheduler.getQueue()));
    }

    @Test
    void skipInLoopModeWithAnEmptyQueueRestartsTheSameTrack() {
        scheduler.queue(track("a"));
        scheduler.setLoopMode(true);

        scheduler.skip();

        assertEquals("a", playingTitle(), "the only track in the loop is itself");
        assertEquals(0, scheduler.getQueueSize());
    }

    @Test
    void loopModeReplaysTheTrackWhenItFinishes() {
        AudioTrack a = track("a");
        scheduler.queue(a);
        scheduler.queue(track("b"));
        scheduler.setLoopMode(true);

        scheduler.onTrackEnd(player, a, AudioTrackEndReason.FINISHED);

        assertEquals("a", playingTitle(), "the same track is played again");
        assertEquals(1, scheduler.getQueueSize(), "the queue did not advance");
        verify(musicPlayer, atLeastOnce()).refreshUi();
    }

    @Test
    void aReplacedOrStoppedTrackNeverStartsTheNextOne() {
        scheduler.queue(track("a"));
        scheduler.queue(track("b"));
        AudioTrack current = playing.get();

        scheduler.onTrackEnd(player, current, AudioTrackEndReason.REPLACED);
        assertSame(current, playing.get());
        assertEquals(1, scheduler.getQueueSize());

        scheduler.setLoopMode(true);
        scheduler.onTrackEnd(player, current, AudioTrackEndReason.STOPPED);
        assertSame(current, playing.get(), "a user stop must not restart the track in loop mode");
    }

    @Test
    void aTrackExceptionSkipsToTheNextTrack() {
        AudioTrack a = track("a");
        scheduler.queue(a);
        scheduler.queue(track("b"));

        scheduler.onTrackException(player, a,
                new FriendlyException("boom", FriendlyException.Severity.COMMON, null));

        assertEquals("b", playingTitle());
    }

    @Test
    void loopQueueModeRefillsTheQueueFromTheHistory() {
        scheduler.setLoopQueueMode(true);
        AudioTrack a = track("a");
        AudioTrack b = track("b");
        scheduler.queue(a);
        scheduler.queue(b);

        // The history is fed by onTrackStart, which LavaPlayer fires for every started track.
        scheduler.onTrackStart(player, a);
        scheduler.nextTrack();
        scheduler.onTrackStart(player, b);
        assertEquals(0, scheduler.getQueueSize());

        scheduler.nextTrack(); // end of the queue: restart from the history

        assertEquals(List.of("a", "b"),
                Stream.concat(Stream.of(playing.get()), scheduler.getQueue().stream())
                        .map(t -> t.getInfo().title).toList());
    }

    @Test
    void disablingLoopQueueModeDropsTheHistory() {
        scheduler.setLoopQueueMode(true);
        AudioTrack a = track("a");
        scheduler.queue(a);
        scheduler.onTrackStart(player, a);

        scheduler.setLoopQueueMode(false);
        AudioTrack current = playing.get();
        scheduler.nextTrack();

        assertSame(current, playing.get(), "nothing left to play");
    }

    @Test
    void shuffleModeDrainsTheWholeQueueWithoutRepeating() {
        scheduler.queue(track("a"));
        scheduler.queue(track("b"));
        scheduler.queue(track("c"));
        scheduler.queue(track("d"));
        scheduler.setShuffleMode(true);

        List<String> played = new ArrayList<>();
        played.add(playingTitle());
        for (int i = 0; i < 3; i++) {
            scheduler.nextTrack();
            played.add(playingTitle());
        }

        assertEquals(0, scheduler.getQueueSize());
        assertEquals(List.of("a", "b", "c", "d"), played.stream().sorted().toList(),
                "every queued track is played exactly once");
    }

    @Test
    void removeAndMoveValidateTheirIndexes() {
        scheduler.queue(track("playing"));
        scheduler.queue(track("a"));
        scheduler.queue(track("b"));
        scheduler.queue(track("c"));

        assertFalse(scheduler.removeTrack(-1));
        assertFalse(scheduler.removeTrack(3));
        assertTrue(scheduler.removeTrack(1));
        assertEquals(List.of("a", "c"), titles(scheduler.getQueue()));

        assertFalse(scheduler.moveTrack(0, 0), "a no-op move is rejected");
        assertFalse(scheduler.moveTrack(0, 2));
        assertTrue(scheduler.moveTrack(1, 0));
        assertEquals(List.of("c", "a"), titles(scheduler.getQueue()));
    }

    @Test
    void restoreQueueAppendsWithoutStartingPlayback() {
        scheduler.restoreQueue(null);
        scheduler.restoreQueue(List.of());
        assertEquals(0, scheduler.getQueueSize());
        assertNull(playing.get(), "restoring never starts a track");

        List<AudioTrack> restored = new ArrayList<>();
        restored.add(track("a"));
        restored.add(null);
        restored.add(track("b"));
        scheduler.restoreQueue(restored);

        assertEquals(List.of("a", "b"), titles(scheduler.getQueue()));
        assertNull(playing.get());
    }

    @Test
    void clearEmptiesTheQueueAndTheHistory() {
        scheduler.setLoopQueueMode(true);
        AudioTrack a = track("a");
        scheduler.queue(a);
        scheduler.onTrackStart(player, a);
        scheduler.queue(track("b"));

        scheduler.clear();
        assertEquals(0, scheduler.getQueueSize());

        AudioTrack current = playing.get();
        scheduler.nextTrack();
        assertSame(current, playing.get(), "the history was cleared too, nothing to restart");
    }

    @Test
    void getQueueReturnsACopy() {
        scheduler.queue(track("playing"));
        scheduler.queue(track("queued"));

        scheduler.getQueue().clear();

        assertEquals(1, scheduler.getQueueSize());
    }
}
