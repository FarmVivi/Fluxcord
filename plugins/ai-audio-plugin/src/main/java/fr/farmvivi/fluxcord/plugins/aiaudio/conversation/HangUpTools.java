package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaSnapshot;

import java.util.List;
import java.util.function.Consumer;

/**
 * Lets the model hang up when the conversation is over.
 *
 * <p>Until now a session only ended by running out of patience: the engage window elapses after the last
 * thing said or answered, so <strong>every exchange is followed by thirty seconds of a paid full-duplex
 * connection listening to a room that has finished with it</strong>. The model is the one thing that knows
 * the request was dealt with and nobody is waiting for anything, and it had no way to say so.
 *
 * <p>Two details that are the whole difference between this working and being worse than the window.
 *
 * <p><strong>It ends after the answer, not instead of it.</strong> Closing the link when the tool runs would
 * cut the goodbye off mid-word, because the tool phase comes before the model speaks: the frames carrying
 * "de rien, à plus" have not been sent yet. So the call only asks, and the conversation closes once the
 * response it belongs to is done.
 *
 * <p><strong>Hanging up is not leaving.</strong> It closes the hosted session and nothing else - the bot
 * stays in the voice channel, the free local transcriber takes the audio back, and the next time anybody says
 * the bot's name a session opens again with the recent turns handed to it. That is the same path the services
 * already take when they close a session on their own schedule, which is why it was not worth inventing a
 * second one.
 *
 * <p>Offered only where it means something. The turn-based path has no session to end, so this group is added
 * by the realtime service rather than sitting in the plugin's shared tool list.
 */
public class HangUpTools implements ToolSource {

    /** The tool name, as the model sees it. */
    public static final String END_THE_CONVERSATION = "end_the_conversation";

    private final Consumer<String> endAfterSpeaking;

    /**
     * @param endAfterSpeaking asks for the session to be closed once the current answer has been spoken,
     *                         given the reason to put in the log
     */
    public HangUpTools(Consumer<String> endAfterSpeaking) {
        this.endAfterSpeaking = endAfterSpeaking;
    }

    @Override
    public List<ChatModel.Tool> declarations() {
        return List.of(new ChatModel.Tool(END_THE_CONVERSATION,
                // Written as what it costs rather than as a rule, because a model given a rule about when it
                // may hang up either never does or does it mid-request. What it needs to know is that
                // staying open is not free and that hanging up loses nothing.
                "End the voice conversation, once you have finished what you are saying. Call it when what "
                        + "was asked of you is done and nobody is waiting for anything else - a goodbye, a "
                        + "thank you, or simply nothing left to answer. Staying connected costs money every "
                        + "second, and ending costs nothing: you stay in the voice channel and listen for "
                        + "your name, and whoever says it again gets you straight back with the "
                        + "conversation remembered. Do not call it while anybody is still asking you "
                        + "something.",
                java.util.Map.of()));
    }

    @Override
    public boolean handles(String name) {
        return END_THE_CONVERSATION.equals(name);
    }

    @Override
    public String execute(ChatModel.ToolCall call, PersonaSnapshot snapshot, long nowMs) {
        endAfterSpeaking.accept("the model said the conversation was over");
        // Said in the past tense and with what survives it, because a model told only "done" sometimes
        // announces the disconnection out loud, and a model that thinks it has gone deaf says goodbye twice.
        return "The conversation will end as soon as you have finished speaking, so say whatever closes it"
                + " now. You stay in the voice channel and keep listening for your name.";
    }
}
