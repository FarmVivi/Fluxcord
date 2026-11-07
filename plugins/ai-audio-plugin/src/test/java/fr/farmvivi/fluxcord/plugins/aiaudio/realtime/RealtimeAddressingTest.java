package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.plugins.aiaudio.conversation.Address;
import fr.farmvivi.fluxcord.plugins.aiaudio.conversation.AddressedToUs;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.Mood;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.Persona;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who the bot is being spoken to, in three parts.
 *
 * <p>The problem, in Victor's words: "les gens parlent beaucoup dans un channel discord donc va falloir
 * trouver un procédé pour bien détecter l'inactivité / le bot doit de lui-même détecter qu'on lui parle
 * plus". Inside an open session the test used to be "something was heard", which made the bot a participant
 * in every conversation held in front of it; requiring the name in every sentence is what made talking to it
 * feel like summoning it anew each time.
 *
 * <p>So: the name settles it, the moments right after an exchange settle it, and only what is left over -
 * the grey zone - is put to a model. These tests pin all three, and in particular that the two free tests
 * never pay for the third.
 */
class RealtimeAddressingTest {

    private static final long START = 1_000_000L;
    private static final long SILENCE_MS = 1_200;
    private static final long FOLLOW_UP_MS = 12_000;

    private final AtomicLong now = new AtomicLong(START);
    private final List<String> sent = new ArrayList<>();
    private final List<Turn> remembered = new ArrayList<>();

    /** Every sentence put to the classifier, in order. */
    private final List<String> asked = new ArrayList<>();
    /** What the classifier answers next. */
    private boolean verdict;

    private RealtimeConversation conversation;
    private Consumer<RealtimeProtocol.Event> events;

    private final AddressedToUs classifier = (spoken, botName, recent, answer) -> {
        asked.add(spoken);
        answer.accept(verdict);
    };

    @BeforeEach
    void setUp() {
        conversation = new RealtimeConversation(
                LoggerFactory.getLogger(RealtimeAddressingTest.class),
                new GeminiRealtime("gemini-2.5-flash-native-audio-latest"), now::get, List.of(),
                audio -> {
                }, () -> {
        }, remembered::add, message -> {
        }, false, SILENCE_MS, spoken -> Address.addressed(spoken, List.of("Chris")),
                Runnable::run, Runnable::run, classifier, FOLLOW_UP_MS);
        conversation.start(snapshot(), "You are Chris.", "Puck", "bot-1", sink -> {
            events = sink;
            return new RealtimeLink() {
                @Override
                public void send(String frame) {
                    sent.add(frame);
                }

                @Override
                public void close() {
                    // nothing to release
                }

                @Override
                public boolean isOpen() {
                    return true;
                }
            };
        });
        sent.clear();
    }

    @Test
    void theNameSettlesItWithoutAskingAnybody() {
        hears("Chris, tu dors ?");

        assertTrue(answered(), "the name is the whole point of a wake word");
        assertTrue(asked.isEmpty(), "and it must not cost a model call: " + asked);
    }

    @Test
    void aSentenceRightAfterAnExchangeNeedsNoNameAndNoQuestion() {
        hears("Chris, tu dors ?");
        botFinishesAnswering();
        sent.clear();

        now.addAndGet(3_000);
        hears("et il est quelle heure ?");

        assertTrue(answered(), "a follow-up to what the bot just said is addressed to it");
        assertTrue(asked.isEmpty(), "the common case pays nothing: " + asked);
    }

    @Test
    void aSentenceLongAfterTheLastExchangeIsPutToTheClassifier() {
        hears("Chris, tu dors ?");
        botFinishesAnswering();
        sent.clear();

        now.addAndGet(FOLLOW_UP_MS + 1_000);
        verdict = false;
        hears("tu as vu le match hier ?");

        assertEquals(List.of("tu as vu le match hier ?"), asked);
        assertFalse(answered(), "two people talking to each other is not the bot's business");
    }

    @Test
    void theSameSentenceIsNotPutToItTwice() {
        // The regression this pins, MEASURED live 2026-10-04: a turn nobody addressed to the bot is held
        // open on purpose, so the same words were still there on the next tick - and the next. The guard
        // only covered a question in flight, so once an answer came back the sentence went out again, about
        // four times a second for as long as the session stayed open.
        now.addAndGet(FOLLOW_UP_MS + 1_000);
        verdict = false;
        hears("il fait beau aujourd'hui");

        conversation.tick();
        now.addAndGet(SILENCE_MS + 100);
        conversation.tick();
        now.addAndGet(SILENCE_MS + 100);
        conversation.tick();

        assertEquals(1, asked.size(), "judged once, not once per tick: " + asked);
    }

    @Test
    void moreWordsAreADifferentSentenceAndWorthAFreshQuestion() {
        now.addAndGet(FOLLOW_UP_MS + 1_000);
        verdict = false;
        hears("il fait beau");

        // Deliberately no name in the added words: with one, the free test would settle it and no second
        // question would be asked - which is correct, and not what this is about.
        events.accept(new RealtimeProtocol.Event.HeardDelta(", tu peux ouvrir la fenetre ?"));
        silenceFalls();

        assertEquals(2, asked.size(), "the words changed, so the verdict may too: " + asked);
    }

    @Test
    void aVerdictInOurFavourAnswersAndOpensTheWindow() {
        now.addAndGet(FOLLOW_UP_MS + 1_000);
        verdict = true;
        hears("tu peux baisser le son ?");

        assertEquals(1, asked.size());
        assertTrue(answered(), "it decided this was for us, so it is answered");

        // And being answered is an exchange, so the next sentence is free again.
        sent.clear();
        asked.clear();
        botFinishesAnswering();
        now.addAndGet(2_000);
        hears("merci");

        assertTrue(asked.isEmpty(), "the window reopened: " + asked);
    }

    /** One whole utterance heard, then silence long enough for the turn to be judged. */
    private void hears(String text) {
        conversation.hear("u1", "Totor", audio());
        events.accept(new RealtimeProtocol.Event.HeardDelta(text));
        silenceFalls();
    }

    private void silenceFalls() {
        now.addAndGet(SILENCE_MS + 100);
        conversation.tick();
    }

    /** The bot having spoken: what closes an exchange, and what the follow-up window is measured from. */
    private void botFinishesAnswering() {
        events.accept(new RealtimeProtocol.Event.TranscriptDelta("oui"));
        events.accept(new RealtimeProtocol.Event.ResponseDone());
    }

    /** @return true when the turn was closed, which on this service is the request for an answer */
    private boolean answered() {
        return sent.stream().anyMatch(frame -> frame.contains("activityEnd"));
    }

    private static PcmAudio audio() {
        return new PcmAudio(new byte[320], PcmAudio.DISCORD_SAMPLE_RATE, 1);
    }

    private static PersonaSnapshot snapshot() {
        ConversationContext conversation = new ConversationContext("g1", "My Server", "c1", "General",
                "Chris", List.of(), List.of(), List.of());
        return new PersonaSnapshot(new Persona("Fluxcord", List.of(), "neutre", Locale.FRANCE, ""),
                Mood.neutral(START), conversation, List.of());
    }
}
