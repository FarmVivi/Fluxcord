package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.player.TrackScheduler;
import net.dv8tion.jda.api.entities.Guild;

import java.util.Optional;

/**
 * Command to control loop modes.
 */
public class LoopCommand {
    private final MusicPlugin plugin;

    public LoopCommand(MusicPlugin plugin) {
        this.plugin = plugin;
    }

    public void execute(CommandContext ctx, String mode) {
        Optional<Guild> optGuild = ctx.getGuild();
        if (optGuild.isEmpty()) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.guild_only"));
            return;
        }
        Guild guild = optGuild.get();

        MusicPlayer player = plugin.getMusicManager().getPlayer(guild);
        TrackScheduler scheduler = player.getTrackScheduler();

        switch (mode.toLowerCase()) {
            case "off":
                scheduler.setLoopMode(false);
                scheduler.setLoopQueueMode(false);
                ctx.replySuccess(plugin.getLanguage().getString(ctx.getLocale(), "music.loop.disabled"));
                break;

            case "track":
                scheduler.setLoopMode(true);
                scheduler.setLoopQueueMode(false);
                ctx.replySuccess(plugin.getLanguage().getString(ctx.getLocale(), "music.loop.track"));
                break;

            case "queue":
                scheduler.setLoopMode(false);
                scheduler.setLoopQueueMode(true);
                ctx.replySuccess(plugin.getLanguage().getString(ctx.getLocale(), "music.loop.queue"));
                break;

            case "toggle":
            default:
                if (scheduler.isLoopMode()) {
                    scheduler.setLoopMode(false);
                    scheduler.setLoopQueueMode(true);
                    ctx.replySuccess(plugin.getLanguage().getString(ctx.getLocale(), "music.loop.queue"));
                } else if (scheduler.isLoopQueueMode()) {
                    scheduler.setLoopMode(false);
                    scheduler.setLoopQueueMode(false);
                    ctx.replySuccess(plugin.getLanguage().getString(ctx.getLocale(), "music.loop.disabled"));
                } else {
                    scheduler.setLoopMode(true);
                    scheduler.setLoopQueueMode(false);
                    ctx.replySuccess(plugin.getLanguage().getString(ctx.getLocale(), "music.loop.track"));
                }
                break;
        }

        player.getPlayerMessage().refresh();
        player.saveState();
    }
}