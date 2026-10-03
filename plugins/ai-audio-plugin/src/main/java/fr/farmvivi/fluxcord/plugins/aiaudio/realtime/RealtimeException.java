package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

/**
 * A realtime conversation could not be opened or kept.
 *
 * <p>Separate from {@code AiRequestException} because the failure modes are not the same shape: that one is one
 * request that did not work, this one is a connection that is gone, and the only sensible answer is to fall back
 * to the turn-based path or to tell the channel.
 */
public class RealtimeException extends RuntimeException {

    public RealtimeException(String message) {
        super(message);
    }

    public RealtimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
