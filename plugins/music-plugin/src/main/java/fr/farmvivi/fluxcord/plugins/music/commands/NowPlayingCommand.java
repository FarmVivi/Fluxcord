package fr.farmvivi.fluxcord.plugins.music.commands;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.utils.TimeParser;
import net.dv8tion.jda.api.EmbedBuilder;

import java.awt.*;

/**
 * Command to display the currently playing track.
 */
public class NowPlayingCommand extends MusicCommand {
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
            PluginLanguageAdapter lm = plugin.getLanguage();
            ctx.replyError(text(ctx, "music.error.nothing_playing"));
            return;
        }

        PluginLanguageAdapter lm = plugin.getLanguage();

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

    private String createProgressBar(AudioTrack track) {
        int barLength = 20;
        long position = track.getPosition();
        long duration = track.getDuration();

        int progress = (int) ((position * barLength) / duration);
        StringBuilder bar = new StringBuilder();

        for (int i = 0; i < barLength; i++) {
            if (i == progress) {
                bar.append("🔘");
            } else if (i < progress) {
                bar.append("▬");
            } else {
                bar.append("▬");
            }
        }

        return bar.toString();
    }
}