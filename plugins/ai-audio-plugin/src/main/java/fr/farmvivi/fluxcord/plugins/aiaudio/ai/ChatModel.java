package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import java.util.List;
import java.util.Map;

/**
 * Answers a conversation, possibly by asking for something first.
 *
 * <p>An interface for the same reason as {@link SpeechToText} and {@link TextToSpeech}: the plugin speaks the
 * OpenAI chat HTTP API, and both OpenAI and Ollama expose it. Unlike transcription, which needed a second
 * implementation because Ollama has no {@code /audio/transcriptions} route, one client covers both here.
 *
 * <p>Tools are described in plain Java rather than as a JSON schema, so nothing outside the HTTP client has to
 * know how a particular provider spells one.
 */
public interface ChatModel {

    /**
     * Asks for one reply.
     *
     * @param messages  the conversation, oldest first, starting with the system message
     * @param tools     what the model may call, possibly empty
     * @param maxTokens the longest answer to ask for; a spoken reply wants a short one
     * @return either something to say or a list of calls to make
     * @throws AiRequestException if the provider could not be reached or refused the request
     */
    Answer reply(List<Message> messages, List<Tool> tools, int maxTokens);

    /**
     * What came back: an answer, or a request to call something first.
     *
     * <p>Never both in practice, but the shape allows both because some providers send a sentence alongside
     * their calls.
     *
     * @param content   what to say, trimmed; empty when the model only wants to call something
     * @param toolCalls what it wants called, in order
     */
    record Answer(String content, List<ToolCall> toolCalls) {

        public Answer {
            content = content == null ? "" : content.strip();
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static Answer spoken(String content) {
            return new Answer(content, List.of());
        }

        /** @return true when the model asked for something before answering */
        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }

    /**
     * One call the model wants made.
     *
     * @param id        the provider's own identifier, which the result has to quote back
     * @param name      the tool's name
     * @param arguments the arguments as the model wrote them, still JSON
     */
    record ToolCall(String id, String name, String arguments) {
    }

    /**
     * Something the model may call.
     *
     * @param name        what the model names it
     * @param description what it does, in one sentence: this is all the model has to go on
     * @param parameters  the accepted arguments, by name
     */
    record Tool(String name, String description, Map<String, Parameter> parameters) {

        public Tool {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }

        /**
         * One argument of a tool.
         *
         * @param type        {@code string}, {@code integer} or {@code boolean}, as JSON schema names them
         * @param description what it means
         * @param required    whether the model must supply it
         */
        public record Parameter(String type, String description, boolean required) {

            public static Parameter requiredString(String description) {
                return new Parameter("string", description, true);
            }

            public static Parameter optionalInteger(String description) {
                return new Parameter("integer", description, false);
            }
        }
    }

    /**
     * One message of the conversation.
     *
     * <p>The role is what draws the trust boundary: {@link Role#SYSTEM} carries what an operator configured,
     * {@link Role#USER} carries what people said, and {@link Role#TOOL} carries what the plugin itself looked
     * up. Nothing from a conversation may ever be given the system role — that is how a spoken sentence would
     * become an instruction.
     *
     * @param role       who is speaking
     * @param content    what they said
     * @param toolCallId set only on a {@link Role#TOOL} message: which call this answers
     * @param toolCalls  set only on an {@link Role#ASSISTANT} message replaying the model's own calls
     */
    record Message(Role role, String content, String toolCallId, List<ToolCall> toolCalls) {

        public Message {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public static Message system(String content) {
            return new Message(Role.SYSTEM, content, null, List.of());
        }

        public static Message user(String content) {
            return new Message(Role.USER, content, null, List.of());
        }

        public static Message assistant(String content) {
            return new Message(Role.ASSISTANT, content, null, List.of());
        }

        /**
         * The model's own turn when it asked for calls, replayed so the provider can match the results to it.
         *
         * @param toolCalls what it asked for
         * @return the message to send back
         */
        public static Message assistantToolCalls(List<ToolCall> toolCalls) {
            return new Message(Role.ASSISTANT, "", null, toolCalls);
        }

        /**
         * The result of one call.
         *
         * @param toolCallId the id the model used
         * @param content    what the plugin found, as text
         * @return the message to send back
         */
        public static Message toolResult(String toolCallId, String content) {
            return new Message(Role.TOOL, content, toolCallId, List.of());
        }
    }

    /** The roles the chat API understands. */
    enum Role {
        /** Instructions from the operator. Trusted. */
        SYSTEM,
        /** What somebody said. Never trusted. */
        USER,
        /** What the bot said, or asked to call. */
        ASSISTANT,
        /** What the plugin looked up because the model asked. */
        TOOL;

        /** @return the role name the HTTP API expects */
        public String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
