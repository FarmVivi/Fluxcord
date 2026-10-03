package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Was the bot being spoken to? The wake word, which in a busy channel is the whole difference between a bot
 * and a nuisance.
 *
 * <p>The cases below are the ones that decide whether this works in practice: a transcript writes the name
 * with a comma after it, says it in capitals, spells it with whatever accent the model felt like, and
 * occasionally contains a word that merely ends in the name.
 */
class AddressTest {

    private static final List<String> NAMES = List.of("Fluxcord", "Tardis");

    @Test
    void theNameOnItsOwnIsEnough() {
        assertTrue(Address.addressed("Fluxcord", NAMES));
    }

    @Test
    void theNameInASentenceCountsWhereverItIs() {
        assertTrue(Address.addressed("Fluxcord, tu peux mettre de la musique ?", NAMES));
        assertTrue(Address.addressed("dis-moi Fluxcord, il est quelle heure", NAMES));
        assertTrue(Address.addressed("je vais demander a Fluxcord", NAMES));
    }

    @Test
    void punctuationAroundTheNameIsNotPartOfIt() {
        // A transcript writes "Fluxcord," far more often than "Fluxcord".
        assertTrue(Address.addressed("Fluxcord, viens ici.", NAMES));
        assertTrue(Address.addressed("(Fluxcord) tu m'entends ?", NAMES));
        assertTrue(Address.addressed("eh... Fluxcord !", NAMES));
    }

    @Test
    void caseAndAccentsAreWhateverTheSpeechModelFeltLike() {
        assertTrue(Address.addressed("FLUXCORD tu dors ?", NAMES));
        assertTrue(Address.addressed("fluxcord tu dors ?", NAMES));
        assertTrue(Address.addressed("Fluxcôrd tu dors ?", NAMES));
        assertTrue(Address.addressed("TARDIS", NAMES));
    }

    @Test
    void anyOfTheNamesWakesIt() {
        // The persona has one name and the server may have renamed the bot to another; people say either.
        assertTrue(Address.addressed("Tardis, joue quelque chose", NAMES));
    }

    @Test
    void aWordThatMerelyContainsTheNameDoesNot() {
        // The failure this guards against: a conversation about charging cables waking the bot every
        // sentence, because "cord" is inside "Fluxcord".
        assertFalse(Address.addressed("passe-moi le cord", List.of("Fluxcord")));
        assertFalse(Address.addressed("c'est un superfluxcord", List.of("Fluxcord")));
        assertFalse(Address.addressed("fluxcords", List.of("Fluxcord")), "a plural is a different word");
    }

    @Test
    void aSentenceAboutSomethingElseIsLeftAlone() {
        assertFalse(Address.addressed("il fait beau aujourd'hui, on sort ce week-end ?", NAMES));
        assertFalse(Address.addressed("", NAMES));
        assertFalse(Address.addressed(null, NAMES));
    }

    @Test
    void withNoNameToListenForEverythingCounts() {
        // Which is right for a channel with one person in it: making them say a name every sentence is
        // worse than answering too often.
        assertTrue(Address.addressed("il fait beau", List.of()));
        assertTrue(Address.addressed("il fait beau", null));
        assertTrue(Address.addressed("", List.of()));
    }

    @Test
    void aNameTooShortToBeSaidByAccidentIsDropped() {
        // A bot called "A" would be woken by half of everything said in French, and the symptom would look
        // like the gate not working at all rather than like a bad name.
        assertEquals(List.of(), List.copyOf(Address.usable(List.of("A", "Ok"))));
        assertTrue(Address.addressed("il fait beau", List.of("A", "Ok")),
                "and with nothing usable left, it is back to answering everything");
    }

    @Test
    void blanksAndDuplicatesAreNotNames() {
        assertEquals(List.of("fluxcord"),
                List.copyOf(Address.usable(Arrays.asList("Fluxcord", null, "  ", "FLUXCORD", "fluxcôrd"))));
    }

    @Test
    void aNameOfSeveralWordsIsMatchedAsThoseWords() {
        assertTrue(Address.addressed("salut Fluxcord Bot, ca va ?", List.of("Fluxcord Bot")));
        assertFalse(Address.addressed("salut Fluxcord, ca va ?", List.of("Fluxcord Bot")));
    }

    /**
     * MEASURED, 2026-10-03, from a real evening of conversation. A bot nicknamed "Poubelle" was written
     * down by the transcriber as "pour belle", and the question that came with it was dropped without a
     * word anywhere — so the person simply said the whole sentence again. These are the sentences as they
     * were actually transcribed.
     */
    @Test
    void aNameTheTranscriberSplitInTwoStillWakesIt() {
        List<String> names = List.of("Poubelle", "Fluxcord");

        assertTrue(Address.addressed("OK pour belle tu et de retour normalement", names),
                "this exact sentence was lost, and the question in it with it");
        assertTrue(Address.addressed("Poubelle, je suis de retour.", names));
        assertTrue(Address.addressed("Ok poubelle est-ce que tu es toujours la", names));
    }

    /**
     * The other half of the same rule, and the one that decides whether it is worth having: French is full
     * of words that are nearly a short name, and a bot that answers them is worse than one that misses a
     * question.
     */
    @Test
    void ordinaryFrenchDoesNotWakeIt() {
        List<String> names = List.of("Poubelle", "Fluxcord");

        assertFalse(Address.addressed("quelle belle journee pour sortir", names));
        assertFalse(Address.addressed("c'est une pou de belle taille", names));
        assertFalse(Address.addressed("il fait beau aujourd'hui a Paris", names));
        assertFalse(Address.addressed("on part a quelle heure demain", names));
    }

    /**
     * Honest about the limit. "Quelle belle" for "Poubelle" is a different opening sound, not a spelling
     * slip, and no budget that catches it leaves the rule above standing. The answer to that one is a name
     * that sounds like nothing else, which is a choice for whoever names the bot.
     */
    @Test
    void aNameHeardAsADifferentWordIsStillMissed() {
        assertFalse(Address.addressed("Quelle belle, quel temps fait-il aujourd'hui a Paris.",
                List.of("Poubelle")));
    }
}
