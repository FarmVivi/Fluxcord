package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;

/**
 * Command to play music from a URL or search query.
 */
public class PlayCommand extends MusicCommand {
    public PlayCommand(MusicPlugin plugin) {
        super(plugin);
    }

    public void execute(CommandContext ctx, String query, boolean playNow) {
        Guild guild = guild(ctx).orElse(null);
        if (guild == null) {
            return;
        }

        // Check if user is in a voice channel. Asked of the context, not of the event: the caller may
        // have reached this command through something other than a slash or a message - a modal, or the
        // voice assistant acting on their request - and they are standing in a channel all the same.
        Member member = ctx.getMember().orElse(null);
        AudioChannel voiceChannel = member != null && member.getVoiceState() != null ? member.getVoiceState().getChannel() : null;
        if (voiceChannel == null) {
            ctx.replyError(text(ctx, "music.error.not_in_voice"));
            return;
        }

        // Check permission (prefer guild-scoped if available)
        String userId = ctx.getUser().getId();
        String perm = plugin.getId() + ".play";
        boolean allowed = plugin.getPermissions().hasPermission(userId, guild.getId(), perm)
                || plugin.getPermissions().hasPermission(userId, perm);
        if (!allowed) {
            ctx.replyError(text(ctx, "music.error.no_permission"));
            return;
        }

        // Load and play the track
        plugin.getMusicManager().loadTrack(ctx, query, playNow);
    }
}