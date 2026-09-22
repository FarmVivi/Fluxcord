package fr.farmvivi.fluxcord.plugins.music.commands;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.utils.TimeParser;
import net.dv8tion.jda.api.EmbedBuilder;

import java.awt.*;

/**
 * Command to display the currently playing track.
 */
public class NowPlayingCommand extends MusicCommand {
    private static final int BAR_LENGTH = 20;

    public NowPlayingCommand(MusicPlugin plugin) {
        super(plugin);
    }

    public void execute(CommandContext ctx) {
        MusicPlayer player = player(ctx).orElse(null);
        if (player == null) {
            return;
        }
        AudioTrack track = player.getPlayingTrack();

        if (track == null) {
            ctx.replyError(text(ctx, "music.error.nothing_playing"));
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(player.isPaused() ? Color.ORANGE : Color.GREEN)
                .setTitle(text(ctx, "music.nowplaying.title"));

        // Thumbnail
        if (track.getInfo().artworkUrl != null) {
            embed.setThumbnail(track.getInfo().artworkUrl);
        }

        // Track info
        embed.addField(
                text(ctx, "music.nowplaying.track"),
                String.format("[%s](%s)", track.getInfo().title, track.getInfo().uri),
                false
        );

        // Author/Artist
        if (track.getInfo().author != null && !track.getInfo().author.isEmpty()) {
            embed.addField(
                    text(ctx, "music.nowplaying.author"),
                    track.getInfo().author,
                    true
            );
        }

        // Duration and progress
        if (track.getDuration() != Long.MAX_VALUE) {
            String progressBar = createProgressBar(track);
            String timeInfo = String.format("%s / %s",
                    TimeParser.formatTime(track.getPosition()),
                    TimeParser.formatTime(track.getDuration())
            );

            embed.addField(
                    text(ctx, "music.nowplaying.progress"),
                    progressBar + "\n" + timeInfo,
                    false
            );
        } else {
            embed.addField(
                    text(ctx, "music.nowplaying.duration"),
                    text(ctx, "music.nowplaying.live"),
                    true
            );
        }

        // Volume
        embed.addField(
                text(ctx, "music.nowplaying.volume"),
                player.getVolume() + "%",
                true
        );

        // Status
        if (player.isPaused()) {
            embed.addField(
                    text(ctx, "music.nowplaying.status"),
                    text(ctx, "music.nowplaying.paused"),
                    true
            );
        }

        ctx.replyEmbed(embed);
    }

    /** The knob sits where the track is; a zero or unknown duration keeps it at the start. */
    private String createProgressBar(AudioTrack track) {
        long duration = track.getDuration();
        int knob = duration > 0
                ? (int) Math.min(BAR_LENGTH - 1L, (track.getPosition() * BAR_LENGTH) / duration)
                : 0;

        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < BAR_LENGTH; i++) {
            bar.append(i == knob ? "🔘" : "▬");
        }
        return bar.toString();
    }
}