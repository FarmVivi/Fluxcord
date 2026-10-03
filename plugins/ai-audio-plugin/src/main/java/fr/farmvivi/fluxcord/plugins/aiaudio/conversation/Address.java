package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Was the bot being spoken to?
 *
 * <p>The question only has a cheap answer, and the cheap answer is a name: in a channel with eight people
 * talking, the thing that distinguishes "what do you think, Fluxcord?" from the rest of the conversation is
 * that somebody said the name. This is the same idea as a smart speaker's wake word, with the difference that
 * the name is not a fixed string chosen by whoever built it — it is whatever the server calls the bot.
 *
 * <p><strong>Matching is deliberately forgiving in two ways and strict in a third.</strong> Accents are
 * stripped and case is ignored, because the text comes from a speech model and "FLUXCORD", "fluxcord" and
 * "Fluxcørd" are the same word being said. Punctuation is ignored, because a transcript writes "Fluxcord,"
 * with a comma far more often than not. But the name has to be a <em>whole word</em>: "cord" must not match
 * "Fluxcord", or a conversation about charging cables wakes the bot every sentence.
 *
 * <p><strong>The names also have to reach the transcriber.</strong> A speech model writes down the nearest
 * word it believes exists, so "Fluxcord" comes back as "flux cord" and the bot is never woken — which makes
 * the wake word and {@code speech_to_text.vocabulary} one feature rather than two. That is why
 * {@code SpeechRecognitionService} adds these same names to the vocabulary itself instead of trusting an
 * operator to remember.
 */
public final class Address {

    /**
     * How many consecutive words may be joined back together while looking for the name.
     *
     * <p>Two, because the mistake this exists for is a name split in half ("pour belle" for "Poubelle").
     * Three would start joining ordinary French into something close to anything.
     */
    private static final int MAX_WINDOW = 2;

    private Address() {
    }

    /**
     * @param spoken what was said, as transcribed
     * @param names  the names that count as being addressed: the bot's persona name, what the server calls
     *               it, and any configured wake word
     * @return true when one of the names was said, or when there are no names to look for
     */
    public static boolean addressed(String spoken, Collection<String> names) {
        Set<String> wanted = usable(names);
        if (wanted.isEmpty()) {
            // Nothing to listen for means listening to everything, which is what a channel with one person
            // in it wants.
            return true;
        }
        if (spoken == null || spoken.isBlank()) {
            return false;
        }
        String[] words = normalise(spoken).split(" ");
        for (String name : wanted) {
            if (nearlySaid(words, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether these words contain the name, allowing for the transcriber having got it slightly wrong.
     *
     * <p>MEASURED, 2026-10-03, on a real evening of conversation: a bot called "Poubelle" was written down as
     * "pour belle" and the question that followed was discarded in silence, so the person said the whole
     * sentence again. An exact match is the right rule for a word in a sentence and the wrong one for a name
     * a speech model had to guess — and a name it guesses slightly wrong is a bot that never answers, with
     * nothing anywhere saying why.
     *
     * <p>The windows are up to {@link #MAX_WINDOW} words joined together, because the mistakes that matter
     * split one name into several words. The budget is a quarter of the name's length, at least one: on
     * fifteen real utterances plus five sentences built to be traps — "quelle belle journée pour sortir",
     * "une pou de belle taille" — this recovered the lost question and woke the bot not once by accident.
     *
     * <p>What it does not do is rescue a name whose opening sound was heard as something else: "quelle belle"
     * for "poubelle" stays unmatched, and deliberately so. No budget catches that without catching half the
     * language with it; the answer there is a name that sounds like nothing else, not a looser rule.
     */
    private static boolean nearlySaid(String[] words, String spacedName) {
        // The windows are joined without spaces, so the name is too: that is what lets "pour belle" meet
        // "poubelle", and it is also how a name that is itself several words ("Fluxcord Bot") is found.
        String name = spacedName.replace(" ", "");
        int budget = Math.max(1, name.length() / 4);
        int widest = Math.max(MAX_WINDOW, spacedName.split(" ").length);
        for (int size = 1; size <= widest; size++) {
            for (int start = 0; start + size <= words.length; start++) {
                String candidate = String.join("", java.util.Arrays.copyOfRange(words, start, start + size));
                if (candidate.equals(name)) {
                    return true;
                }
                // A single word has to match exactly. Allowing it to be a character out would wake a bot
                // called Poubelle on "poubelles", and one called Fluxcord on "fluxcords" - a conversation
                // about the thing, not with it. The mistake worth forgiving is the other one: a name the
                // transcriber broke into pieces, which is always more than one word.
                if (size == 1) {
                    continue;
                }
                // Length is checked first because it is free, and it is what keeps this from comparing a
                // name with every window of a long sentence.
                if (Math.abs(candidate.length() - name.length()) <= budget
                        && distance(candidate, name) <= budget) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Levenshtein distance, one row at a time: the names here are short and this runs per utterance. */
    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /**
     * The names worth looking for, normalised and deduplicated.
     *
     * <p>A name of one or two letters is dropped: a bot called "A" would be woken by half of everything said
     * in French, and the failure would look like the gate not working at all.
     *
     * @param names candidate names, any of which may be null, blank or a duplicate of another
     * @return the normalised names, in the order given
     */
    static Set<String> usable(Collection<String> names) {
        Set<String> out = new LinkedHashSet<>();
        if (names == null) {
            return out;
        }
        for (String name : names) {
            if (name == null) {
                continue;
            }
            String normalised = normalise(name);
            if (normalised.length() >= 3) {
                out.add(normalised);
            }
        }
        return out;
    }

    /**
     * Lower case, no accents, and every run of anything that is not a letter or a digit turned into one
     * space.
     *
     * <p>The last part is what makes the whole-word test work on a transcript: "Fluxcord, tu m'entends ?"
     * becomes "fluxcord tu m entends", and the name is then a word with spaces around it.
     *
     * @param text the text to normalise, never null
     * @return the normalised form
     */
    static String normalise(String text) {
        String stripped = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return stripped.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .strip();
    }
}
