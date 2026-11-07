package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;

import java.util.List;
import java.util.function.Consumer;

/**
 * Whether a sentence was aimed at the bot, when the name alone cannot say.
 *
 * <p>The problem this exists for, in Victor's words: "les gens parlent beaucoup dans un channel discord donc
 * va falloir trouver un procédé pour bien détecter l'inactivité / le bot doit de lui-même détecter qu'on lui
 * parle plus". Inside an open session the test used to be "something was heard", which makes the bot a
 * participant in every conversation held in front of it. Requiring the name in every sentence is the other
 * extreme, and it is what made talking to it feel like summoning it anew each time.
 *
 * <p>So the decision is in three parts, and only the last one is here. The name settles it for free. The
 * moments right after the bot was named or answered settle it for free as well, because that is what a
 * follow-up looks like. What is left is the grey zone - a sentence arriving a while after the last exchange,
 * in a session that is still open - and that is the question this is asked.
 *
 * <p><strong>Asynchronous on purpose.</strong> The answer comes from a model over the network, which takes
 * long enough that asking for it on the thread carrying the audio would stall the audio. The caller hands
 * over a callback and carries on; see {@code RealtimeConversation}'s inbox for where the answer lands.
 */
public interface AddressedToUs {

    /**
     * Asks whether this sentence was meant for the bot.
     *
     * @param spoken  what was said, as the local transcriber wrote it
     * @param botName what the bot is called where this was said - its Discord name, or the server's own
     *                name for it. Passed per question rather than held, because it is per guild and can
     *                change under a running session.
     * @param recent the last few turns, oldest first, as context - a question only makes sense against what
     *               came before it
     * @param answer called once with the verdict. May be called on any thread, and may be called before this
     *               method returns; a caller that mutates state in it is responsible for getting there
     *               safely. Called with false when the question could not be answered at all, because
     *               answering a sentence nobody aimed at the bot is worse than missing one.
     */
    void ask(String spoken, String botName, List<Turn> recent, Consumer<Boolean> answer);

    /**
     * @return an implementation that never thinks anything was aimed at the bot. The honest default when no
     *         model is configured for this: the two free tests still apply, so the bot answers its name and
     *         follow-ups to it, and stays out of everything else.
     */
    static AddressedToUs never() {
        return (spoken, botName, recent, answer) -> answer.accept(false);
    }

    /**
     * @return an implementation that thinks everything was aimed at the bot - the behaviour before any of
     *         this existed, kept so it can be configured back on if the judgement turns out worse than the
     *         noise it replaces
     */
    static AddressedToUs always() {
        return (spoken, botName, recent, answer) -> answer.accept(true);
    }
}
