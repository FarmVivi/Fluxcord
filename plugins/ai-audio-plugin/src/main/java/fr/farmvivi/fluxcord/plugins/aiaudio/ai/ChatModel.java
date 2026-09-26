package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import java.util.List;

/**
 * Answers a conversation.
 *
 * <p>An interface for the same reason as {@link SpeechToText} and {@link TextToSpeech}: the plugin speaks the
 * OpenAI chat HTTP API, and both OpenAI and Ollama expose it. Unlike transcription, which needed a
 * second implementation because Ollama has no {@code /audio/transcriptions} route, one client covers both
 * here.
 */
public interface ChatModel {

    /**
     * Asks for one reply.
     *
     * @param messages the conversation, oldest first, starting with the system message
     * @param maxTokens the longest answer to ask for; a spoken reply wants a short one
     * @return what to say, trimmed, or an empty string when the model chose to stay silent
     * @throws AiRequestException if the provider could not be reached or refused the request
     */
    String reply(List<Message> messages, int maxTokens);

    /**
     * One message of the conversation.
     *
     * <p>The role is what draws the trust boundary: {@link Role#SYSTEM} carries what an operator configured,
     * {@link Role#USER} carries what people said. Nothing from a conversation may ever be given the system
     * role — that is how a spoken sentence would become an instruction.
     *
     * @param role who is speaking
     * @param content what they said
     */
    record Message(Role role, String content) {

        public static Message system(String content) {
            return new Message(Role.SYSTEM, content);
        }

        public static Message user(String content) {
            return new Message(Role.USER, content);
        }

        public static Message assistant(String content) {
            return new Message(Role.ASSISTANT, content);
        }
    }

    /** The three roles the chat API understands. */
    enum Role {
        /** Instructions from the operator. Trusted. */
        SYSTEM,
        /** What somebody said. Never trusted. */
        USER,
        /** What the bot said before. */
        ASSISTANT;

        /** @return the role name the HTTP API expects */
        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
