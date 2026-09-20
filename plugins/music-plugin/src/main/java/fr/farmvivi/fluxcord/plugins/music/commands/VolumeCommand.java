package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import net.dv8tion.jda.api.entities.Guild;

import java.util.Optional;

/**
 * Command to adjust playback volume.
 */
public class VolumeCommand {
    private final MusicPlugin plugin;

    public VolumeCommand(MusicPlugin plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandContext ctx, Integer level) {
        Optional<Guild> optGuild = ctx.getGuild();
        if (optGuild.isEmpty()) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.guild_only"));
            return;
        }
        Guild guild = optGuild.get();

        MusicPlayer player = plugin.getMusicManager().getPlayer(guild);

        // Check permission
        String userId = ctx.getUser().getId();
        String perm = plugin.getId() + ".volume";
        boolean allowed = plugin.getPermissions().hasPermission(userId, guild.getId(), perm)
                || plugin.getPermissions().hasPermission(userId, perm);
        if (!allowed) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.no_permission"));
            return;
        }

        if (level == null) {
            // Show current volume
            ctx.replyInfo(plugin.getLanguage().getString(ctx.getLocale(), "music.volume.current", player.getVolume()));
        } else {
            // Set new volume
            player.setVolume(level);
            ctx.replySuccess(plugin.getLanguage().getString(ctx.getLocale(), "music.volume.set", level));
        }
    }
}