package fr.farmvivi.fluxcord.plugins.aiaudio.persona;

import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationMemory;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import fr.farmvivi.fluxcord.plugins.aiaudio.testing.MemoryDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The snapshot the voice-to-voice step will be handed: who the bot is here, how it feels, who is present and
 * how well it knows them.
 *
 * <p>The acquaintance level is the part that makes the bot adapt to people rather than to places, and it is
 * counted from each person's own <em>cross-server</em> history — so a regular from another server is not a
 * stranger here. That is the whole reason the memory keeps a per-person scope.
 */
class PersonaSnapshotTest {

    private static final String GUILD = "g1";
    private static final String CHANNEL = "c1";
    private static final long NOW = 9_000_000L;

    private ConversationMemory memory;
    private PersonaStore store;

    @BeforeEach
    void setUp() {
        PluginDataStorageAdapter storage =
                new PluginDataStorageAdapter("ai-audio-plugin", new MemoryDataStorage());
        memory = new ConversationMemory(storage, 50, 50, 100);
        store = new PersonaStore(storage, new Persona("Fluxcord", List.of("curieux"), "familier",
                Locale.FRANCE, ""));
    }

    /** A conversation in this channel with the given people present. */
    private ConversationContext conversation(ConversationContext.Participant... present) {
        return new ConversationContext(GUILD, "My Server", CHANNEL, "General", List.of(present),
                memory.channelHistory(GUILD, CHANNEL, 10), memory.serverHistory(GUILD, 10));
    }

    /** Records {@code count} turns said by {@code userId}, anywhere. */
    private void spoke(String userId, String guildId, int count) {
        for (int i = 0; i < count; i++) {
            memory.remember(new Turn(NOW - count + i, userId, "Someone", guildId, "S", CHANNEL, "General",
                    "line " + i));
        }
    }

    @Test
    void theSnapshotCarriesThePersonaTheMoodAndThePlace() {
        store.setGuildPersona(GUILD, new Persona("", List.of(), "formel", null, ""));
        store.nudgeMood(GUILD, CHANNEL, 0.5, 0, NOW);

        PersonaSnapshot snapshot = PersonaSnapshot.of(store, memory,
                conversation(new ConversationContext.Participant("u1", "Victor")), NOW);

        assertEquals("formel", snapshot.persona().tone(), "the server override applies");
        assertEquals("Fluxcord", snapshot.persona().name());
        assertEquals(0.5, snapshot.mood().energy(), 1e-9);
        assertEquals("General", snapshot.conversation().channelName());
        assertFalse(snapshot.isEmpty());
    }

    @Test
    void anEmptyChannelHasNobodyToTalkTo() {
        PersonaSnapshot snapshot = PersonaSnapshot.of(store, memory, conversation(), NOW);

        assertTrue(snapshot.isEmpty());
        assertEquals(List.of(), snapshot.familiarity());
    }

    @Test
    void someoneNeverHeardFromIsAStranger() {
        PersonaSnapshot snapshot = PersonaSnapshot.of(store, memory,
                conversation(new ConversationContext.Participant("u1", "Victor")), NOW);

        PersonaSnapshot.Acquaintance victor = snapshot.familiarity().get(0);
        assertEquals("u1", victor.userId());
        assertEquals("Victor", victor.displayName());
        assertEquals(PersonaSnapshot.Acquaintance.Level.STRANGER, victor.level());
        assertEquals(0, victor.rememberedTurns());
    }

    @Test
    void afterAFewTurnsSomeoneIsKnown() {
        spoke("u1", GUILD, PersonaSnapshot.KNOWN_FROM);

        PersonaSnapshot snapshot = PersonaSnapshot.of(store, memory,
                conversation(new ConversationContext.Participant("u1", "Victor")), NOW);

        assertEquals(PersonaSnapshot.Acquaintance.Level.KNOWN, snapshot.familiarity().get(0).level());
    }

    @Test
    void oneTurnShortOfTheThresholdIsStillAStranger() {
        spoke("u1", GUILD, PersonaSnapshot.KNOWN_FROM - 1);

        PersonaSnapshot snapshot = PersonaSnapshot.of(store, memory,
                conversation(new ConversationContext.Participant("u1", "Victor")), NOW);

        assertEquals(PersonaSnapshot.Acquaintance.Level.STRANGER, snapshot.familiarity().get(0).level());
    }

    @Test
    void aTalkativePersonBecomesARegular() {
        spoke("u1", GUILD, PersonaSnapshot.REGULAR_FROM);

        PersonaSnapshot snapshot = PersonaSnapshot.of(store, memory,
                conversation(new ConversationContext.Participant("u1", "Victor")), NOW);

        assertEquals(PersonaSnapshot.Acquaintance.Level.REGULAR, snapshot.familiarity().get(0).level());
    }

    @Test
    void aRegularFromAnotherServerIsNotAStrangerHere() {
        // The point of the per-person memory: familiarity follows the person, not the place.
        spoke("u1", "some-other-guild", PersonaSnapshot.REGULAR_FROM);

        PersonaSnapshot snapshot = PersonaSnapshot.of(store, memory,
                conversation(new ConversationContext.Participant("u1", "Victor")), NOW);

        assertEquals(PersonaSnapshot.Acquaintance.Level.REGULAR, snapshot.familiarity().get(0).level());
    }

    @Test
    void eachPersonIsJudgedOnTheirOwnHistory() {
        spoke("u1", GUILD, PersonaSnapshot.KNOWN_FROM);

        PersonaSnapshot snapshot = PersonaSnapshot.of(store, memory, conversation(
                new ConversationContext.Participant("u1", "Victor"),
                new ConversationContext.Participant("u2", "Alice")), NOW);

        assertEquals(PersonaSnapshot.Acquaintance.Level.KNOWN, snapshot.familiarity().get(0).level());
        assertEquals(PersonaSnapshot.Acquaintance.Level.STRANGER, snapshot.familiarity().get(1).level());
        assertEquals(List.of("Victor", "Alice"),
                snapshot.familiarity().stream().map(PersonaSnapshot.Acquaintance::displayName).toList());
    }

    @Test
    void theMoodInTheSnapshotHasAlreadyFaded() {
        store.nudgeMood(GUILD, CHANNEL, 0.8, 0, NOW);

        PersonaSnapshot later = PersonaSnapshot.of(store, memory,
                conversation(new ConversationContext.Participant("u1", "Victor")),
                NOW + Mood.HALF_LIFE.toMillis());

        assertEquals(0.4, later.mood().energy(), 1e-6);
    }
}
