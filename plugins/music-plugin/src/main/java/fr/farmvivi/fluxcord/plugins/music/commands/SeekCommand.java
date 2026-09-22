package fr.farmvivi.fluxcord.plugins.music.commands;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.utils.TimeParser;

/**
 * Command to seek to a specific position in the current track.
 */
public class SeekCommand extends MusicCommand {
    public SeekCommand(MusicPlugin plugin) {
        super(plugin);
    }

    public void execute(CommandContext ctx, String timeStr) {
        MusicPlayer player = player(ctx).orElse(null);
        if (player == null) {
            return;
        }
        AudioTrack track = player.getPlayingTrack();

        if (track == null) {
            ctx.replyError(text(ctx, "music.error.nothing_playing"));
            return;
        }

        if (!track.isSeekable()) {
            ctx.replyError(text(ctx, "music.error.not_seekable"));
            return;
        }

        long position = TimeParser.parseTime(timeStr);
        if (position < 0) {
            ctx.replyError(text(ctx, "music.error.invalid_time"));
            return;
        }

        if (position > track.getDuration()) {
            ctx.replyError(text(ctx, "music.error.seek_too_far"));
            return;
        }

        track.setPosition(position);
        ctx.replySuccess(text(ctx, "music.seeked", TimeParser.formatTime(position)));

        player.getPlayerMessage().refresh();
        player.saveState();
    }
}