package fr.farmvivi.fluxcord.plugins.music.commands;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The commands that change what the player is doing: pause, stop, skip, clear, shuffle, loop and
 * volume. Each one refuses outside a guild through {@link MusicCommand}, so that case is checked
 * once here rather than in every nested class.
 */
class PlaybackCommandsTest extends MusicCommandTestBase {

    @Test
    void everyMusicCommandRefusesOutsideAGuild() {
        when(ctx.getGuild()).thenReturn(Optional.empty());

        new PauseCommand(plugin).execute(ctx);

        assertEquals("music.error.guild_only", lastError());
        verify(musicManager, never()).getPlayer(any());
    }

    @Nested
    class Pause {
        @Test
        void togglesAndReportsTheNewState() {
            playing("a", 1000);
            when(player.isPaused()).thenReturn(false);

            new PauseCommand(plugin).execute(ctx);

            verify(player).setPaused(true);
            assertEquals("music.paused", lastSuccess());

            when(player.isPaused()).thenReturn(true);
            new PauseCommand(plugin).execute(ctx);

            verify(player).setPaused(false);
            assertEquals("music.resumed", lastSuccess());
        }

        @Test
        void needsSomethingPlaying() {
            new PauseCommand(plugin).execute(ctx);

            assertEquals("music.error.nothing_playing", lastError());
            verify(player, never()).setPaused(anyBoolean());
        }
    }

    @Nested
    class Stop {
        @Test
        void stopsButStaysInTheChannel() {
            playing("a", 1000);

            new StopCommand(plugin).execute(ctx);

            verify(player).stop();
            verify(player, never()).stopAndLeave();
            assertEquals("music.stopped", lastSuccess());
        }

        @Test
        void aQueuedTrackIsEnoughToStopEvenWithNothingPlaying() {
            queued(track("a", 1000));

            new StopCommand(plugin).execute(ctx);

            verify(player).stop();
        }

        @Test
        void refusesWhenThereIsNothingAtAll() {
            new StopCommand(plugin).execute(ctx);

            assertEquals("music.error.nothing_playing", lastError());
            verify(player, never()).stop();
        }
    }

    @Nested
    class Skip {
        @Test
        void skipsAndNamesTheTrackItLeft() {
            playing("Never Gonna", 1000);

            new SkipCommand(plugin).execute(ctx);

            verify(player).skipTrack();
            assertEquals("music.skipped", lastSuccess());
        }

        @Test
        void needsTheSkipPermission() {
            playing("a", 1000);
            when(permissions.hasPermission(USER_ID, GUILD_ID, PLUGIN_ID + ".skip")).thenReturn(false);
            when(permissions.hasPermission(USER_ID, PLUGIN_ID + ".skip")).thenReturn(false);

            new SkipCommand(plugin).execute(ctx);

            assertEquals("music.error.no_permission", lastError());
            verify(player, never()).skipTrack();
        }

        @Test
        void aGlobalPermissionIsEnoughWhenTheGuildOneIsMissing() {
            playing("a", 1000);
            when(permissions.hasPermission(USER_ID, GUILD_ID, PLUGIN_ID + ".skip")).thenReturn(false);
            when(permissions.hasPermission(USER_ID, PLUGIN_ID + ".skip")).thenReturn(true);

            new SkipCommand(plugin).execute(ctx);

            verify(player).skipTrack();
        }

        @Test
        void needsSomethingPlaying() {
            new SkipCommand(plugin).execute(ctx);

            assertEquals("music.error.nothing_playing", lastError());
            verify(permissions, never()).hasPermission(anyString(), anyString(), anyString());
        }
    }

    @Nested
    class Clear {
        @Test
        void emptiesTheQueueAndRefreshesTheUi() {
            queued(track("a", 1000), track("b", 1000));

            new ClearCommand(plugin).execute(ctx);

            verify(scheduler).clear();
            verify(playerMessage).refresh();
            verify(player).saveState();
            assertEquals("music.queue.cleared", lastSuccess());
        }

        @Test
        void refusesAnEmptyQueue() {
            new ClearCommand(plugin).execute(ctx);

            assertEquals("music.error.queue_empty", lastError());
            verify(scheduler, never()).clear();
        }
    }

    @Nested
    class Shuffle {
        @Test
        void togglesTheModeBothWays() {
            queued(track("a", 1000));
            when(scheduler.isShuffleMode()).thenReturn(false);

            new ShuffleCommand(plugin).execute(ctx);

            verify(scheduler).setShuffleMode(true);
            assertEquals("music.shuffle.enabled", lastSuccess());

            when(scheduler.isShuffleMode()).thenReturn(true);
            new ShuffleCommand(plugin).execute(ctx);

            verify(scheduler).setShuffleMode(false);
            assertEquals("music.shuffle.disabled", lastSuccess());
        }

        @Test
        void refusesAnEmptyQueue() {
            new ShuffleCommand(plugin).execute(ctx);

            assertEquals("music.error.queue_empty", lastError());
            verify(scheduler, never()).setShuffleMode(anyBoolean());
        }
    }

    @Nested
    class Loop {
        @Test
        void anExplicitModeSetsBothFlags() {
            new LoopCommand(plugin).execute(ctx, "track");
            verify(scheduler).setLoopMode(true);
            verify(scheduler).setLoopQueueMode(false);
            assertEquals("music.loop.track", lastSuccess());

            new LoopCommand(plugin).execute(ctx, "QUEUE");
            verify(scheduler).setLoopQueueMode(true);
            assertEquals("music.loop.queue", lastSuccess());

            new LoopCommand(plugin).execute(ctx, "off");
            assertEquals("music.loop.disabled", lastSuccess());
            verify(scheduler, times(2)).setLoopMode(false);
        }

        @Test
        void togglingCyclesTrackThenQueueThenOff() {
            new LoopCommand(plugin).execute(ctx, "toggle");
            verify(scheduler).setLoopMode(true);
            assertEquals("music.loop.track", lastSuccess());

            when(scheduler.isLoopMode()).thenReturn(true);
            new LoopCommand(plugin).execute(ctx, "toggle");
            verify(scheduler).setLoopQueueMode(true);
            assertEquals("music.loop.queue", lastSuccess());

            when(scheduler.isLoopMode()).thenReturn(false);
            when(scheduler.isLoopQueueMode()).thenReturn(true);
            new LoopCommand(plugin).execute(ctx, "toggle");
            assertEquals("music.loop.disabled", lastSuccess());

            verify(playerMessage, times(3)).refresh();
            verify(player, times(3)).saveState();
        }

        @Test
        void anUnknownModeBehavesLikeToggle() {
            new LoopCommand(plugin).execute(ctx, "sideways");

            verify(scheduler).setLoopMode(true);
            assertEquals("music.loop.track", lastSuccess());
        }
    }

    @Nested
    class Volume {
        @Test
        void withoutALevelItOnlyReportsTheCurrentOne() {
            when(player.getVolume()).thenReturn(42);

            new VolumeCommand(plugin).execute(ctx, null);

            assertEquals("music.volume.current", lastInfo());
            verify(player, never()).setVolume(anyInt());
        }

        @Test
        void withALevelItSetsIt() {
            new VolumeCommand(plugin).execute(ctx, 80);

            verify(player).setVolume(80);
            assertEquals("music.volume.set", lastSuccess());
        }

        @Test
        void needsTheVolumePermission() {
            when(permissions.hasPermission(USER_ID, GUILD_ID, PLUGIN_ID + ".volume")).thenReturn(false);
            when(permissions.hasPermission(USER_ID, PLUGIN_ID + ".volume")).thenReturn(false);

            new VolumeCommand(plugin).execute(ctx, 80);

            assertEquals("music.error.no_permission", lastError());
            verify(player, never()).setVolume(anyInt());
        }
    }
}
