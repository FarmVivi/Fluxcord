package fr.farmvivi.fluxcord.plugins.aiaudio.persona;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The persona and, above all, how an override merges into it.
 *
 * <p>A persona is used two ways: complete, as read from the configuration, and partial, as a per-server or
 * per-channel override where a blank field means "leave it alone". That double role is why the type itself
 * substitutes no defaults — doing so would make every override silently rename the bot.
 */
class PersonaTest {

    private static final Persona BASE = new Persona("Fluxcord", List.of("curieux", "taquin"),
            "familier", Locale.FRANCE, "Reste bref.");

    @Test
    void aPersonaKeepsWhatItWasGiven() {
        assertEquals("Fluxcord", BASE.name());
        assertEquals(List.of("curieux", "taquin"), BASE.traits());
        assertEquals("familier", BASE.tone());
        assertEquals(Locale.FRANCE, BASE.language());
        assertEquals("Reste bref.", BASE.instructions());
        assertEquals("curieux, taquin", BASE.traitsAsText());
    }

    @Test
    void nothingIsSubstitutedForABlankField() {
        // The whole override mechanism depends on this: blank has to stay blank.
        Persona blank = Persona.nothing();

        assertEquals("", blank.name());
        assertEquals("", blank.tone());
        assertNull(blank.language());
        assertEquals("", blank.instructions());
        assertEquals(List.of(), blank.traits());
        assertEquals("-", blank.traitsAsText());
    }

    @Test
    void whitespaceIsTrimmedAndEmptyTraitsAreDropped() {
        Persona messy = new Persona("  Flux  ", List.of(" curieux ", "", "  ", "taquin"),
                "  sec ", Locale.FRANCE, "   garde le rythme   ");

        assertEquals("Flux", messy.name());
        assertEquals(List.of("curieux", "taquin"), messy.traits());
        assertEquals("sec", messy.tone());
        assertEquals("garde le rythme", messy.instructions());
    }

    @Test
    void aTraitIsNotRepeated() {
        Persona repeated = new Persona("a", List.of("curieux", "curieux", "taquin"), "t", Locale.FRANCE, "");

        assertEquals(List.of("curieux", "taquin"), repeated.traits());
    }

    @Test
    void tooManyTraitsAreCutRatherThanDiluting() {
        List<String> tooMany = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");

        Persona limited = new Persona("n", tooMany, "t", Locale.FRANCE, "");

        assertEquals(Persona.MAX_TRAITS, limited.traits().size());
        assertEquals(List.of("a", "b", "c", "d", "e", "f", "g", "h"), limited.traits());
    }

    @Test
    void aVeryLongInstructionIsTruncatedSoOneOperatorCannotFillTheContext() {
        Persona verbose = new Persona("n", List.of(), "t", Locale.FRANCE, "x".repeat(2000));

        assertEquals(Persona.MAX_INSTRUCTIONS, verbose.instructions().length());
    }

    @Test
    void nullsAreAcceptedAsAbsentRatherThanFailing() {
        // A stored override read back with missing fields must not blow up the whole persona resolution.
        Persona sparse = new Persona(null, null, null, null, null);

        assertEquals("", sparse.name());
        assertEquals(List.of(), sparse.traits());
        assertEquals("", sparse.tone());
        assertEquals("", sparse.instructions());
    }

    @Test
    void anOverrideThatSaysNothingChangesNothing() {
        assertEquals(BASE, BASE.overriddenBy(Persona.nothing()));
        assertEquals(BASE, BASE.overriddenBy(null));
    }

    @Test
    void anOverrideReplacesOnlyTheFieldsItStates() {
        Persona override = new Persona("", List.of(), "sec", null, "");

        Persona merged = BASE.overriddenBy(override);

        assertEquals("sec", merged.tone(), "the one stated field");
        assertEquals("Fluxcord", merged.name());
        assertEquals(List.of("curieux", "taquin"), merged.traits());
        assertEquals(Locale.FRANCE, merged.language());
        assertEquals("Reste bref.", merged.instructions());
    }

    @Test
    void anOverrideCanReplaceEveryField() {
        Persona override = new Persona("Autre", List.of("sérieux"), "formel", Locale.US, "Sois précis.");

        Persona merged = BASE.overriddenBy(override);

        assertEquals(override, merged);
    }

    @Test
    void overridesStackFromTheWidestToTheNarrowest() {
        // This is what resolving base -> server -> channel does.
        Persona server = new Persona("", List.of("sérieux"), "formel", null, "");
        Persona channel = new Persona("", List.of(), "taquin", null, "");

        Persona effective = BASE.overriddenBy(server).overriddenBy(channel);

        assertEquals("taquin", effective.tone(), "the channel wins over the server");
        assertEquals(List.of("sérieux"), effective.traits(), "which the server had set");
        assertEquals("Fluxcord", effective.name(), "and the base still names it");
    }

    @Test
    void anOverrideCannotRemoveTraits() {
        // Documented on purpose: an empty list means "unchanged", so emptying is a reset, not an override.
        Persona emptying = new Persona("", List.of(), "", null, "");

        assertEquals(List.of("curieux", "taquin"), BASE.overriddenBy(emptying).traits());
    }

    @Test
    void twoPersonasWithTheSameContentAreEqual() {
        assertEquals(BASE, new Persona("Fluxcord", List.of("curieux", "taquin"), "familier",
                Locale.FRANCE, "Reste bref."));
        assertNotEquals(BASE, new Persona("Fluxcord", List.of("curieux"), "familier",
                Locale.FRANCE, "Reste bref."));
    }
}
