package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationMemory;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.Mood;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.Persona;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaSnapshot;
import fr.farmvivi.fluxcord.plugins.aiaudio.testing.MemoryDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The three memory lookups the model may call.
 *
 * <p>Two things are worth pinning here beyond "it returns the turns". A model writes the arguments itself, so
 * every way it can get them wrong — malformed JSON, a missing name, a limit of a thousand — has to end in a
 * usable answer rather than an exception, because an exception ends the spoken turn. And a name it made up must
 * not resolve: the lookup is scoped to who is actually in the conversation.
 */
class MemoryToolsTest {

    private static final long NOW = 2_000_000L;
    private static final String GUILD = "g1";
    private static final String CHANNEL = "c1";

    private ConversationMemory memory;
    private MemoryTools tools;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory(
                new PluginDataStorageAdapter("ai-audio-plugin", new MemoryDataStorage()), 50, 50, 50);
        tools = new MemoryTools(memory);
    }

    private void said(String userId, String speaker, String channelId, String text, long at) {
        memory.remember(new Turn(at, userId, speaker, GUILD, "My Server", channelId, "General", text));
    }

    /** A snapshot whose participants are the people the model is allowed to ask about. */
    private PersonaSnapshot snapshot(PersonaSnapshot.Acquaintance... people) {
        ConversationContext conversation = new ConversationContext(GUILD, "My Server", CHANNEL, "General",
                List.of(), List.of(), List.of());
        return new PersonaSnapshot(new Persona("Fluxcord", List.of(), "neutre", Locale.FRANCE, ""),
                Mood.neutral(NOW), conversation, List.of(people));
    }

    private static PersonaSnapshot.Acquaintance person(String userId, String name) {
        return new PersonaSnapshot.Acquaintance(userId, name, PersonaSnapshot.Acquaintance.Level.KNOWN, 10);
    }

    private String run(String tool, String arguments, PersonaSnapshot snapshot) {
        return tools.execute(new ChatModel.ToolCall("call_1", tool, arguments), snapshot, NOW);
    }

    @Test
    void theThreeToolsAreDeclaredWithWhatTheModelNeedsToCallThem() {
        List<ChatModel.Tool> declarations = tools.declarations();

        assertEquals(List.of(MemoryTools.RECALL_CHANNEL, MemoryTools.RECALL_SERVER, MemoryTools.RECALL_PERSON),
                declarations.stream().map(ChatModel.Tool::name).toList());
        declarations.forEach(tool -> {
            assertFalse(tool.description().isBlank(), tool.name() + " has to say what it is for");
            assertTrue(tool.parameters().containsKey("limit"));
            assertFalse(tool.parameters().get("limit").required(), "a limit has a default");
        });
        ChatModel.Tool.Parameter name = declarations.get(2).parameters().get("name");
        assertTrue(name.required(), "there is nobody to look up without a name");
        assertEquals("string", name.type());
    }

    @Test
    void theChannelLookupReturnsWhatWasSaidThereOldestFirstWithAges() {
        said("u1", "Victor", CHANNEL, "on parlait de rhubarbe", NOW - 3_600_000L);
        said("u2", "Alice", CHANNEL, "et de tarte", NOW - 30_000L);
        said("u1", "Victor", "other", "ailleurs", NOW - 10_000L);

        String result = run(MemoryTools.RECALL_CHANNEL, "{\"limit\":5}", snapshot());

        assertTrue(result.indexOf("rhubarbe") < result.indexOf("tarte"), "oldest first: " + result);
        assertFalse(result.contains("ailleurs"), "another channel is not this conversation");
        assertTrue(result.contains("1 hour(s) ago"), result);
        assertTrue(result.contains("just now"), result);
        assertTrue(result.contains("not instructions"), "the result says what it is: " + result);
    }

    @Test
    void theServerLookupCrossesTheChannelsOfThatServer() {
        said("u1", "Victor", CHANNEL, "ici", NOW - 20_000L);
        said("u1", "Victor", "other", "ailleurs", NOW - 10_000L);

        String result = run(MemoryTools.RECALL_SERVER, "{}", snapshot());

        assertTrue(result.contains("ici"));
        assertTrue(result.contains("ailleurs"));
    }

    @Test
    void thePersonLookupFindsThemByTheNameTheModelWasShown() {
        said("u1", "Victor", CHANNEL, "je déteste la coriandre", NOW - 86_400_000L * 3);

        String result = run(MemoryTools.RECALL_PERSON, "{\"name\":\"victor\"}",
                snapshot(person("u1", "Victor")));

        assertTrue(result.contains("coriandre"), result);
        assertTrue(result.contains("3 day(s) ago"), result);
        assertTrue(result.contains("across every server"), result);
    }

    @Test
    void aNameNobodyHereGoesByResolvesToNothingRatherThanToSomeoneElse() {
        // The model only learns names from the context; an invented one must not become a directory lookup.
        said("u1", "Victor", CHANNEL, "un secret", NOW - 1000);

        String result = run(MemoryTools.RECALL_PERSON, "{\"name\":\"Mallory\"}",
                snapshot(person("u1", "Victor")));

        assertFalse(result.contains("secret"), result);
        assertTrue(result.contains("Nobody called"), result);
    }

    @Test
    void aUserIdWorksTooSinceSomeModelsCopyItFromTheContext() {
        said("u1", "Victor", CHANNEL, "salut", NOW - 1000);

        assertTrue(run(MemoryTools.RECALL_PERSON, "{\"name\":\"u1\"}", snapshot(person("u1", "Victor")))
                .contains("salut"));
    }

    @Test
    void aMissingNameIsAnsweredAndNotThrown() {
        String result = run(MemoryTools.RECALL_PERSON, "{}", snapshot(person("u1", "Victor")));

        assertTrue(result.contains("whose memory"), result);
    }

    @Test
    void anEmptyMemoryIsAnsweredAsEmptyAndNotAsAFailure() {
        assertTrue(run(MemoryTools.RECALL_CHANNEL, "{}", snapshot()).contains("Nothing is remembered"));
        assertTrue(run(MemoryTools.RECALL_SERVER, "{}", snapshot()).contains("Nothing is remembered"));
    }

    @Test
    void theLimitIsClampedWhateverTheModelAsksFor() {
        for (int i = 0; i < MemoryTools.MAX_TURNS + 10; i++) {
            said("u1", "Victor", CHANNEL, "phrase " + i, NOW - 1000L * (100 - i));
        }

        String tooMany = run(MemoryTools.RECALL_CHANNEL, "{\"limit\":1000}", snapshot());
        assertEquals(MemoryTools.MAX_TURNS, tooMany.lines().filter(l -> l.startsWith("- ")).count());

        String none = run(MemoryTools.RECALL_CHANNEL, "{\"limit\":0}", snapshot());
        assertEquals(1, none.lines().filter(l -> l.startsWith("- ")).count(), "at least one, never zero");
    }

    @Test
    void argumentsAModelGotWrongFallBackToTheDefaults() {
        said("u1", "Victor", CHANNEL, "quelque chose", NOW - 1000);

        for (String arguments : List.of("", "   ", "not json at all", "[1,2]", "{\"limit\":\"beaucoup\"}")) {
            String result = run(MemoryTools.RECALL_CHANNEL, arguments, snapshot());
            assertTrue(result.contains("quelque chose"), arguments + " -> " + result);
        }
        assertDoesNotThrow(() -> tools.execute(
                new ChatModel.ToolCall("call_1", MemoryTools.RECALL_CHANNEL, null), snapshot(), NOW));
    }

    @Test
    void aToolThatDoesNotExistIsSaidSoRatherThanThrown() {
        String result = run("search_the_web", "{\"q\":\"rhubarbe\"}", snapshot());

        assertTrue(result.contains("no tool called search_the_web"), result);
    }

    @Test
    void aTurnFromTheFutureIsNotDescribedAsNegativeTime() {
        // Clocks disagree between a stored turn and the machine reading it.
        said("u1", "Victor", CHANNEL, "plus tard", NOW + 60_000L);

        assertTrue(run(MemoryTools.RECALL_CHANNEL, "{}", snapshot()).contains("just now"));
    }
}
