package fr.farmvivi.fluxcord.plugins.aiaudio.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.managers.AudioManager;

import java.util.Optional;

/**
 * Shared prologue of the AI audio commands: they all need the plugin, the caller's guild, a voice
 * connection and translated replies.
 */
public abstract class AiAudioCommand {

    protected final AIAudioPlugin plugin;

    protected AiAudioCommand(AIAudioPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * The guild the command was sent from.
     *
     * <p>Replies with {@code errors.guild_only} and returns empty when there is none — a direct message
     * or the console.
     *
     * @param ctx the command context
     * @return the guild, or empty when the command cannot run here
     */
    protected Optional<Guild> guild(CommandContext ctx) {
        Optional<Guild> guild = ctx.getGuild();
        if (guild.isEmpty()) {
            ctx.replyError(text(ctx, "errors.guild_only"));
        }
        return guild;
    }

    /**
     * Makes sure the bot is in a voice channel, joining the caller's one when it is not already
     * connected.
     *
     * <p>Replies with {@code errors.no_voice_channel} and returns false when the caller is in no
     * channel, because there is nowhere to speak or listen then.
     *
     * @param ctx   the command context
     * @param guild the command's guild
     * @return true when the bot is connected and the command can go on
     */
    protected boolean ensureConnected(CommandContext ctx, Guild guild) {
        AudioManager audioManager = guild.getAudioManager();
        if (audioManager.isConnected()) {
            return true;
        }
        AudioChannel channel = callerChannel(ctx);
        if (channel == null) {
            ctx.replyError(text(ctx, "errors.no_voice_channel"));
            return false;
        }
        audioManager.openAudioConnection(channel);
        return true;
    }

    /**
     * The voice channel the caller is in.
     *
     * <p>The member has to come from the original event: {@link CommandContext} exposes the user, not
     * the member, and only a member has a voice state.
     *
     * @param ctx the command context
     * @return the channel, or null when the caller is in none
     */
    protected AudioChannel callerChannel(CommandContext ctx) {
        Member member = switch (ctx.getOriginalEvent()) {
            case SlashCommandInteractionEvent event -> event.getMember();
            case MessageReceivedEvent event -> event.getMember();
            default -> null;
        };
        return member == null || member.getVoiceState() == null ? null : member.getVoiceState().getChannel();
    }

    /**
     * A translated string in the caller's locale.
     *
     * @param ctx  the command context
     * @param key  the translation key, without the plugin namespace
     * @param args the {@link java.text.MessageFormat} arguments
     * @return the translated string
     */
    protected String text(CommandContext ctx, String key, Object... args) {
        return args.length == 0
                ? plugin.getLanguage().getString(ctx.getLocale(), key)
                : plugin.getLanguage().getString(ctx.getLocale(), key, args);
    }
}
