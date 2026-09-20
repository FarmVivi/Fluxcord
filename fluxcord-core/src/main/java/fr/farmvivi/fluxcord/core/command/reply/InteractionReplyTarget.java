package fr.farmvivi.fluxcord.core.command.reply;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slash commands, buttons and modals: one interaction, one reply. Before the interaction is acknowledged the
 * message is the initial reply (or, with {@code deferred}, only a {@code deferReply}); afterwards it edits the
 * placeholder. An empty message deletes the placeholder, or sends and deletes a zero-width one so Discord does not
 * report "the application did not respond".
 */
public final class InteractionReplyTarget implements ReplyTarget {
    private static final Logger logger = LoggerFactory.getLogger(InteractionReplyTarget.class);
    private static final String PLACEHOLDER = "​";

    private final IReplyCallback callback;

    public InteractionReplyTarget(IReplyCallback callback) {
        this.callback = callback;
    }

    @Override
    public void send(MessageCreateData message, boolean ephemeral, boolean deferred) {
        boolean empty = isEmpty(message);
        if (callback.isAcknowledged()) {
            InteractionHook hook = callback.getHook();
            if (empty) {
                logger.debug("Nothing to show after deferral: deleting the placeholder");
                hook.deleteOriginal().queue();
                return;
            }
            WebhookMessageEditAction<Message> edit = hook.editOriginal(message.getContent());
            edit.setEmbeds(message.getEmbeds());
            edit.setComponents(message.getComponents());
            edit.queue();
            return;
        }
        if (deferred) {
            logger.debug("Deferring interaction reply (ephemeral={})", ephemeral);
            callback.deferReply(ephemeral).queue();
            return;
        }
        if (empty) {
            callback.reply(PLACEHOLDER).setEphemeral(ephemeral).flatMap(InteractionHook::deleteOriginal).queue();
            return;
        }
        ReplyCallbackAction reply = callback.reply(message.getContent()).setEphemeral(ephemeral);
        if (!message.getEmbeds().isEmpty()) {
            reply.addEmbeds(message.getEmbeds());
        }
        if (!message.getComponents().isEmpty()) {
            reply.addComponents(message.getComponents());
        }
        reply.queue();
    }

    static boolean isEmpty(MessageCreateData message) {
        return message == null || message.getContent().isEmpty() && message.getEmbeds().isEmpty() && message.getComponents().isEmpty()
                && message.getFiles().isEmpty();
    }
}
