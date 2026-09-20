package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import net.dv8tion.jda.api.entities.Guild;

import java.util.Optional;

/**
 * Command to skip the current track.
 */
public class SkipCommand {
    private final MusicPlugin plugin;

    public SkipCommand(MusicPlugin plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandContext ctx) {
        Optional<Guild> optGuild = ctx.getGuild();
        if (optGuild.isEmpty()) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.guild_only"));
            return;
        }
        Guild guild = optGuild.get();

        MusicPlayer player = plugin.getMusicManager().getPlayer(guild);

        if (player.getPlayingTrack() == null) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.nothing_playing"));
            return;
        }

        // Check permission
        String userId = ctx.getUser().getId();
        String perm = plugin.getId() + ".skip";
        boolean allowed = plugin.getPermissions().hasPermission(userId, guild.getId(), perm)
                || plugin.getPermissions().hasPermission(userId, perm);
        if (!allowed) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.no_permission"));
            return;
        }

        String skippedTitle = player.getPlayingTrack().getInfo().title;
        player.skipTrack();

        PluginLanguageAdapter lm = plugin.getLanguage();
        ctx.replySuccess(lm.getString(ctx.getLocale(), "music.skipped", skippedTitle));
    }
}