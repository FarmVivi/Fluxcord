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
        String haystack = " " + normalise(spoken) + " ";
        for (String name : wanted) {
            if (haystack.contains(" " + name + " ")) {
                return true;
            }
        }
        return false;
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
