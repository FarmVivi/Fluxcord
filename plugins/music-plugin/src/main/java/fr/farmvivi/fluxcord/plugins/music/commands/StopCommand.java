package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;

/**
 * Command to stop playback and clear the queue.
 */
public class StopCommand extends MusicCommand {
    public StopCommand(MusicPlugin plugin) {
        super(plugin);
    }

    public void execute(CommandContext ctx) {
        MusicPlayer player = player(ctx).orElse(null);
        if (player == null) {
            return;
        }

        if (player.getPlayingTrack() == null && player.getTrackScheduler().getQueueSize() == 0) {
            ctx.replyError(text(ctx, "music.error.nothing_playing"));
            return;
        }

        player.stop(); // stay in the channel until the auto-leave timeout

        ctx.replySuccess(text(ctx, "music.stopped"));
    }
}