package fr.farmvivi.fluxcord.plugins.music.commands;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The commands that read the queue or move inside it: queue, nowplaying, remove, seek — plus play,
 * whose own job is to check the caller before handing over to {@code MusicManager}.
 */
class QueueCommandsTest extends MusicCommandTestBase {

    /** The text of the embed the command replied with, fields included. */
    private String embedText() {
        MessageEmbed embed = lastEmbed().build();
        StringBuilder text = new StringBuilder(String.valueOf(embed.getTitle())).append('\n');
        if (embed.getDescription() != null) {
            text.append(embed.getDescription()).append('\n');
        }
        embed.getFields().forEach(field ->
                text.append(field.getName()).append(": ").append(field.getValue()).append('\n'));
        return text.toString();
    }

    @Nested
    class Queue {
        @Test
        void showsTheCurrentTrackAndTheUpcomingOnes() {
            playing("current", 120_000);
            queued(track("next", 60_000), track("later", 30_000));

            new QueueCommand(plugin).execute(ctx, 1);

            String text = embedText();
            assertTrue(text.contains("current"), text);
            assertTrue(text.contains("1. [next]"), text);
            assertTrue(text.contains("2. [later]"), text);
            assertTrue(text.contains("1:30"), "the total duration of the queue: " + text);
        }

        @Test
        void refusesWhenNothingIsPlayingAndTheQueueIsEmpty() {
            new QueueCommand(plugin).execute(ctx, 1);

            assertEquals("music.queue.empty", lastError());
        }

        @Test
        void pagesAreClampedToWhatExists() {
            playing("current", 1000);
            AudioTrack[] tracks = new AudioTrack[25];
            for (int i = 0; i < tracks.length; i++) {
                tracks[i] = track("t" + i, 1000);
            }
            queued(tracks);

            new QueueCommand(plugin).execute(ctx, 99);

            String text = embedText();
            assertTrue(text.contains("21. [t20]"), "the last page is shown: " + text);
            assertFalse(text.contains("1. [t0]"), text);
        }

        @Test
        void theActiveModesAreListed() {
            playing("current", 1000);
            queued(track("a", 1000));
            when(scheduler.isLoopMode()).thenReturn(true);
            when(scheduler.isShuffleMode()).thenReturn(true);

            new QueueCommand(plugin).execute(ctx, 1);

            assertTrue(embedText().contains("music.queue.modes"));
        }
    }

    @Nested
    class NowPlaying {
        @Test
        void showsTheTrackWithAProgressBar() {
            AudioTrack track = playing("current", 200_000);
            when(track.getPosition()).thenReturn(100_000L);
            when(player.getVolume()).thenReturn(70);

            new NowPlayingCommand(plugin).execute(ctx);

            String text = embedText();
            assertTrue(text.contains("current"), text);
            assertTrue(text.contains("1:40 / 3:20"), "position and duration: " + text);
            assertTrue(text.contains("70%"), text);
            assertTrue(text.contains("🔘"), "the progress knob: " + text);
        }

        @Test
        void aZeroDurationDoesNotBreakTheProgressBar() {
            AudioTrack track = playing("weird", 0);
            when(track.getPosition()).thenReturn(0L);

            assertDoesNotThrow(() -> new NowPlayingCommand(plugin).execute(ctx));

            assertTrue(embedText().contains("🔘"));
        }

        @Test
        void aLiveStreamHasNoProgress() {
            playing("live", Long.MAX_VALUE);

            new NowPlayingCommand(plugin).execute(ctx);

            String text = embedText();
            assertTrue(text.contains("music.nowplaying.live"), text);
            assertFalse(text.contains("music.nowplaying.progress"), text);
        }

        @Test
        void needsSomethingPlaying() {
            new NowPlayingCommand(plugin).execute(ctx);

            assertEquals("music.error.nothing_playing", lastError());
        }
    }

    @Nested
    class Remove {
        @Test
        void removesTheTrackAtTheGivenOneBasedPosition() {
            queued(track("a", 1000), track("b", 1000));
            when(scheduler.removeTrack(1)).thenReturn(true);

            new RemoveCommand(plugin).execute(ctx, 2);

            verify(scheduler).removeTrack(1);
            assertEquals("music.queue.removed", lastSuccess());
            verify(playerMessage).refresh();
        }

        @Test
        void positionsOutsideTheQueueAreRefused() {
            queued(track("a", 1000));

            new RemoveCommand(plugin).execute(ctx, 0);
            assertEquals("music.error.invalid_position", lastError());

            new RemoveCommand(plugin).execute(ctx, 2);
            assertEquals("music.error.invalid_position", lastError());

            verify(scheduler, never()).removeTrack(anyInt());
        }

        @Test
        void refusesAnEmptyQueue() {
            new RemoveCommand(plugin).execute(ctx, 1);

            assertEquals("music.error.queue_empty", lastError());
        }

        @Test
        void aFailedRemovalIsReported() {
            queued(track("a", 1000));
            when(scheduler.removeTrack(0)).thenReturn(false);

            new RemoveCommand(plugin).execute(ctx, 1);

            assertEquals("music.error.remove_failed", lastError());
            verify(playerMessage, never()).refresh();
        }
    }

    @Nested
    class Seek {
        @Test
        void movesThePlayingTrack() {
            AudioTrack track = playing("a", 200_000);

            new SeekCommand(plugin).execute(ctx, "1m30s");

            verify(track).setPosition(90_000);
            assertEquals("music.seeked", lastSuccess());
            verify(player).saveState();
        }

        @Test
        void invalidInputIsReported() {
            playing("a", 200_000);

            new SeekCommand(plugin).execute(ctx, "yesterday");

            assertEquals("music.error.invalid_time", lastError());
        }

        @Test
        void seekingPastTheEndIsRefused() {
            playing("a", 10_000);

            new SeekCommand(plugin).execute(ctx, "30");

            assertEquals("music.error.seek_too_far", lastError());
        }

        @Test
        void aStreamThatCannotSeekIsRefused() {
            AudioTrack track = playing("live", 10_000);
            when(track.isSeekable()).thenReturn(false);

            new SeekCommand(plugin).execute(ctx, "1");

            assertEquals("music.error.not_seekable", lastError());
            verify(track, never()).setPosition(anyLong());
        }

        @Test
        void needsSomethingPlaying() {
            new SeekCommand(plugin).execute(ctx, "1");

            assertEquals("music.error.nothing_playing", lastError());
        }
    }

    @Nested
    class Play {
        private void callerInVoice(boolean inVoice) {
            SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
            Member member = mock(Member.class);
            GuildVoiceState voiceState = mock(GuildVoiceState.class);
            when(event.getMember()).thenReturn(member);
            when(member.getVoiceState()).thenReturn(voiceState);
            when(voiceState.getChannel()).thenReturn(inVoice ? mock(AudioChannelUnion.class) : null);
            when(ctx.getOriginalEvent()).thenReturn(event);
        }

        @Test
        void handsTheQueryOverToTheMusicManager() {
            callerInVoice(true);

            new PlayCommand(plugin).execute(ctx, "never gonna give you up", true);

            verify(musicManager).loadTrack(ctx, "never gonna give you up", true);
        }

        @Test
        void theCallerMustBeInAVoiceChannel() {
            callerInVoice(false);

            new PlayCommand(plugin).execute(ctx, "a", false);

            assertEquals("music.error.not_in_voice", lastError());
            verify(musicManager, never()).loadTrack(any(), anyString(), anyBoolean());
        }

        @Test
        void needsThePlayPermission() {
            callerInVoice(true);
            when(permissions.hasPermission(USER_ID, GUILD_ID, PLUGIN_ID + ".play")).thenReturn(false);
            when(permissions.hasPermission(USER_ID, PLUGIN_ID + ".play")).thenReturn(false);

            new PlayCommand(plugin).execute(ctx, "a", false);

            assertEquals("music.error.no_permission", lastError());
            verify(musicManager, never()).loadTrack(any(), anyString(), anyBoolean());
        }
    }
}
