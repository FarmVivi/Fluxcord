package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Asks a model whether a sentence was aimed at the bot.
 *
 * <p>Deliberately the <em>free local</em> model, the one already running for transcription: this question is
 * asked of sentences the bot might otherwise answer, which in a busy channel is most of them, and paying a
 * hosted model to decide whether to pay a hosted model is the wrong shape. It also wants to be fast rather
 * than clever - the alternative is the bot answering a beat late, or not at all.
 *
 * <p>Built on {@link ChatModel} rather than its own HTTP client, so it reaches Ollama, OpenAI or anything
 * else speaking the same API exactly as the rest of this plugin does.
 */
public class ModelAddressedToUs implements AddressedToUs {

    /**
     * Enough room for "yes" and nothing else.
     *
     * <p>Not one token: a reasoning model that cannot be talked out of thinking spends its whole budget on
     * the thinking and returns empty content, which this plugin has already been bitten by once. A handful
     * of tokens leaves room for the answer to survive a short preamble.
     */
    private static final int BUDGET = 12;

    /** How many earlier turns go in. A question needs what came before it; a transcript does not help. */
    private static final int CONTEXT_TURNS = 4;

    private static final String INSTRUCTIONS = """
            You decide one thing only: was the last sentence addressed to the assistant, or to another
            person in the room?

            You are reading a transcript of a voice channel where several people talk to each other. The
            assistant is one participant among them. People do not repeat its name in every sentence, so a
            follow-up to something it just said is addressed to it even without the name. A sentence that
            continues a conversation between two humans is not, even when it sounds like a question.

            These are addressed to the assistant, even with no name in them:
            - anything said in the second person to it, soon after it spoke
            - an order, or a request to do something it can do
            - a complaint, a correction or a thank-you about what it just did
            - an answer to a question it just asked

            These are not:
            - two people talking to each other about something else
            - somebody being named who is not the assistant
            - thinking out loud, or a reaction to the music

            Answer with exactly one word: YES if it was addressed to the assistant, NO otherwise. If you
            cannot tell, answer NO.""";

    private final Logger logger;
    private final ChatModel model;
    private final Executor workers;

    /**
     * @param logger  where the verdicts go, because this one is worth being able to audit after the fact
     * @param model   the model to ask - the local one
     * @param workers where the request runs; never the thread carrying audio
     */
    public ModelAddressedToUs(Logger logger, ChatModel model, Executor workers) {
        this.logger = logger;
        this.model = model;
        this.workers = workers;
    }

    @Override
    public void ask(String spoken, String botName, List<Turn> recent, Consumer<Boolean> answer) {
        if (spoken == null || spoken.isBlank()) {
            answer.accept(false);
            return;
        }
        workers.execute(() -> {
            boolean verdict;
            try {
                verdict = judge(spoken, botName, recent);
            } catch (RuntimeException e) {
                // A classifier that cannot answer must not become a classifier that says yes: the failure
                // mode of answering everything is the bot talking over a conversation it is not part of,
                // which is the thing this exists to stop.
                logger.debug("Could not decide whether \"{}\" was for us; assuming it was not", spoken, e);
                verdict = false;
            }
            answer.accept(verdict);
        });
    }

    private boolean judge(String spoken, String botName, List<Turn> recent) {
        List<ChatModel.Message> messages = new ArrayList<>();
        messages.add(ChatModel.Message.system(INSTRUCTIONS));
        messages.add(ChatModel.Message.user(transcript(spoken, botName, recent)));

        ChatModel.Answer reply = model.reply(messages, List.of(), BUDGET);
        String said = reply.content().strip().toUpperCase(Locale.ROOT);
        // Contains rather than equals: a small model will say "YES." or "Yes, it was", and refusing to read
        // that is how a working classifier looks broken. NO is checked first, because "NO" is a substring of
        // nothing useful while a sentence containing both words is a model that did not answer the question.
        boolean no = said.contains("NO");
        boolean yes = said.contains("YES");
        boolean verdict = yes && !no;
        logger.debug("Addressed to us? {} -> {} (model said \"{}\")", spoken, verdict, reply.content().strip());
        return verdict;
    }

    /**
     * The room as the model should read it: who said what, oldest first, with the sentence being judged
     * last and marked as the one in question.
     */
    private String transcript(String spoken, String botName, List<Turn> recent) {
        String called = botName == null || botName.isBlank() ? "the assistant" : botName;
        StringBuilder out = new StringBuilder("The assistant is called \"").append(called)
                .append("\".\n\nTranscript:\n");
        List<Turn> context = recent == null ? List.of() : recent;
        int from = Math.max(0, context.size() - CONTEXT_TURNS);
        for (Turn turn : context.subList(from, context.size())) {
            // The assistant own turns are marked as such. Listing them under its name alone was not
            // enough: MEASURED live 2026-10-04, "tu m as menti, fais la commande" thirteen seconds
            // after the bot had spoken was judged NO, which only makes sense if the model could not
            // tell which line was the assistant.
            boolean mine = turn.speaker() != null && turn.speaker().equalsIgnoreCase(called);
            out.append("- ").append(turn.speaker()).append(mine ? " (the assistant)" : "")
                    .append(": ").append(turn.text()).append('\n');
        }
        out.append("\nThe sentence to decide about is this one:\n- ").append(spoken.strip());
        return out.toString();
    }
}
