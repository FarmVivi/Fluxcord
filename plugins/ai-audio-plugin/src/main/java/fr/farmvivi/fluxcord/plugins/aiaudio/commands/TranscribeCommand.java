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
            // A conversation is listening for its own reasons, and it is not this command's to end: stopping
            // the writing here would otherwise leave the bot unable to hear the next question.
            if (plugin.getConversation() != null && plugin.getConversation().isActive(guild)) {
                boolean wasWriting = plugin.getSpeechRecognition().setOutput(guild, null);
                ctx.replySuccess(text(ctx,
                        wasWriting ? "messages.transcription_stopped" : "errors.not_transcribing"));
                return;
            }
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
        // A conversation may already be listening silently; this command is the request for the writing, so
        // it attaches a channel to what is running rather than refusing because somebody else got there
        // first. Only a guild that is already *writing* is already transcribing.
        if (plugin.getSpeechRecognition().isActive(guild)) {
            if (plugin.getSpeechRecognition().outputFor(guild).isPresent()) {
                ctx.replyError(text(ctx, "errors.already_transcribing"));
                return;
            }
            plugin.getSpeechRecognition().setOutput(guild, ctx.getChannel());
            ctx.replySuccess(text(ctx, "messages.transcription_started"));
            return;
        }
        if (!plugin.getSpeechRecognition().start(guild, ctx.getChannel())) {
            ctx.replyError(text(ctx, "errors.already_transcribing"));
            return;
        }
        ctx.replySuccess(text(ctx, "messages.transcription_started"));
    }
}
