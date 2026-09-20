package fr.farmvivi.fluxcord.core.command.reply;

import fr.farmvivi.fluxcord.core.command.parser.event.ConsoleCommandEvent;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

/**
 * Where a command reply goes: one implementation per transport (slash/modal interaction, text message, console).
 * The builder composes the message; the target knows how its transport sends, defers, edits or emulates
 * ephemeral replies.
 */
public interface ReplyTarget {

    /**
     * Sends a composed message.
     *
     * @param message   the message, or {@code null} for an empty reply (JDA cannot build one), which each
     *                  transport handles its own way (interaction: placeholder deleted, text/console: nothing sent)
     * @param ephemeral only the invoking user should see it (emulated where the transport has no such notion)
     * @param deferred  the caller intends to answer later: an interaction that is not yet acknowledged only gets
     *                  {@code deferReply}; the message itself is sent on a later call
     */
    void send(MessageCreateData message, boolean ephemeral, boolean deferred);

    /**
     * Picks the target for the event a command came from.
     *
     * @throws IllegalArgumentException for an event no transport handles
     */
    static ReplyTarget of(Event event) {
        if (event instanceof IReplyCallback callback) {
            return new InteractionReplyTarget(callback);
        }
        if (event instanceof MessageReceivedEvent messageEvent) {
            return new MessageReplyTarget(messageEvent);
        }
        if (event instanceof ConsoleCommandEvent) {
            return new ConsoleReplyTarget(System.out);
        }
        throw new IllegalArgumentException("No reply transport for " + event.getClass().getName());
    }
}
