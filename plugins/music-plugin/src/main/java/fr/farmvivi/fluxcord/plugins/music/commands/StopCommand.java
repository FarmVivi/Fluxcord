package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import net.dv8tion.jda.api.entities.Guild;

import java.util.Optional;

/**
 * Command to stop playback and clear the queue.
 */
public class StopCommand {
    private final MusicPlugin plugin;

    public StopCommand(MusicPlugin plugin) {
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

        if (player.getPlayingTrack() == null && player.getTrackScheduler().getQueueSize() == 0) {
            ctx.replyError(plugin.getLanguage().getString(ctx.getLocale(), "music.error.nothing_playing"));
            return;
        }

        player.stop(); // stay in the channel until the auto-leave timeout

        ctx.replySuccess(plugin.getLanguage().getString(ctx.getLocale(), "music.stopped"));
    }
}