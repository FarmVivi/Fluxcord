package fr.farmvivi.fluxcord.plugins.aiaudio.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import fr.farmvivi.fluxcord.plugins.aiaudio.AiSettings;
import net.dv8tion.jda.api.entities.Guild;

import java.util.Optional;

/**
 * {@code /speak <text> [voice]} — says something out loud in the caller's voice channel.
 */
public class SpeakCommand extends AiAudioCommand {

    public SpeakCommand(AIAudioPlugin plugin) {
        super(plugin);
    }

    /**
     * @param ctx   the command context
     * @param input what to say
     * @param voice the voice to use, or null for the configured default
     */
    public void execute(CommandContext ctx, String input, String voice) {
        Optional<Guild> optGuild = guild(ctx);
        if (optGuild.isEmpty()) {
            return;
        }
        AiSettings settings = plugin.getSettings();

        String spoken = input == null ? "" : input.trim();
        if (spoken.isEmpty()) {
            ctx.replyError(text(ctx, "errors.nothing_to_say"));
            return;
        }
        if (spoken.length() > settings.maxTextLength()) {
            ctx.replyError(text(ctx, "errors.text_too_long", settings.maxTextLength()));
            return;
        }
        if (settings.synthesisNeedsKey()) {
            ctx.replyError(text(ctx, "errors.api_key_missing"));
            return;
        }
        if (!ensureConnected(ctx, optGuild.get())) {
            return;
        }

        // Synthesis is a network round trip: acknowledge now or Discord gives up after three seconds.
        ctx.deferReply();
        plugin.getTextToSpeech().speak(optGuild.get(), spoken, voice)
                .thenAccept(audio -> ctx.reply(text(ctx, "messages.speaking", preview(spoken))))
                .exceptionally(error -> {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    plugin.getLogger().warn("Speech synthesis failed: {}", cause.getMessage());
                    ctx.replyError(text(ctx, "errors.synthesis_failed", cause.getMessage()));
                    return null;
                });
    }

    /** Keeps the confirmation readable when a long text was spoken. */
    private static String preview(String spoken) {
        return spoken.length() <= 100 ? spoken : spoken.substring(0, 100) + "...";
    }
}
