package fr.farmvivi.fluxcord.plugins.aiaudio.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;

import java.util.Optional;

/**
 * {@code /forget <me|channel|server>} — erases remembered conversation.
 *
 * <p>Anyone can erase their own history, whatever the server: it is theirs. Clearing a channel or the
 * whole server is somebody else's conversation too, so it takes the plugin's admin permission — which the
 * command registration enforces on the {@code server} choice.
 */
public class ForgetCommand extends AiAudioCommand {

    /** The {@code scope} option values, also what the slash command offers as choices. */
    public static final String ME = "me";
    public static final String CHANNEL = "channel";
    public static final String SERVER = "server";

    public ForgetCommand(AIAudioPlugin plugin) {
        super(plugin);
    }

    /**
     * @param ctx   the command context
     * @param scope {@link #ME}, {@link #CHANNEL} or {@link #SERVER}
     */
    public void execute(CommandContext ctx, String scope) {
        String wanted = scope == null ? ME : scope.toLowerCase(java.util.Locale.ROOT);
        if (ME.equals(wanted)) {
            plugin.getMemory().forgetPerson(ctx.getUser().getId());
            ctx.replySuccess(text(ctx, "messages.forgot_me"));
            return;
        }

        Optional<Guild> optGuild = guild(ctx);
        if (optGuild.isEmpty()) {
            return;
        }
        Guild guild = optGuild.get();
        if (!plugin.getPermissions().hasPermission(ctx.getUser().getId(), plugin.permissionKey(AIAudioPlugin.PERM_ADMIN))) {
            ctx.replyError(text(ctx, "errors.no_permission"));
            return;
        }
        if (SERVER.equals(wanted)) {
            plugin.getMemory().forgetServer(guild.getId());
            ctx.replySuccess(text(ctx, "messages.forgot_server", guild.getName()));
            return;
        }

        AudioChannel channel = guild.getAudioManager().getConnectedChannel();
        if (channel == null) {
            ctx.replyError(text(ctx, "errors.not_connected"));
            return;
        }
        plugin.getMemory().forgetChannel(guild.getId(), channel.getId());
        ctx.replySuccess(text(ctx, "messages.forgot_channel", channel.getName()));
    }
}
