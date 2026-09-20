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
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Command to display the music queue.
 */
public class QueueCommand {
    private static final int TRACKS_PER_PAGE = 10;
    private final MusicPlugin plugin;

    public QueueCommand(MusicPlugin plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandContext ctx, int page) {
        Optional<Guild> optGuild = ctx.getGuild();
        if (optGuild.isEmpty()) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.guild_only"));
            return;
        }
        Guild guild = optGuild.get();

        MusicPlayer player = plugin.getMusicManager().getPlayer(guild);
        List<AudioTrack> queue = player.getTrackScheduler().getQueue();

        if (queue.isEmpty() && player.getPlayingTrack() == null) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.queue.empty"));
            return;
        }

        PluginLanguageAdapter lm = plugin.getLanguage();
        Locale locale = ctx.getLocale();

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(Color.BLUE)
                .setTitle(lm.getString(locale, "music.queue.title"));

        // Add current track
        AudioTrack current = player.getPlayingTrack();
        if (current != null) {
            String status = player.isPaused() ? "⏸️" : "▶️";
            embed.addField(
                    lm.getString(locale, "music.queue.now_playing"),
                    String.format("%s [%s](%s) - %s",
                            status,
                            current.getInfo().title,
                            current.getInfo().uri,
                            TimeParser.formatTime(current.getDuration())
                    ),
                    false
            );
        }

        if (!queue.isEmpty()) {
            int totalPages = (queue.size() + TRACKS_PER_PAGE - 1) / TRACKS_PER_PAGE;
            page = Math.max(1, Math.min(page, totalPages));

            int start = (page - 1) * TRACKS_PER_PAGE;
            int end = Math.min(start + TRACKS_PER_PAGE, queue.size());

            StringBuilder queueList = new StringBuilder();
            for (int i = start; i < end; i++) {
                AudioTrack track = queue.get(i);
                queueList.append(String.format("%d. [%s](%s) - %s\n",
                        i + 1,
                        track.getInfo().title,
                        track.getInfo().uri,
                        TimeParser.formatTime(track.getDuration())
                ));
            }

            embed.addField(
                    lm.getString(locale, "music.queue.upcoming", queue.size()),
                    queueList.toString(),
                    false
            );

            // Add page info
            if (totalPages > 1) {
                embed.setFooter(lm.getString(locale, "music.queue.page", page, totalPages));
            }

            // Add total duration
            long totalDuration = queue.stream()
                    .mapToLong(t -> t.getDuration())
                    .filter(d -> d != Long.MAX_VALUE)
                    .sum();

            if (totalDuration > 0) {
                embed.addField(
                        lm.getString(locale, "music.queue.duration"),
                        TimeParser.formatTime(totalDuration),
                        true
                );
            }
        }

        // Add playback modes
        if (player.getTrackScheduler().isLoopMode() ||
                player.getTrackScheduler().isLoopQueueMode() ||
                player.getTrackScheduler().isShuffleMode()) {

            StringBuilder modes = new StringBuilder();
            if (player.getTrackScheduler().isLoopMode()) {
                modes.append("🔂 ");
            }
            if (player.getTrackScheduler().isLoopQueueMode()) {
                modes.append("🔁 ");
            }
            if (player.getTrackScheduler().isShuffleMode()) {
                modes.append("🔀 ");
            }

            embed.addField(
                    lm.getString(locale, "music.queue.modes"),
                    modes.toString(),
                    true
            );
        }

        ctx.replyEmbed(embed);
    }
}