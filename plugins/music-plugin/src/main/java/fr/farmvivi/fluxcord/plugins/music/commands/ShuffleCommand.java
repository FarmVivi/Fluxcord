package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;

/**
 * Command to toggle shuffle mode.
 */
public class ShuffleCommand extends MusicCommand {
    public ShuffleCommand(MusicPlugin plugin) {
        super(plugin);
    }

    public void execute(CommandContext ctx) {
        MusicPlayer player = player(ctx).orElse(null);
        if (player == null) {
            return;
        }

        if (player.getTrackScheduler().getQueueSize() == 0) {
            ctx.replyError(text(ctx, "music.error.queue_empty"));
            return;
        }

        boolean shuffled = !player.getTrackScheduler().isShuffleMode();
        player.getTrackScheduler().setShuffleMode(shuffled);

        if (shuffled) {
            ctx.replySuccess(text(ctx, "music.shuffle.enabled"));
        } else {
            ctx.replySuccess(text(ctx, "music.shuffle.disabled"));
        }

        player.getPlayerMessage().refresh();
        player.saveState();
    }
}