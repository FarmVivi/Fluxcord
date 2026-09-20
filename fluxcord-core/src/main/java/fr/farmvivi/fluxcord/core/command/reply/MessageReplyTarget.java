package fr.farmvivi.fluxcord.core.command.reply;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Prefixed text commands: the reply answers the triggering message. There is no deferral and no ephemeral
 * message on this transport; "ephemeral" is emulated by deleting the reply — and the triggering message when the
 * bot may — after {@link #EPHEMERAL_LIFETIME_MINUTES}. An empty message sends nothing.
 */
public final class MessageReplyTarget implements ReplyTarget {
    private static final Logger logger = LoggerFactory.getLogger(MessageReplyTarget.class);
    static final long EPHEMERAL_LIFETIME_MINUTES = 1;

    private final MessageReceivedEvent event;

    public MessageReplyTarget(MessageReceivedEvent event) {
        this.event = event;
    }

    @Override
    public void send(MessageCreateData message, boolean ephemeral, boolean deferred) {
        if (InteractionReplyTarget.isEmpty(message)) {
            if (ephemeral) {
                deleteTriggeringMessageLater();
            }
            return;
        }
        MessageCreateAction action = event.getMessage().reply(message);
        if (ephemeral) {
            action.queue(sent -> {
                sent.delete().queueAfter(EPHEMERAL_LIFETIME_MINUTES, TimeUnit.MINUTES);
                deleteTriggeringMessageLater();
            });
        } else {
            action.queue();
        }
    }

    private void deleteTriggeringMessageLater() {
        if (event.isFromGuild() && event.getGuild().getSelfMember().hasPermission(event.getGuildChannel(), Permission.MESSAGE_MANAGE)) {
            logger.debug("Ephemeral emulation: deleting the triggering message in {} min", EPHEMERAL_LIFETIME_MINUTES);
            Message trigger = event.getMessage();
            trigger.delete().queueAfter(EPHEMERAL_LIFETIME_MINUTES, TimeUnit.MINUTES);
        }
    }
}
