package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;

/**
 * Command to pause or resume playback.
 */
public class PauseCommand extends MusicCommand {
    public PauseCommand(MusicPlugin plugin) {
        super(plugin);
    }

    public void execute(CommandContext ctx) {
        MusicPlayer player = player(ctx).orElse(null);
        if (player == null) {
            return;
        }

        if (player.getPlayingTrack() == null) {
            ctx.replyError(text(ctx, "music.error.nothing_playing"));
            return;
        }

        boolean paused = !player.isPaused();
        player.setPaused(paused);

        if (paused) {
            ctx.replySuccess(text(ctx, "music.paused"));
        } else {
            ctx.replySuccess(text(ctx, "music.resumed"));
        }
    }
}