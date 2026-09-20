package fr.farmvivi.fluxcord.plugins.music.commands;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import net.dv8tion.jda.api.entities.Guild;

import java.util.List;
import java.util.Optional;

/**
 * Command to remove a track from the queue.
 */
public class RemoveCommand {
    private final MusicPlugin plugin;

    public RemoveCommand(MusicPlugin plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandContext ctx, int position) {
        Optional<Guild> optGuild = ctx.getGuild();
        if (optGuild.isEmpty()) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.guild_only"));
            return;
        }
        Guild guild = optGuild.get();

        MusicPlayer player = plugin.getMusicManager().getPlayer(guild);
        List<AudioTrack> queue = player.getTrackScheduler().getQueue();

        if (queue.isEmpty()) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.queue_empty"));
            return;
        }

        if (position < 1 || position > queue.size()) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.invalid_position", 1, queue.size()));
            return;
        }

        AudioTrack removed = queue.get(position - 1);
        if (player.getTrackScheduler().removeTrack(position - 1)) {
            ctx.replySuccess(plugin.getLanguage().getString(ctx.getLocale(), "music.queue.removed", removed.getInfo().title));
            player.getPlayerMessage().refresh();
            player.saveState();
        } else {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.remove_failed"));
        }
    }
}