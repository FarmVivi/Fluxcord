package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import net.dv8tion.jda.api.entities.Guild;

/**
 * Command to skip the current track.
 */
public class SkipCommand extends MusicCommand {
    public SkipCommand(MusicPlugin plugin) {
        super(plugin);
    }

    public void execute(CommandContext ctx) {
        MusicPlayer player = player(ctx).orElse(null);
        if (player == null) {
            return;
        }
        Guild guild = player.getGuild();

        if (player.getPlayingTrack() == null) {
            ctx.replyError(text(ctx, "music.error.nothing_playing"));
            return;
        }

        // Check permission
        String userId = ctx.getUser().getId();
        String perm = plugin.getId() + ".skip";
        boolean allowed = plugin.getPermissions().hasPermission(userId, guild.getId(), perm)
                || plugin.getPermissions().hasPermission(userId, perm);
        if (!allowed) {
            ctx.replyError(text(ctx, "music.error.no_permission"));
            return;
        }

        String skippedTitle = player.getPlayingTrack().getInfo().title;
        player.skipTrack();

        ctx.replySuccess(text(ctx, "music.skipped", skippedTitle));
    }
}