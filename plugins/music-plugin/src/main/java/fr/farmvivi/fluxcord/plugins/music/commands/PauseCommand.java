package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import net.dv8tion.jda.api.entities.Guild;

import java.util.Optional;

/**
 * Command to pause or resume playback.
 */
public class PauseCommand {
    private final MusicPlugin plugin;

    public PauseCommand(MusicPlugin plugin) {
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

        boolean paused = !player.isPaused();
        player.setPaused(paused);

        PluginLanguageAdapter lm = plugin.getLanguage();
        if (paused) {
            ctx.replySuccess(lm.getString(ctx.getLocale(), "music.paused"));
        } else {
            ctx.replySuccess(lm.getString(ctx.getLocale(), "music.resumed"));
        }
    }
}