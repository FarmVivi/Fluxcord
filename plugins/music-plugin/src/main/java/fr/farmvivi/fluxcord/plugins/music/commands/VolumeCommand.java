package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import net.dv8tion.jda.api.entities.Guild;

/**
 * Command to adjust playback volume.
 */
public class VolumeCommand extends MusicCommand {
    public VolumeCommand(MusicPlugin plugin) {
        super(plugin);
    }

    public void execute(CommandContext ctx, Integer level) {
        MusicPlayer player = player(ctx).orElse(null);
        if (player == null) {
            return;
        }
        Guild guild = player.getGuild();

        // Check permission
        String userId = ctx.getUser().getId();
        String perm = plugin.getId() + ".volume";
        boolean allowed = plugin.getPermissions().hasPermission(userId, guild.getId(), perm)
                || plugin.getPermissions().hasPermission(userId, perm);
        if (!allowed) {
            ctx.replyError(text(ctx, "music.error.no_permission"));
            return;
        }

        if (level == null) {
            // Show current volume
            ctx.replyInfo(text(ctx, "music.volume.current", player.getVolume()));
        } else {
            // Set new volume
            player.setVolume(level);
            ctx.replySuccess(text(ctx, "music.volume.set", level));
        }
    }
}