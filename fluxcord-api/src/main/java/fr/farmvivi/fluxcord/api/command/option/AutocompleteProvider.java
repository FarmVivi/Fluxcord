package fr.farmvivi.fluxcord.api.command.option;

import java.util.List;
import java.util.function.Function;

/**
 * Dynamic suggestions for a command option, computed while the user types a slash command. Discord shows at
 * most 25 choices; the core truncates longer lists. Called on a JDA thread with a ~3 s budget: keep it fast
 * (in-memory lookups), never block on network calls.
 *
 * @param <T> the option value type
 */
@FunctionalInterface
public interface AutocompleteProvider<T> {

    List<OptionChoice<T>> suggest(AutocompleteContext context);

    /** Adapts a provider that only looks at the typed text (the historical signature). */
    static <T> AutocompleteProvider<T> fromPartial(Function<String, List<OptionChoice<T>>> provider) {
        return context -> provider.apply(context.partial());
    }
}
