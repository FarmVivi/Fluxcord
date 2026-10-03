package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.plugins.aiaudio.conversation.Address;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A voice channel with eight people in it: who closes a turn, and which turns deserve an answer.
 *
 * <p>Left to the service, a full-duplex session answers every single utterance and lets anybody with a
 * microphone cut the bot off mid-sentence. That is pleasant with one person in the channel and unusable with
 * eight — the bot talks constantly and finishes nothing. These tests pin the other arrangement: the plugin
 * closes turns on silence, the bot hears and remembers everything, and it only speaks when somebody said its
 * name.
 *
 * <p>The clock is mutable here, unlike in {@link RealtimeConversationTest}, because silence is the thing
 * being tested and silence is measured in time passing.
 */
class RealtimeTurnTakingTest {

    private static final long START = 1_000_000L;
    private static final long SILENCE_MS = 1_200;

    private final AtomicLong now = new AtomicLong(START);
    private final List<String> sent = new ArrayList<>();
    private final List<PcmAudio> played = new ArrayList<>();
    private final List<Turn> remembered = new ArrayList<>();
    private final AtomicInteger stops = new AtomicInteger();

    private RealtimeConversation conversation;
    private Consumer<RealtimeProtocol.Event> events;

    /** Opens a conversation in which this plugin closes turns and the bot wakes on its name. */
    private void open(RealtimeProtocol protocol) {
        conversation = new RealtimeConversation(
                LoggerFactory.getLogger(RealtimeTurnTakingTest.class), protocol, now::get, List.of(),
                played::add, stops::incrementAndGet, remembered::add, message -> { },
                false, SILENCE_MS, spoken -> Address.addressed(spoken, List.of("Fluxcord")));
        conversation.start(snapshot(), "You are Fluxcord.", "marin", "bot-1", sink -> {
            events = sink;
            // Honest about being closed: a double that always answers "open" hides every bug about
            // letting go of a session.
            return new RealtimeLink() {
                private boolean open = true;

                @Override
                public void send(String frame) {
                    sent.add(frame);
                }

                @Override
                public void close() {
                    open = false;
                }

                @Override
                public boolean isOpen() {
                    return open;
                }
            };
        }, List.of("Fluxcord"));
        sent.clear();
    }

    @BeforeEach
    void setUp() {
        open(new OpenAiRealtime());
    }

    private static PersonaSnapshot snapshot() {
        ConversationContext context = new ConversationContext("g1", "My Server", "c1", "General",
                "Fluxcord", List.of(), List.of(), List.of());
        return new PersonaSnapshot(new Persona("Fluxcord", List.of(), "neutre", Locale.FRANCE, ""),
                Mood.neutral(START), context,
                List.of(new PersonaSnapshot.Acquaintance("u1", "Victor",
                        PersonaSnapshot.Acquaintance.Level.REGULAR, 10)));
    }

    private void somebodySpeaks() {
        conversation.hear("u1", "Victor", new PcmAudio(new byte[960], 24_000, 1));
    }

    private void silenceFalls() {
        now.addAndGet(SILENCE_MS + 100);
        conversation.tick();
    }

    private boolean sentAny(String fragment) {
        return sent.stream().anyMatch(frame -> frame.contains(fragment));
    }

    // ---------------------------------------------------------------- closing the turn

    @Test
    void aTurnIsClosedWhenNobodyHasSpokenForAMoment() {
        // MEASURED against the real service: server-side detection has to hear silence to end a turn, and
        // JDA stops delivering packets the moment somebody stops talking - so nothing would ever end it.
        somebodySpeaks();

        assertFalse(sentAny("input_audio_buffer.commit"), "not while they are still talking");

        silenceFalls();

        assertTrue(sentAny("input_audio_buffer.commit"));
    }

    @Test
    void silenceThatIsNotLongEnoughClosesNothing() {
        somebodySpeaks();
        now.addAndGet(SILENCE_MS - 200);

        conversation.tick();

        assertFalse(sentAny("input_audio_buffer.commit"), "a pause for breath is not the end of a sentence");
    }

    @Test
    void aTickWithNoTurnOpenDoesNothingAtAll() {
        silenceFalls();

        assertTrue(sent.isEmpty());
    }

    @Test
    void theTurnIsClosedOnceAndReopensOnTheNextPacket() {
        somebodySpeaks();
        silenceFalls();
        silenceFalls();

        assertEquals(1, sent.stream().filter(f -> f.contains("input_audio_buffer.commit")).count());

        somebodySpeaks();
        silenceFalls();

        assertEquals(2, sent.stream().filter(f -> f.contains("input_audio_buffer.commit")).count());
    }

    // ---------------------------------------------------------------- which turns are answered

    @Test
    void aSentenceThatNamesTheBotIsAnswered() {
        somebodySpeaks();
        silenceFalls();
        sent.clear();

        events.accept(new RealtimeProtocol.Event.HeardFromSomebody("Fluxcord, il est quelle heure ?"));

        assertTrue(sentAny("response.create"));
    }

    @Test
    void aSentenceAboutSomethingElseIsHeardAndRememberedButNotAnswered() {
        // The whole point. Eight people talking is eight transcriptions and no interruptions.
        somebodySpeaks();
        silenceFalls();
        sent.clear();

        events.accept(new RealtimeProtocol.Event.HeardFromSomebody("il fait beau, on sort ce week-end ?"));

        assertFalse(sentAny("response.create"), "it was not talking to us");
        assertEquals(1, remembered.size(), "but the bot heard it and keeps it");
        assertEquals("il fait beau, on sort ce week-end ?", remembered.get(0).text());
    }

    @Test
    void everythingHeardIsRememberedWhetherOrNotItWasAnswered() {
        events.accept(new RealtimeProtocol.Event.HeardFromSomebody("premiere phrase"));
        events.accept(new RealtimeProtocol.Event.HeardFromSomebody("Fluxcord, deuxieme"));

        assertEquals(List.of("premiere phrase", "Fluxcord, deuxieme"),
                remembered.stream().map(Turn::text).toList());
    }

    // ---------------------------------------------------------------- interruption

    @Test
    void theBotIsNotCutOffByPeopleTalkingAmongstThemselves() {
        // What this replaces: any noise at all stopping the bot mid-sentence.
        events.accept(new RealtimeProtocol.Event.AudioDelta(new PcmAudio(new byte[960], 24_000, 1)));
        sent.clear();

        events.accept(new RealtimeProtocol.Event.HeardFromSomebody("tu as vu le match hier ?"));

        assertFalse(sentAny("response.cancel"));
        assertEquals(0, stops.get(), "nothing was dropped from the playback queue");
    }

    @Test
    void theBotIsCutOffWhenSomebodyAsksItSomethingElse() {
        events.accept(new RealtimeProtocol.Event.AudioDelta(new PcmAudio(new byte[960], 24_000, 1)));
        sent.clear();

        events.accept(new RealtimeProtocol.Event.HeardFromSomebody("Fluxcord, arrete"));

        assertTrue(sentAny("response.cancel"));
        assertEquals(1, stops.get(), "and what was queued on this side is dropped too");
    }

    @Test
    void thereIsNothingToInterruptWhenTheBotIsNotTalking() {
        events.accept(new RealtimeProtocol.Event.HeardFromSomebody("Fluxcord, salut"));

        assertFalse(sentAny("response.cancel"));
        assertEquals(0, stops.get());
    }

    @Test
    void anAnswerThatFinishedLeavesNothingToInterrupt() {
        events.accept(new RealtimeProtocol.Event.AudioDelta(new PcmAudio(new byte[960], 24_000, 1)));
        events.accept(new RealtimeProtocol.Event.ResponseDone());
        sent.clear();

        events.accept(new RealtimeProtocol.Event.HeardFromSomebody("Fluxcord, encore"));

        assertFalse(sentAny("response.cancel"));
    }

    // ---------------------------------------------------------------- the other service's control flow

    @Test
    void onAServiceWhereEndingTheTurnAnswersItTheTurnIsHeldOpenUntilAddressed() {
        // Google has one gesture for both, so an utterance nobody addressed to the bot cannot be closed
        // without also asking for an answer - it is left open and the words keep accumulating.
        open(new GeminiRealtime("gemini-2.5-flash-native-audio-latest"));

        somebodySpeaks();
        events.accept(new RealtimeProtocol.Event.HeardDelta("il fait "));
        events.accept(new RealtimeProtocol.Event.HeardDelta("beau"));
        silenceFalls();

        assertFalse(sentAny("activityEnd"), "closing it would be asking for an answer");

        events.accept(new RealtimeProtocol.Event.HeardDelta(", Fluxcord ?"));
        silenceFalls();

        assertTrue(sentAny("activityEnd"), "now it has been addressed");
        assertEquals(1, remembered.size());
        assertEquals("il fait beau, Fluxcord ?", remembered.get(0).text(),
                "the fragments are one utterance, not three");
    }

    @Test
    void aTurnIsAnnouncedBeforeTheAudioOnAServiceThatNeedsTelling() {
        open(new GeminiRealtime("gemini-2.5-flash-native-audio-latest"));

        somebodySpeaks();

        assertTrue(sentAny("activityStart"));
    }
    @Test
    void aServiceHangingUpOnScheduleEndsTheSessionWithoutTellingTheChannel() {
        // The distinction that matters to whoever is in the voice channel: a session reaching its
        // documented age limit is housekeeping, a broken connection is not.
        events.accept(new RealtimeProtocol.Event.TranscriptDelta("j'allais dire"));

        events.accept(new RealtimeProtocol.Event.ClosingSoon("the service closes the session in 10s"));

        assertFalse(conversation.isOpen(), "and the owner can see it has to open another");
        assertEquals(1, remembered.size(), "what the bot had said is kept");
        assertEquals("j'allais dire", remembered.get(0).text());
    }

    @Test
    void aSecondWarningChangesNothing() {
        events.accept(new RealtimeProtocol.Event.ClosingSoon("first"));

        assertDoesNotThrow(() -> events.accept(new RealtimeProtocol.Event.ClosingSoon("second")));
    }

}
