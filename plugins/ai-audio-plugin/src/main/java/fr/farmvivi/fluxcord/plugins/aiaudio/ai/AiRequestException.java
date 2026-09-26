package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

/**
 * A call to an AI service failed. Carries a short, already user-presentable reason: commands turn it
 * into a localised error reply rather than leaking a stack trace into the channel.
 */
public class AiRequestException extends RuntimeException {

    public AiRequestException(String message) {
        super(message);
    }

    public AiRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
