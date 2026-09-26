package fr.farmvivi.fluxcord.plugins.aiaudio.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import net.dv8tion.jda.api.entities.Guild;

import java.util.Optional;

/**
 * {@code /converse <start|stop>} — lets the bot answer out loud what is said in the voice channel.
 *
 * <p>Starting a conversation starts transcription too: the bot cannot answer what it does not hear. Stopping
 * leaves the transcription running, since writing down a conversation without taking part in it is a perfectly
 * reasonable thing to want.
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
            boolean stopped = plugin.getConversation().stop(guild);
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
        if (!ensureConnected(ctx, guild)) {
            return;
        }
        // Answering requires hearing: starting transcription here saves running two commands, and starting it
        // twice is harmless.
        plugin.getSpeechRecognition().start(guild, ctx.getChannel());

        if (!plugin.getConversation().start(guild)) {
            ctx.replyError(text(ctx, "errors.already_conversing"));
            return;
        }
        String wakeWord = plugin.getSettings().chat().wakeWord();
        ctx.replySuccess(wakeWord.isEmpty()
                ? text(ctx, "messages.converse_started")
                : text(ctx, "messages.converse_started_wake_word", wakeWord));
    }
}
