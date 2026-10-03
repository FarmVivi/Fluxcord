package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationMemory;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import fr.farmvivi.fluxcord.plugins.aiaudio.testing.MemoryDataStorage;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * When the bot starts paying to listen, and when it stops.
 *
 * <p>Measured at about ten audio input tokens per second of speech, a hosted session left open in a busy
 * channel is paid to overhear a conversation it is not part of. This is the class that stops that, and the
 * things worth pinning are the three that cost money or break the illusion: nothing is opened until the bot
 * is addressed, a follow-up does not need the name again, and a session that nobody is using closes.
 *
 * <p>The fourth is the subtle one. The sentence that woke the bot was heard by the <em>local</em>
 * transcriber, before the session existed — so unless it is handed over, the bot opens a connection and
 * waits for a question that has already been asked.
 */
class WakeGateTest {

    private static final long START = 5_000_000L;
    private static final long WINDOW_MS = 30_000;
    private static final String GUILD_ID = "g1";

    private final AtomicLong now = new AtomicLong(START);
    private final List<String> opened = new ArrayList<>();
    private final List<String> closed = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();
    private final List<List<Turn>> historyGiven = new ArrayList<>();
    private final List<PcmAudio> heard = new ArrayList<>();
    /** The diversion currently installed on the local handler, or null when transcribing locally. */
    private BiConsumer<String, PcmAudio> diversion;
    private boolean canOpen = true;
    private long lastActivity = START;
    /** Whether the hosted session still has a connection; the services close one on their own schedule. */
    private boolean alive = true;

    private ConversationMemory memory;
    private WakeGate gate;
    private Guild guild;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory(
                new PluginDataStorageAdapter("ai-audio-plugin", new MemoryDataStorage()), 20, 20, 20);
        guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);

        WakeGate.Engagement engagement = new WakeGate.Engagement(
                (g, output) -> {
                    if (!canOpen) {
                        return false;
                    }
                    opened.add(g.getId());
                    return true;
                },
                g -> closed.add(g.getId()),
                (g, divert) -> {
                    diversion = divert;
                    return true;
                },
                (g, userId, audio) -> heard.add(audio),
                (g, question, history) -> {
                    asked.add(question);
                    historyGiven.add(history);
                },
                g -> lastActivity,
                g -> alive);

        gate = new WakeGate(LoggerFactory.getLogger(WakeGateTest.class), now::get, memory, engagement,
                WINDOW_MS);
    }

    /**
     * One sentence, a second after the last.
     *
     * <p>The clock has to move: a turn is stored under {@code timestamp-userId}, so two sentences from the
     * same speaker in the same millisecond overwrite each other. Real speech cannot do that — the segmenter
     * enforces a minimum utterance — but a test with a frozen clock can, and it costs an hour to work out.
     */
    private Turn said(String userId, String speaker, String text) {
        Turn turn = new Turn(now.addAndGet(1_000), userId, speaker, GUILD_ID, "My Server", "c1", "General",
                text);
        memory.remember(turn);
        return turn;
    }

    // ---------------------------------------------------------------- opening

    @Test
    void nothingIsPaidForUntilTheBotIsAddressed() {
        // The gate is only called once the local, free transcription has decided the name was said, so the
        // thing to check here is that engaging is what opens the session - not /converse start.
        assertFalse(gate.isEngaged(guild));
        assertTrue(opened.isEmpty());
        assertNull(diversion, "the audio is going to the local transcriber");
    }

    @Test
    void beingAddressedOpensTheSessionAndSendsTheAudioThere() {
        assertTrue(gate.engage(guild, mock(MessageChannel.class), said("u1", "Victor", "Fluxcord, salut")));

        assertEquals(List.of(GUILD_ID), opened);
        assertTrue(gate.isEngaged(guild));
        assertNotNull(diversion, "the packets now go to the hosted session");

        diversion.accept("u1", new PcmAudio(new byte[960], 48_000, 2));
        assertEquals(1, heard.size());
    }

    @Test
    void theSessionIsHandedTheQuestionItCouldNotHaveHeard() {
        // Without this the bot opens a connection and waits for a question that has already been asked.
        gate.engage(guild, mock(MessageChannel.class), said("u1", "Victor", "Fluxcord, il est quelle heure ?"));

        assertEquals(List.of("Fluxcord, il est quelle heure ?"), asked);
    }

    @Test
    void theSessionIsAlsoHandedWhatWasSaidBeforeItExisted() {
        said("u1", "Victor", "on part a quelle heure demain ?");
        said("u2", "Alice", "vers huit heures je pense");
        Turn question = said("u1", "Victor", "Fluxcord, tu confirmes ?");

        gate.engage(guild, mock(MessageChannel.class), question);

        List<String> history = historyGiven.get(0).stream().map(Turn::text).toList();
        assertEquals(List.of("on part a quelle heure demain ?", "vers huit heures je pense"), history);
        assertFalse(history.contains("Fluxcord, tu confirmes ?"),
                "the question goes separately; sent twice it gets answered twice");
    }

    @Test
    void engagingTwiceChangesNothingAndCostsNothing() {
        gate.engage(guild, mock(MessageChannel.class), said("u1", "Victor", "Fluxcord, salut"));
        gate.engage(guild, mock(MessageChannel.class), said("u1", "Victor", "Fluxcord, encore"));

        assertEquals(1, opened.size());
        assertEquals(1, asked.size(), "the second sentence was already going there as audio");
    }

    @Test
    void aSessionThatCannotBeOpenedLeavesEverythingLocal() {
        canOpen = false;

        assertFalse(gate.engage(guild, mock(MessageChannel.class), said("u1", "Victor", "Fluxcord ?")));

        assertFalse(gate.isEngaged(guild));
        assertNull(diversion, "nothing was diverted into a session that does not exist");
    }

    // ---------------------------------------------------------------- the window

    @Test
    void aFollowUpNeedsNoNameBecauseTheSessionIsStillOpen() {
        gate.engage(guild, mock(MessageChannel.class), said("u1", "Victor", "Fluxcord, salut"));

        now.addAndGet(WINDOW_MS - 1_000);
        lastActivity = now.get();
        gate.tick();

        assertTrue(gate.isEngaged(guild), "somebody is still talking to it");
        assertTrue(closed.isEmpty());
    }

    @Test
    void aSessionNobodyIsUsingCloses() {
        gate.engage(guild, mock(MessageChannel.class), said("u1", "Victor", "Fluxcord, salut"));

        now.addAndGet(WINDOW_MS + 1);
        gate.tick();

        assertFalse(gate.isEngaged(guild));
        assertEquals(List.of(GUILD_ID), closed);
        assertNull(diversion, "and the local transcriber has the audio back");
    }

    @Test
    void theWindowIsCountedFromTheLastThingSaidOrAnswered() {
        // Counted from the answer too, or the bot's own window cuts it off mid-sentence.
        gate.engage(guild, mock(MessageChannel.class), said("u1", "Victor", "Fluxcord, raconte"));

        now.addAndGet(WINDOW_MS - 500);
        lastActivity = now.get(); // the bot is still speaking
        gate.tick();
        assertTrue(gate.isEngaged(guild));

        now.addAndGet(WINDOW_MS + 1);
        gate.tick();
        assertFalse(gate.isEngaged(guild));
    }

    @Test
    void aTickWithNothingEngagedIsHarmless() {
        assertDoesNotThrow(() -> gate.tick());
        assertTrue(closed.isEmpty());
    }

    // ---------------------------------------------------------------- stopping

    @Test
    void disengagingGivesTheAudioBackAndClosesTheSession() {
        gate.engage(guild, mock(MessageChannel.class), said("u1", "Victor", "Fluxcord, salut"));

        assertTrue(gate.disengage(guild, "the conversation was stopped"));

        assertFalse(gate.isEngaged(guild));
        assertEquals(List.of(GUILD_ID), closed);
        assertNull(diversion);
    }

    @Test
    void disengagingSomethingThatWasNeverEngagedIsHarmless() {
        assertFalse(gate.disengage(guild, "nothing to do"));
        assertTrue(closed.isEmpty());
    }

    @Test
    void shuttingDownClosesWhatIsOpen() {
        gate.engage(guild, mock(MessageChannel.class), said("u1", "Victor", "Fluxcord, salut"));

        gate.shutdown();

        assertFalse(gate.isEngaged(guild));
        assertEquals(List.of(GUILD_ID), closed);
        assertDoesNotThrow(gate::shutdown);
    }
    @Test
    void aSessionTheServiceClosedIsNoticedAndGivenBackToTheLocalTranscriber() {
        // Both services end a session on their own schedule - sixty minutes for OpenAI, about fifteen for
        // an audio-only Google one. Noticing turns that from a dead conversation into a pause.
        gate.engage(guild, mock(MessageChannel.class), said("u1", "Victor", "Fluxcord, salut"));
        lastActivity = now.get();

        alive = false;
        gate.tick();

        assertFalse(gate.isEngaged(guild));
        assertEquals(List.of(GUILD_ID), closed);
        assertNull(diversion, "the local transcriber is listening again");
    }

    @Test
    void andTheNextTimeTheNameIsSaidAFreshSessionOpensWithTheContext() {
        gate.engage(guild, mock(MessageChannel.class), said("u1", "Victor", "Fluxcord, salut"));
        said("u1", "Victor", "et aussi quelque chose d'autre");
        alive = false;
        gate.tick();

        alive = true;
        assertTrue(gate.engage(guild, mock(MessageChannel.class),
                said("u1", "Victor", "Fluxcord, tu es toujours la ?")));

        assertEquals(2, opened.size());
        assertEquals("Fluxcord, tu es toujours la ?", asked.get(1));
        assertFalse(historyGiven.get(1).isEmpty(), "the new session is told what it missed");
    }

}
