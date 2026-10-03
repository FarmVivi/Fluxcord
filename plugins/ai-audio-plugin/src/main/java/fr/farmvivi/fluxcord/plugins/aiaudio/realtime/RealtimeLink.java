package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

/**
 * Somewhere to send frames, and a way to stop.
 *
 * <p>One interface for one reason: it is the line between what can be tested and what cannot. Everything about
 * a Realtime conversation — when to interrupt the bot, which tool answers which call, what gets remembered — is
 * decided in {@link RealtimeConversation} against this interface, and the only part left untestable is the
 * WebSocket itself in {@link RealtimeSession}, which does nothing but carry strings.
 */
public interface RealtimeLink {

    /**
     * Sends one frame.
     *
     * <p>Must not throw: a conversation that is already failing should not also break the audio thread that
     * noticed. Implementations report a failure through their own event stream instead.
     *
     * @param frame the JSON frame, as {@link RealtimeProtocol} built it
     */
    void send(String frame);

    /** Closes the conversation. Safe to call twice. */
    void close();

    /** @return true while frames can still be sent */
    boolean isOpen();
}
