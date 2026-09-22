package fr.farmvivi.fluxcord.plugins.music.commands;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;

import java.util.List;

/**
 * Command to remove a track from the queue.
 */
public class RemoveCommand extends MusicCommand {
    public RemoveCommand(MusicPlugin plugin) {
        super(plugin);
    }

    public void execute(CommandContext ctx, int position) {
        MusicPlayer player = player(ctx).orElse(null);
        if (player == null) {
            return;
        }
        List<AudioTrack> queue = player.getTrackScheduler().getQueue();

        if (queue.isEmpty()) {
            ctx.replyError(text(ctx, "music.error.queue_empty"));
            return;
        }

        if (position < 1 || position > queue.size()) {
            ctx.replyError(text(ctx, "music.error.invalid_position", 1, queue.size()));
            return;
        }

        AudioTrack removed = queue.get(position - 1);
        if (player.getTrackScheduler().removeTrack(position - 1)) {
            ctx.replySuccess(text(ctx, "music.queue.removed", removed.getInfo().title));
            player.getPlayerMessage().refresh();
            player.saveState();
        } else {
            ctx.replyError(text(ctx, "music.error.remove_failed"));
        }
    }
}