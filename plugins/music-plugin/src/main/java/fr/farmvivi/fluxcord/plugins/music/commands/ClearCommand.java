package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;

/**
 * Command to clear the music queue.
 */
public class ClearCommand extends MusicCommand {
    public ClearCommand(MusicPlugin plugin) {
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

        int cleared = player.getTrackScheduler().getQueueSize();
        player.getTrackScheduler().clear();

        ctx.replySuccess(text(ctx, "music.queue.cleared", cleared));

        player.getPlayerMessage().refresh();
        player.saveState();
    }
}