package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The one tool whose whole job is to stop the meter running. */
class HangUpToolsTest {

    private final List<String> asked = new ArrayList<>();
    private final HangUpTools tools = new HangUpTools(asked::add);

    @Test
    void itOffersExactlyOneToolAndTakesNoArguments() {
        List<ChatModel.Tool> declared = tools.declarations();

        assertEquals(1, declared.size());
        assertEquals(HangUpTools.END_THE_CONVERSATION, declared.get(0).name());
        assertTrue(declared.get(0).parameters().isEmpty(),
                "nothing to get wrong is the point: a model that has to write an argument writes a bad one");
    }

    @Test
    void itSaysWhatStayingOpenCostsAndWhatEndingDoesNot() {
        // Not a style check. A model given only a rule about when it may hang up either never hangs up or
        // hangs up mid-request; what makes it act correctly is knowing the connection is billed and that
        // ending loses nothing.
        String description = tools.declarations().get(0).description().toLowerCase(java.util.Locale.ROOT);

        assertTrue(description.contains("costs money"), "why to call it");
        assertTrue(description.contains("voice channel"), "that it is not leaving");
        assertTrue(description.contains("still asking"), "when not to call it");
    }

    @Test
    void itOwnsOnlyItsOwnName() {
        assertTrue(tools.handles(HangUpTools.END_THE_CONVERSATION));
        assertFalse(tools.handles("search_the_web"));
        assertFalse(tools.handles("end_the_conversation_now"));
    }

    @Test
    void runningItAsksForTheSessionToEndAndTellsTheModelToFinishSpeaking() {
        String answer = tools.execute(
                new ChatModel.ToolCall("call-1", HangUpTools.END_THE_CONVERSATION, "{}"), null, 0);

        assertEquals(1, asked.size(), "asked exactly once");
        assertFalse(asked.get(0).isBlank(), "with a reason, which is the only record of who decided");
        assertTrue(answer.contains("finished speaking"),
                "the model has to know the goodbye still gets out, or it does not say one");
        assertTrue(answer.contains("voice channel"),
                "and that it has not gone deaf, or it says goodbye twice");
    }
}
