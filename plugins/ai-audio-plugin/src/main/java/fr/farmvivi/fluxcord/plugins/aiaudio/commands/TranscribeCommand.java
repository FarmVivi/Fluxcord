package fr.farmvivi.fluxcord.plugins.aiaudio.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import net.dv8tion.jda.api.entities.Guild;

import java.util.Optional;

/**
 * {@code /transcribe <start|stop>} — writes what is said in the voice channel into the text channel it
 * was called from.
 */
public class TranscribeCommand extends AiAudioCommand {

    /** The {@code action} option values, also what the slash command offers as choices. */
    public static final String START = "start";
    public static final String STOP = "stop";

    public TranscribeCommand(AIAudioPlugin plugin) {
        super(plugin);
    }

    /**
     * @param ctx    the command context
     * @param action {@link #START} or {@link #STOP}
     */
    public void execute(CommandContext ctx, String action) {
        Optional<Guild> optGuild = guild(ctx);
        if (optGuild.isEmpty()) {
            return;
        }
        Guild guild = optGuild.get();
        if (STOP.equalsIgnoreCase(action)) {
            boolean stopped = plugin.getSpeechRecognition().stop(guild);
            ctx.replySuccess(text(ctx, stopped ? "messages.transcription_stopped" : "errors.not_transcribing"));
            return;
        }
        if (plugin.getSettings().transcriptionNeedsKey()) {
            ctx.replyError(text(ctx, "errors.api_key_missing"));
            return;
        }
        if (!ensureConnected(ctx, guild)) {
            return;
        }
        if (!plugin.getSpeechRecognition().start(guild, ctx.getChannel())) {
            ctx.replyError(text(ctx, "errors.already_transcribing"));
            return;
        }
        ctx.replySuccess(text(ctx, "messages.transcription_started"));
    }
}
