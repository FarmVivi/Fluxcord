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

    /** The {@code state} option values. Absent means toggle, which is what the button does. */
    public static final String PAUSE = "pause";
    public static final String RESUME = "resume";

    public void execute(CommandContext ctx) {
        execute(ctx, null);
    }

    /**
     * @param action {@link #PAUSE}, {@link #RESUME}, or null to toggle
     */
    public void execute(CommandContext ctx, String action) {
        MusicPlayer player = player(ctx).orElse(null);
        if (player == null) {
            return;
        }

        if (player.getPlayingTrack() == null) {
            ctx.replyError(text(ctx, "music.error.nothing_playing"));
            return;
        }

        // Explicit when asked for explicitly, a toggle only when nobody said which way.
        //
        // A toggle is fine for a button: the person pressing it can see the state. It is actively wrong for
        // a caller that cannot, and the AI is one - MEASURED live 2026-10-04, the model called "pause",
        // found the answer ambiguous, called it again to be sure, and the second call resumed playback.
        // What Victor saw was "I tell it to pause and it does not".
        boolean paused = switch (action == null ? "" : action.toLowerCase(java.util.Locale.ROOT)) {
            case PAUSE -> true;
            case RESUME -> false;
            default -> !player.isPaused();
        };
        player.setPaused(paused);

        if (paused) {
            ctx.replySuccess(text(ctx, "music.paused"));
        } else {
            ctx.replySuccess(text(ctx, "music.resumed"));
        }
    }
}