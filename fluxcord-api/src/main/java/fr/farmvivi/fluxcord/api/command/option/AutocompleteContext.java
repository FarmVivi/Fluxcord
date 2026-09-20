package fr.farmvivi.fluxcord.api.command.option;

import java.util.Map;

/**
 * What an {@link AutocompleteProvider} knows when Discord asks for suggestions.
 *
 * @param partial the text the user has typed so far in the focused option (may be empty)
 * @param guildId the guild the command is typed in, or {@code null} in DMs
 * @param userId  the user typing the command
 * @param options the other options already filled in, by name, as their raw string values
 */
public record AutocompleteContext(String partial, String guildId, String userId, Map<String, String> options) {

    public AutocompleteContext {
        partial = partial == null ? "" : partial;
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /** The value the user already entered for another option, if any. */
    public String option(String name) {
        return options.get(name);
    }
}
