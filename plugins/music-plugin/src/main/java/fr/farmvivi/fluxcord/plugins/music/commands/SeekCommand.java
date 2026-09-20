package fr.farmvivi.fluxcord.plugins.music.commands;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.utils.TimeParser;
import net.dv8tion.jda.api.entities.Guild;

import java.util.Optional;

/**
 * Command to seek to a specific position in the current track.
 */
public class SeekCommand {
    private final MusicPlugin plugin;

    public SeekCommand(MusicPlugin plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandContext ctx, String timeStr) {
        Optional<Guild> optGuild = ctx.getGuild();
        if (optGuild.isEmpty()) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.guild_only"));
            return;
        }
        Guild guild = optGuild.get();

        MusicPlayer player = plugin.getMusicManager().getPlayer(guild);
        AudioTrack track = player.getPlayingTrack();

        if (track == null) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.nothing_playing"));
            return;
        }

        if (!track.isSeekable()) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.not_seekable"));
            return;
        }

        long position = TimeParser.parseTime(timeStr);
        if (position < 0) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.invalid_time"));
            return;
        }

        if (position > track.getDuration()) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.seek_too_far"));
            return;
        }

        track.setPosition(position);
        ctx.replySuccess(plugin.getLanguage().getString(ctx.getLocale(), "music.seeked", TimeParser.formatTime(position)));

        player.getPlayerMessage().refresh();
        player.saveState();
    }
}