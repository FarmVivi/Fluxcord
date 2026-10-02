package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaSnapshot;

import java.util.List;

/**
 * A group of related things the model may call.
 *
 * <p>Extracted when the second one arrived: with the memory alone a field was enough, with search as well
 * {@link ConversationService} would have grown a chain of {@code if} per group. It stays a dispatcher instead,
 * and a third group — a calculator, the weather, whatever — is a constructor argument.
 *
 * <p>The contract is the one the memory tools already had, and it is the important part:
 * <strong>{@link #execute} never throws.</strong> A model writes the arguments itself, so every way it can get
 * them wrong has to come back as a sentence it can act on; an exception propagating from here ends the spoken
 * turn, which to everyone in the channel sounds like the bot ignoring them.
 */
public interface ToolSource {

    /** @return what the model is told it can call, in the order they are offered */
    List<ChatModel.Tool> declarations();

    /**
     * @param name a tool name the model used
     * @return true when this group owns it and {@link #execute} can run it
     */
    boolean handles(String name);

    /**
     * Runs one call and returns what to hand back to the model.
     *
     * @param call     what the model asked for, with the arguments as it wrote them
     * @param snapshot the conversation, for a group that needs to know where it is or who is present
     * @param nowMs    the current time, for a group that reports ages rather than timestamps
     * @return the result as text, never null
     */
    String execute(ChatModel.ToolCall call, PersonaSnapshot snapshot, long nowMs);
}
