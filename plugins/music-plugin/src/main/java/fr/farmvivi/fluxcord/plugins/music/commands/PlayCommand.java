package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

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

        // Check if user is in a voice channel
        Member member = null;
        if (ctx.getOriginalEvent() instanceof SlashCommandInteractionEvent e) {
            member = e.getMember();
        } else if (ctx.getOriginalEvent() instanceof MessageReceivedEvent e) {
            member = e.getMember();
        }
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