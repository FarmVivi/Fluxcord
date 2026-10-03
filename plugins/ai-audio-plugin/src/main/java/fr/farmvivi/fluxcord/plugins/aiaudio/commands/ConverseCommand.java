package fr.farmvivi.fluxcord.plugins.aiaudio.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import fr.farmvivi.fluxcord.plugins.aiaudio.realtime.RealtimeException;
import net.dv8tion.jda.api.entities.Guild;

import java.util.Optional;

/**
 * {@code /converse <start|stop>} — lets the bot answer out loud what is said in the voice channel.
 *
 * <p>Starting a conversation starts transcription too: the bot cannot answer what it does not hear — and
 * stopping it stops that transcription again, because a bot told to stop conversing has visibly not stopped
 * while it still writes down every sentence. Transcription asked for on its own, with {@code /transcribe},
 * survives: only what this command started is taken away.
 */
public class ConverseCommand extends AiAudioCommand {

    /** The {@code action} option values. */
    public static final String START = "start";
    public static final String STOP = "stop";

    public ConverseCommand(AIAudioPlugin plugin) {
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
            // Asked before stopping, because stopping is what releases the claim.
            if (plugin.getWakeGate() != null) {
                plugin.getWakeGate().disengage(guild, "the conversation was stopped");
            }
            boolean ourListening = plugin.getConversation().ownsListening(guild);
            // Both are stopped, not the first one that answers: either path may be the one running, and
            // stopping the other is a no-op. Spelled out in two statements rather than with a
            // non-short-circuiting "|", which reads like a typo for "||" and hides the whole point.
            boolean realtimeStopped = plugin.getRealtime().stop(guild);
            boolean turnBasedStopped = plugin.getConversation().stop(guild);
            boolean stopped = realtimeStopped || turnBasedStopped;
            if (ourListening) {
                plugin.getSpeechRecognition().stop(guild);
            }
            plugin.getTextToSpeech().interrupt(guild);
            ctx.replySuccess(text(ctx, stopped ? "messages.converse_stopped" : "errors.not_conversing"));
            return;
        }

        if (!plugin.getSettings().chat().enabled()) {
            ctx.replyError(text(ctx, "errors.conversation_disabled"));
            return;
        }
        if (plugin.getSettings().chat().needsKey()) {
            ctx.replyError(text(ctx, "errors.api_key_missing"));
            return;
        }
        // Answering out loud needs a voice. Unless the chat model produces one itself, that voice comes from
        // the synthesis endpoint - and promising to answer aloud with no way to speak is the one failure a
        // user cannot diagnose, because the bot simply goes quiet.
        if (!plugin.getSettings().chat().audio().speak() && plugin.getSettings().synthesisNeedsKey()) {
            ctx.replyError(text(ctx, "errors.api_key_missing"));
            return;
        }
        if (!ensureConnected(ctx, guild)) {
            return;
        }
        // A realtime session that waits to be addressed is started by the gate, not here: the local
        // transcriber has to be listening first, for free, and it is what notices the name.
        if (plugin.getSettings().realtime().isUsable()
                && !plugin.getSettings().realtime().wakeLocally()) {
            startRealtime(ctx, guild);
            return;
        }
        // Answering requires hearing, so this starts the transcriber - but with nowhere to write. Hearing and
        // publishing are two different requests: asking the bot to talk with you is not asking it to narrate
        // the room into a text channel, and when it did, a conversation of a few minutes buried the channel
        // under every half-sentence anybody said. "/transcribe" is how you ask for the writing, and it can be
        // run while this is already listening.
        boolean startedListening = plugin.getSpeechRecognition().start(guild, null);

        if (!plugin.getConversation().start(guild, ctx.getChannel())) {
            ctx.replyError(text(ctx, "errors.already_conversing"));
            return;
        }
        if (startedListening) {
            plugin.getConversation().ownListening(guild);
        }
        String wakeWord = plugin.getSettings().chat().wakeWord();
        ctx.replySuccess(wakeWord.isEmpty()
                ? text(ctx, "messages.converse_started")
                : text(ctx, "messages.converse_started_wake_word", wakeWord));
    }

    /**
     * Opens the full-duplex conversation instead of the turn-based one.
     *
     * <p>No transcription service is started here, and no wake word applies: the service hears continuously and
     * decides for itself when somebody has finished talking, which is the whole difference. It also means the
     * bot answers everything said in the channel, so this is not a configuration to switch on by accident —
     * which is why it needs a URL and a key before it counts as usable.
     */
    private void startRealtime(CommandContext ctx, Guild guild) {
        try {
            if (!plugin.getRealtime().start(guild, ctx.getChannel())) {
                ctx.replyError(text(ctx, "errors.already_conversing"));
                return;
            }
        } catch (RealtimeException e) {
            ctx.replyError(text(ctx, "errors.realtime_failed", String.valueOf(e.getMessage())));
            return;
        }
        ctx.replySuccess(text(ctx, "messages.converse_started_realtime"));
    }
}
