package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import net.dv8tion.jda.api.entities.Guild;

import java.util.Optional;

/**
 * Command to clear the music queue.
 */
public class ClearCommand {
    private final MusicPlugin plugin;

    public ClearCommand(MusicPlugin plugin) {
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

        if (player.getTrackScheduler().getQueueSize() == 0) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.queue_empty"));
            return;
        }

        int cleared = player.getTrackScheduler().getQueueSize();
        player.getTrackScheduler().clear();

        ctx.replySuccess(plugin.getLanguage().getString(ctx.getLocale(), "music.queue.cleared", cleared));

        player.getPlayerMessage().refresh();
        player.saveState();
    }
}