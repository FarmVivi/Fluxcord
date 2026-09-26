package fr.farmvivi.fluxcord.plugins.aiaudio.memory;

import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.plugins.aiaudio.testing.MemoryDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The three histories, on a real {@code PluginDataStorageAdapter} over an in-memory backend — the scopes
 * and key prefixes are the actual ones, since getting those wrong is what makes stored data unreachable.
 */
class ConversationMemoryTest {

    private static final String GUILD = "g1";
    private static final String CHANNEL = "c1";
    private static final String USER = "u1";

    private MemoryDataStorage backend;
    private PluginDataStorageAdapter storage;

    @BeforeEach
    void setUp() {
        backend = new MemoryDataStorage();
        storage = new PluginDataStorageAdapter("ai-audio-plugin", backend);
    }

    private ConversationMemory memory(int channelTurns, int serverTurns, int userTurns) {
        return new ConversationMemory(storage, channelTurns, serverTurns, userTurns);
    }

    private Turn turn(long at, String userId, String speaker, String text) {
        return new Turn(at, userId, speaker, GUILD, "My Server", CHANNEL, "General", text);
    }

    @Test
    void aTurnIsRecordedInTheThreeHistoriesAtOnce() {
        memory(10, 10, 10).remember(turn(1000, USER, "Victor", "salut"));

        ConversationMemory memory = memory(10, 10, 10);
        assertEquals(List.of("salut"), memory.channelHistory(GUILD, CHANNEL, 10).stream().map(Turn::text).toList());
        assertEquals(List.of("salut"), memory.serverHistory(GUILD, 10).stream().map(Turn::text).toList());
        assertEquals(List.of("salut"), memory.personHistory(USER, 10).stream().map(Turn::text).toList());
    }

    @Test
    void aPersonIsRememberedAcrossServers() {
        // The point of the user scope: what somebody said elsewhere, with where they said it.
        ConversationMemory memory = memory(0, 0, 10);
        memory.remember(new Turn(1000, USER, "Victor", "g1", "First", "c1", "General", "here"));
        memory.remember(new Turn(2000, USER, "Vic", "g2", "Second", "c9", "Lounge", "and there"));

        List<Turn> history = memory.personHistory(USER, 10);

        assertEquals(List.of("here", "and there"), history.stream().map(Turn::text).toList());
        assertEquals(List.of("First", "Second"), history.stream().map(Turn::guildName).toList());
        assertEquals("Lounge", history.get(1).channelName());
        assertEquals("Vic", history.get(1).speaker(), "the name they had on that server");
    }

    @Test
    void twoChannelsOfTheSameServerDoNotSeeEachOther() {
        ConversationMemory memory = memory(10, 10, 0);
        memory.remember(new Turn(1000, USER, "Victor", GUILD, "S", "c1", "General", "in general"));
        memory.remember(new Turn(2000, USER, "Victor", GUILD, "S", "c2", "Music", "in music"));

        assertEquals(List.of("in general"),
                memory.channelHistory(GUILD, "c1", 10).stream().map(Turn::text).toList());
        assertEquals(List.of("in general", "in music"),
                memory.serverHistory(GUILD, 10).stream().map(Turn::text).toList(),
                "the server history spans its channels");
    }

    @Test
    void turnsComeBackOldestFirstWhateverOrderTheyWereWrittenIn() {
        ConversationMemory memory = memory(10, 0, 0);
        memory.remember(turn(3000, USER, "Victor", "third"));
        memory.remember(turn(1000, "u2", "Alice", "first"));
        memory.remember(turn(2000, USER, "Victor", "second"));

        assertEquals(List.of("first", "second", "third"),
                memory.channelHistory(GUILD, CHANNEL, 10).stream().map(Turn::text).toList());
    }

    @Test
    void onlyTheMostRecentTurnsAreKept() {
        ConversationMemory memory = memory(3, 0, 0);
        for (int i = 1; i <= 6; i++) {
            memory.remember(turn(1000L * i, USER, "Victor", "line " + i));
        }

        assertEquals(List.of("line 4", "line 5", "line 6"),
                memory.channelHistory(GUILD, CHANNEL, 10).stream().map(Turn::text).toList(),
                "the oldest are deleted, not merely hidden");
    }

    @Test
    void readingFewerTurnsThanAreStoredReturnsTheLatestOnes() {
        ConversationMemory memory = memory(10, 0, 0);
        for (int i = 1; i <= 5; i++) {
            memory.remember(turn(1000L * i, USER, "Victor", "line " + i));
        }

        assertEquals(List.of("line 4", "line 5"),
                memory.channelHistory(GUILD, CHANNEL, 2).stream().map(Turn::text).toList());
    }

    @Test
    void twoPeopleTalkingInTheSameMillisecondBothSurvive() {
        // The key is timestamp plus speaker; the timestamp alone would lose one of them.
        ConversationMemory memory = memory(10, 0, 0);
        memory.remember(turn(1000, "u1", "Victor", "mine"));
        memory.remember(turn(1000, "u2", "Alice", "mine too"));

        assertEquals(2, memory.channelHistory(GUILD, CHANNEL, 10).size());
    }

    @Test
    void aHistorySetToZeroIsNotWrittenAtAll() {
        // Turning memory off must leave no trace, not merely hide it on read.
        memory(0, 0, 0).remember(turn(1000, USER, "Victor", "nothing kept"));

        assertTrue(memory(10, 10, 10).channelHistory(GUILD, CHANNEL, 10).isEmpty());
        assertTrue(memory(10, 10, 10).personHistory(USER, 10).isEmpty());
        assertTrue(backend.scope("guild:" + GUILD).isEmpty());
        assertTrue(backend.scope("user:" + USER).isEmpty());
        assertTrue(memory(0, 0, 0).isDisabled());
        assertFalse(memory(0, 0, 1).isDisabled());
    }

    @Test
    void blankSpeechIsNeverRecorded() {
        ConversationMemory memory = memory(10, 10, 10);

        memory.remember(turn(1000, USER, "Victor", "   "));
        memory.remember(turn(2000, USER, "Victor", null));

        assertTrue(memory.channelHistory(GUILD, CHANNEL, 10).isEmpty());
    }

    @Test
    void someoneCanBeForgottenWithoutErasingTheConversation() {
        ConversationMemory memory = memory(10, 10, 10);
        memory.remember(turn(1000, USER, "Victor", "said something"));

        assertTrue(memory.forgetPerson(USER));

        assertTrue(memory.personHistory(USER, 10).isEmpty());
        assertEquals(1, memory.channelHistory(GUILD, CHANNEL, 10).size(),
                "the channel's conversation belongs to the channel, not to one of its speakers");
    }

    @Test
    void aChannelOrAWholeServerCanBeCleared() {
        ConversationMemory memory = memory(10, 10, 10);
        memory.remember(turn(1000, USER, "Victor", "said something"));

        assertTrue(memory.forgetChannel(GUILD, CHANNEL));
        assertTrue(memory.channelHistory(GUILD, CHANNEL, 10).isEmpty());
        assertEquals(1, memory.serverHistory(GUILD, 10).size(), "the server history is separate");

        assertTrue(memory.forgetServer(GUILD));
        assertTrue(memory.serverHistory(GUILD, 10).isEmpty());
    }

    @Test
    void theKeysAreNamespacedByPluginScopeAndKind() {
        // Storage layout is a compatibility surface: stored data becomes unreachable if it changes.
        memory(10, 10, 10).remember(turn(1000, USER, "Victor", "salut"));

        assertEquals(List.of("ai-audio-plugin.conversation.c1.1000-u1", "ai-audio-plugin.server.1000-u1"),
                backend.scope("guild:" + GUILD).keySet().stream().sorted().toList());
        assertEquals(List.of("ai-audio-plugin.memory.1000-u1"),
                List.copyOf(backend.scope("user:" + USER).keySet()));
    }

    @Test
    void readingWithNoLimitAsksTheStorageForNothing() {
        assertTrue(memory(10, 10, 10).channelHistory(GUILD, CHANNEL, 0).isEmpty());
    }
}
