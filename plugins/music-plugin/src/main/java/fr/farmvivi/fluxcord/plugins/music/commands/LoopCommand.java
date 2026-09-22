package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.player.TrackScheduler;

/**
 * Command to control loop modes.
 */
public class LoopCommand extends MusicCommand {
    public LoopCommand(MusicPlugin plugin) {
        super(plugin);
    }

    public void execute(CommandContext ctx, String mode) {
        MusicPlayer player = player(ctx).orElse(null);
        if (player == null) {
            return;
        }
        TrackScheduler scheduler = player.getTrackScheduler();

        switch (mode.toLowerCase()) {
            case "off":
                scheduler.setLoopMode(false);
                scheduler.setLoopQueueMode(false);
                ctx.replySuccess(text(ctx, "music.loop.disabled"));
                break;

            case "track":
                scheduler.setLoopMode(true);
                scheduler.setLoopQueueMode(false);
                ctx.replySuccess(text(ctx, "music.loop.track"));
                break;

            case "queue":
                scheduler.setLoopMode(false);
                scheduler.setLoopQueueMode(true);
                ctx.replySuccess(text(ctx, "music.loop.queue"));
                break;

            case "toggle":
            default:
                if (scheduler.isLoopMode()) {
                    scheduler.setLoopMode(false);
                    scheduler.setLoopQueueMode(true);
                    ctx.replySuccess(text(ctx, "music.loop.queue"));
                } else if (scheduler.isLoopQueueMode()) {
                    scheduler.setLoopMode(false);
                    scheduler.setLoopQueueMode(false);
                    ctx.replySuccess(text(ctx, "music.loop.disabled"));
                } else {
                    scheduler.setLoopMode(true);
                    scheduler.setLoopQueueMode(false);
                    ctx.replySuccess(text(ctx, "music.loop.track"));
                }
                break;
        }

        player.getPlayerMessage().refresh();
        player.saveState();
    }
}