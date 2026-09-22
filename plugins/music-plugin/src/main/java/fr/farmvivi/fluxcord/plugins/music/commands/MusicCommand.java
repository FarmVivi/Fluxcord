package fr.farmvivi.fluxcord.plugins.music.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import net.dv8tion.jda.api.entities.Guild;

import java.util.Optional;

/**
 * Shared prologue of the music commands: every one of them needs the plugin, the caller's guild and
 * translated replies.
 */
public abstract class MusicCommand {
    protected final MusicPlugin plugin;

    protected MusicCommand(MusicPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * The guild the command was sent from.
     *
     * <p>Replies with {@code music.error.guild_only} and returns empty when there is none (direct
     * message or console).
     *
     * @param ctx the command context
     * @return the guild, or empty when the command cannot run here
     */
    protected Optional<Guild> guild(CommandContext ctx) {
        Optional<Guild> guild = ctx.getGuild();
        if (guild.isEmpty()) {
            ctx.replyError(text(ctx, "music.error.guild_only"));
        }
        return guild;
    }

    /**
     * The music player of the command's guild, creating it if needed.
     *
     * @param ctx the command context
     * @return the player, or empty when the command was not sent from a guild
     */
    protected Optional<MusicPlayer> player(CommandContext ctx) {
        return guild(ctx).map(guild -> plugin.getMusicManager().getPlayer(guild));
    }

    /**
     * A translated string in the caller's locale.
     *
     * @param ctx  the command context
     * @param key  the translation key
     * @param args the {@link java.text.MessageFormat} arguments
     * @return the translated string
     */
    protected String text(CommandContext ctx, String key, Object... args) {
        return args.length == 0
                ? plugin.getLanguage().getString(ctx.getLocale(), key)
                : plugin.getLanguage().getString(ctx.getLocale(), key, args);
    }
}
