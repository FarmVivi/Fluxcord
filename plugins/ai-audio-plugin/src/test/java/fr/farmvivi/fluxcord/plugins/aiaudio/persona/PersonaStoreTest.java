package fr.farmvivi.fluxcord.plugins.aiaudio.persona;

import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.plugins.aiaudio.testing.MemoryDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The three levels of persona and the per-channel moods, on a real storage adapter over an in-memory backend.
 *
 * <p>The storage layout is asserted directly in one test: it is a compatibility surface, and a persona an
 * operator set months ago has to still be found under the same key.
 */
class PersonaStoreTest {

    private static final String GUILD = "g1";
    private static final String CHANNEL = "c1";
    private static final long NOW = 5_000_000L;
    private static final Persona BASE = new Persona("Fluxcord", List.of("curieux"), "familier",
            Locale.FRANCE, "");

    private MemoryDataStorage backend;
    private PersonaStore store;

    @BeforeEach
    void setUp() {
        backend = new MemoryDataStorage();
        store = new PersonaStore(new PluginDataStorageAdapter("ai-audio-plugin", backend), BASE);
    }

    @Test
    void withoutAnyOverrideTheConfiguredPersonaApplies() {
        assertEquals(BASE, store.base());
        assertEquals(BASE, store.effective(GUILD, CHANNEL));
        assertTrue(store.guildOverride(GUILD).isEmpty());
        assertTrue(store.channelOverride(GUILD, CHANNEL).isEmpty());
    }

    @Test
    void outsideAGuildThereIsNothingToOverride() {
        assertEquals(BASE, store.effective(null, null));
    }

    @Test
    void aServerOverrideAppliesToEveryChannelOfThatServer() {
        store.setGuildPersona(GUILD, new Persona("", List.of(), "formel", null, ""));

        assertEquals("formel", store.effective(GUILD, CHANNEL).tone());
        assertEquals("formel", store.effective(GUILD, "another-channel").tone());
        assertEquals("Fluxcord", store.effective(GUILD, CHANNEL).name(), "the rest falls through");
    }

    @Test
    void aChannelOverrideWinsOverTheServersAndOnlyThere() {
        store.setGuildPersona(GUILD, new Persona("", List.of("sérieux"), "formel", null, ""));
        store.setChannelPersona(GUILD, CHANNEL, new Persona("", List.of(), "taquin", null, ""));

        assertEquals("taquin", store.effective(GUILD, CHANNEL).tone());
        assertEquals(List.of("sérieux"), store.effective(GUILD, CHANNEL).traits(), "from the server");
        assertEquals("formel", store.effective(GUILD, "other").tone(), "another channel keeps the server's");
    }

    @Test
    void anotherServerIsUnaffected() {
        store.setGuildPersona(GUILD, new Persona("Autre", List.of(), "", null, ""));

        assertEquals("Fluxcord", store.effective("g2", CHANNEL).name());
    }

    @Test
    void everyFieldSurvivesTheRoundTripThroughStorage() {
        // The stored shape is strings only, so this is what proves the conversion both ways.
        Persona override = new Persona("Autre", List.of("sérieux", "calme"), "formel", Locale.US,
                "Réponds en une phrase.");

        store.setGuildPersona(GUILD, override);

        Persona read = store.guildOverride(GUILD).orElseThrow();
        assertEquals("Autre", read.name());
        assertEquals(List.of("sérieux", "calme"), read.traits());
        assertEquals("formel", read.tone());
        assertEquals(Locale.US, read.language());
        assertEquals("Réponds en une phrase.", read.instructions());
    }

    @Test
    void anOverrideWithoutALanguageComesBackWithoutOne() {
        // If a missing language came back as a default, every override would force that language.
        store.setGuildPersona(GUILD, new Persona("", List.of(), "sec", null, ""));

        assertNull(store.guildOverride(GUILD).orElseThrow().language());
        assertEquals(Locale.FRANCE, store.effective(GUILD, null).language(), "so the base's applies");
    }

    @Test
    void resettingRemovesOnlyItsOwnLevel() {
        store.setGuildPersona(GUILD, new Persona("", List.of(), "formel", null, ""));
        store.setChannelPersona(GUILD, CHANNEL, new Persona("", List.of(), "taquin", null, ""));

        assertTrue(store.resetChannel(GUILD, CHANNEL));

        assertEquals("formel", store.effective(GUILD, CHANNEL).tone(), "the server's is still there");
        assertTrue(store.resetGuild(GUILD));
        assertEquals(BASE, store.effective(GUILD, CHANNEL));
        assertFalse(store.resetGuild(GUILD), "nothing left to reset");
    }

    @Test
    void aChannelStartsNeutralAndRemembersWhatItWasNudgedTo() {
        assertTrue(store.mood(GUILD, CHANNEL, NOW).isNeutral());

        store.nudgeMood(GUILD, CHANNEL, 0.4, 0.2, NOW);

        Mood mood = store.mood(GUILD, CHANNEL, NOW);
        assertEquals(0.4, mood.energy(), 1e-9);
        assertEquals(0.2, mood.warmth(), 1e-9);
    }

    @Test
    void aMoodReadLaterHasFaded() {
        store.nudgeMood(GUILD, CHANNEL, 0.8, 0, NOW);

        Mood later = store.mood(GUILD, CHANNEL, NOW + Mood.HALF_LIFE.toMillis());

        assertEquals(0.4, later.energy(), 1e-6);
    }

    @Test
    void oneChannelsMoodDoesNotColourAnother() {
        // A mood belongs to a conversation, not to a community.
        store.nudgeMood(GUILD, CHANNEL, 0.9, 0, NOW);

        assertTrue(store.mood(GUILD, "other", NOW).isNeutral());
    }

    @Test
    void nudgesAccumulateAcrossCalls() {
        store.nudgeMood(GUILD, CHANNEL, 0.2, 0, NOW);
        store.nudgeMood(GUILD, CHANNEL, 0.2, 0, NOW);

        assertEquals(0.4, store.mood(GUILD, CHANNEL, NOW).energy(), 1e-9);
    }

    @Test
    void aMoodCanBeCleared() {
        store.nudgeMood(GUILD, CHANNEL, 0.9, 0, NOW);

        assertTrue(store.resetMood(GUILD, CHANNEL));

        assertTrue(store.mood(GUILD, CHANNEL, NOW).isNeutral());
        assertFalse(store.resetMood(GUILD, CHANNEL));
    }

    @Test
    void thereIsNoMoodWithoutAPlaceToAttachItTo() {
        assertTrue(store.mood(null, CHANNEL, NOW).isNeutral());
        assertTrue(store.mood(GUILD, null, NOW).isNeutral());
        assertTrue(store.nudgeMood(null, null, 0.5, 0.5, NOW).isNeutral());
    }

    @Test
    void theStorageLayoutIsWhatAnOlderInstallWouldHaveWritten() {
        store.setGuildPersona(GUILD, new Persona("", List.of(), "formel", null, ""));
        store.setChannelPersona(GUILD, CHANNEL, new Persona("", List.of(), "taquin", null, ""));
        store.nudgeMood(GUILD, CHANNEL, 0.3, 0, NOW);

        assertEquals(List.of(
                        "ai-audio-plugin.mood.c1",
                        "ai-audio-plugin.persona",
                        "ai-audio-plugin.persona.c1"),
                backend.scope("guild:" + GUILD).keySet().stream().sorted().toList());
    }
}
