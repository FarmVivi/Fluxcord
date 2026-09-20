package fr.farmvivi.fluxcord.plugins.music.commands;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.utils.TimeParser;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;

import java.awt.*;
import java.util.Locale;
import java.util.Optional;

/**
 * Command to display the currently playing track.
 */
public class NowPlayingCommand {
    private final MusicPlugin plugin;

    public NowPlayingCommand(MusicPlugin plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandContext ctx) {
        Optional<Guild> optGuild = ctx.getGuild();
        if (optGuild.isEmpty()) {
            PluginLanguageAdapter lm = plugin.getLanguage();
            ctx.replyError(lm.getString(ctx.getLocale(), "music.error.guild_only"));
            return;
        }
        Guild guild = optGuild.get();
        MusicPlayer player = plugin.getMusicManager().getPlayer(guild);
        AudioTrack track = player.getPlayingTrack();

        if (track == null) {
            PluginLanguageAdapter lm = plugin.getLanguage();
            ctx.replyError(lm.getString(ctx.getLocale(), "music.error.nothing_playing"));
            return;
        }

        PluginLanguageAdapter lm = plugin.getLanguage();
        Locale locale = ctx.getLocale();

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(player.isPaused() ? Color.ORANGE : Color.GREEN)
                .setTitle(lm.getString(locale, "music.nowplaying.title"));

        // Thumbnail
        if (track.getInfo().artworkUrl != null) {
            embed.setThumbnail(track.getInfo().artworkUrl);
        }

        // Track info
        embed.addField(
                lm.getString(locale, "music.nowplaying.track"),
                String.format("[%s](%s)", track.getInfo().title, track.getInfo().uri),
                false
        );

        // Author/Artist
        if (track.getInfo().author != null && !track.getInfo().author.isEmpty()) {
            embed.addField(
                    lm.getString(locale, "music.nowplaying.author"),
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
                    lm.getString(locale, "music.nowplaying.progress"),
                    progressBar + "\n" + timeInfo,
                    false
            );
        } else {
            embed.addField(
                    lm.getString(locale, "music.nowplaying.duration"),
                    lm.getString(locale, "music.nowplaying.live"),
                    true
            );
        }

        // Volume
        embed.addField(
                lm.getString(locale, "music.nowplaying.volume"),
                player.getVolume() + "%",
                true
        );

        // Status
        if (player.isPaused()) {
            embed.addField(
                    lm.getString(locale, "music.nowplaying.status"),
                    lm.getString(locale, "music.nowplaying.paused"),
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