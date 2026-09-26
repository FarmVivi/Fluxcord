package fr.farmvivi.fluxcord.plugins.aiaudio.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import net.dv8tion.jda.api.entities.Guild;

import java.util.Optional;

/**
 * {@code /silence} — stops the bot talking and drops whatever it still had to say.
 *
 * <p>Needed because a long {@code /speak} cannot be taken back otherwise: the audio is already queued.
 */
public class SilenceCommand extends AiAudioCommand {

    public SilenceCommand(AIAudioPlugin plugin) {
        super(plugin);
    }

    /** @param ctx the command context */
    public void execute(CommandContext ctx) {
        Optional<Guild> optGuild = guild(ctx);
        if (optGuild.isEmpty()) {
            return;
        }
        boolean wasSpeaking = plugin.getTextToSpeech().stop(optGuild.get());
        ctx.replySuccess(text(ctx, wasSpeaking ? "messages.silenced" : "messages.already_silent"));
    }
}
