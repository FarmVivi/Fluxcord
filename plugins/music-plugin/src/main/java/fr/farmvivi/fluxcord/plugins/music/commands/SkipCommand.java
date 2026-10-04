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

        // Read once, and used for both the guard and the title: asking twice invited the two answers to
        // disagree, and the second one is the only place a null would have been a NullPointerException.
        var leaving = player.getPlayingTrack();
        if (leaving == null) {
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

        String skippedTitle = leaving.getInfo().title;
        player.skipTrack();

        // Naming what is playing now, and not only what was dropped. Asked out loud to move to the next
        // track, the bot used to read this answer back and announce the title it had just skipped as the
        // one now playing - the answer simply did not contain the only fact the question was about.
        var nowPlaying = player.getPlayingTrack();
        ctx.replySuccess(nowPlaying == null
                ? text(ctx, "music.skipped", skippedTitle)
                : text(ctx, "music.skipped_now_playing", skippedTitle, nowPlaying.getInfo().title));
    }
}