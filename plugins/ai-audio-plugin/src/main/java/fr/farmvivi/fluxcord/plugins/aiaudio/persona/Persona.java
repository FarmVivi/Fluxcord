package fr.farmvivi.fluxcord.plugins.aiaudio.persona;

import java.util.List;
import java.util.Locale;

/**
 * Who the bot is: the part of its behaviour an operator chooses, as opposed to the mood, which the
 * conversation moves.
 *
 * <p>Deliberately a handful of named fields rather than one block of prose. A single free-text prompt cannot
 * be overridden per server or per channel without rewriting the whole thing, and it gives whoever composes
 * the final instructions no way to tell a trait from an instruction. {@code instructions} is there for what
 * does not fit, and it is the one field an operator can use to say anything.
 *
 * <p>Every field comes from the configuration or from an operator command — never from a conversation. That
 * is the boundary: a persona is trusted input, a transcription is not.
 *
 * @param name         what the bot calls itself
 * @param traits       a few adjectives, in the order they should be presented
 * @param tone         how it speaks (e.g. {@code familier, concis})
 * @param language     the language it answers in; null in an override means "unchanged"
 * @param instructions anything else the operator wants to add, possibly empty
 */
public record Persona(String name, List<String> traits, String tone, Locale language, String instructions) {

    /** Traits beyond this are dropped: a long list stops steering and starts diluting. */
    public static final int MAX_TRAITS = 8;
    /** Longest instruction text accepted, to keep one operator from filling the model's context. */
    public static final int MAX_INSTRUCTIONS = 500;

    /**
     * Normalises, and deliberately substitutes nothing.
     *
     * <p>A persona is used two ways: as the complete base read from the configuration, and as a
     * <em>partial override</em> for a server or a channel, where a blank field means "leave it as it is".
     * Defaulting a blank name here would make every override rename the bot. The defaults therefore live
     * where the base is built, not in the type.
     */
    public Persona {
        name = name == null ? "" : name.strip();
        tone = tone == null ? "" : tone.strip();
        instructions = instructions == null ? "" : truncate(instructions.strip(), MAX_INSTRUCTIONS);
        traits = traits == null ? List.of() : traits.stream()
                .filter(trait -> trait != null && !trait.isBlank())
                .map(String::strip)
                .distinct()
                .limit(MAX_TRAITS)
                .toList();
    }

    /** An override that states nothing: every field falls through to the level above. */
    public static Persona nothing() {
        return new Persona("", List.of(), "", null, "");
    }

    /**
     * Returns this persona with whatever the other one actually says.
     *
     * <p>This is how a server or a channel override works: it states only what differs, and everything else
     * falls through. A blank field or an empty trait list means "unchanged", which is why an override cannot
     * be used to <em>remove</em> traits — resetting is a separate action.
     *
     * @param override the narrower persona, or null
     * @return the merged persona
     */
    public Persona overriddenBy(Persona override) {
        if (override == null) {
            return this;
        }
        return new Persona(
                override.name.isBlank() ? name : override.name,
                override.traits.isEmpty() ? traits : override.traits,
                override.tone.isBlank() ? tone : override.tone,
                override.language == null ? language : override.language,
                override.instructions.isBlank() ? instructions : override.instructions);
    }

    /** @return the traits as a comma-separated list, or {@code "-"} when there are none */
    public String traitsAsText() {
        return traits.isEmpty() ? "-" : String.join(", ", traits);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
