package fr.farmvivi.fluxcord.api.command;

import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import fr.farmvivi.fluxcord.api.command.option.AutocompleteProvider;
import fr.farmvivi.fluxcord.api.command.option.OptionChoice;
import fr.farmvivi.fluxcord.api.command.option.OptionType;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Builder for creating commands.
 * Provides a fluent API for command configuration.
 */
public interface CommandBuilder {

    /**
     * Creates a new command builder.
     *
     * @return a new command builder
     */
    static CommandBuilder create() {
        // This will be implemented in the implementation class
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Sets the name of the command.
     *
     * @param name the command name
     * @return this builder
     */
    CommandBuilder name(String name);

    /**
     * Sets the description of the command.
     *
     * @param description the command description
     * @return this builder
     */
    CommandBuilder description(String description);

    /**
     * Sets the category of the command.
     *
     * @param category the command category
     * @return this builder
     */
    CommandBuilder category(String category);

    /**
     * Sets the command group.
     * This is used for slash command grouping.
     *
     * @param group the command group
     * @return this builder
     */
    CommandBuilder group(String group);

    /**
     * Sets the permission required to execute the command.
     *
     * @param permission the permission string
     * @return this builder
     */
    CommandBuilder permission(String permission);

    /**
     * Sets the translation key prefix for this command.
     * If not set, the translation key will be derived from the command name.
     *
     * @param translationKey the translation key prefix
     * @return this builder
     */
    CommandBuilder translationKey(String translationKey);

    /**
     * Adds an alias for the command.
     *
     * @param alias the command alias
     * @return this builder
     */
    CommandBuilder alias(String alias);

    /**
     * Adds multiple aliases for the command.
     *
     * @param aliases the command aliases
     * @return this builder
     */
    CommandBuilder aliases(String... aliases);

    /**
     * Sets whether the command is guild-only.
     *
     * @param guildOnly true if the command can only be executed in a guild
     * @return this builder
     */
    CommandBuilder guildOnly(boolean guildOnly);

    /**
     * Specifies the guilds where this command is available.
     * If not set, the command is available in all guilds.
     *
     * @param guildIds the guild IDs
     * @return this builder
     */
    CommandBuilder guilds(String... guildIds);

    /**
     * Sets whether this command is enabled by default.
     *
     * @param enabled true if the command is enabled by default
     * @return this builder
     */
    CommandBuilder enabled(boolean enabled);

    /**
     * Sets the cooldown of the command in seconds.
     *
     * @param cooldown the cooldown in seconds
     * @return this builder
     */
    CommandBuilder cooldown(int cooldown);

    /**
     * Adds a string option to the command.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @return this builder
     */
    CommandBuilder stringOption(String name, String description, boolean required);

    /**
     * Adds a string option to the command with choices.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @param choices     the available choices
     * @return this builder
     */
    CommandBuilder stringOption(String name, String description, boolean required, OptionChoice<String>... choices);

    /**
     * Adds a string option to the command with validation.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @param validator   the validator for the option value
     * @return this builder
     */
    CommandBuilder stringOption(String name, String description, boolean required, Predicate<String> validator);

    /**
     * Adds a string option to the command with autocomplete (typed text only; see the
     * {@link AutocompleteProvider} overload for guild/user/other-options context).
     */
    default CommandBuilder stringOption(String name, String description, boolean required,
                                        Function<String, List<OptionChoice<String>>> autocompleteProvider) {
        return stringOption(name, description, required, AutocompleteProvider.fromPartial(autocompleteProvider));
    }

    /**
     * Adds a string option whose suggestions are computed while the user types.
     *
     * @param name                 the option name
     * @param description          the option description
     * @param required             true if the option is required
     * @param autocompleteProvider the suggestion provider (fast, in-memory)
     * @return this builder
     */
    CommandBuilder stringOption(String name, String description, boolean required,
                                AutocompleteProvider<String> autocompleteProvider);

    /**
     * Adds an integer option to the command.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @return this builder
     */
    CommandBuilder integerOption(String name, String description, boolean required);

    /**
     * Adds an integer option to the command with choices.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @param choices     the available choices
     * @return this builder
     */
    CommandBuilder integerOption(String name, String description, boolean required, OptionChoice<Integer>... choices);

    /**
     * Adds an integer option to the command with validation.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @param validator   the validator for the option value
     * @return this builder
     */
    CommandBuilder integerOption(String name, String description, boolean required, Predicate<Integer> validator);

    /**
     * Adds an integer option to the command with min and max values.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @param min         the minimum value (null for no min)
     * @param max         the maximum value (null for no max)
     * @return this builder
     */
    CommandBuilder integerOption(String name, String description, boolean required, Integer min, Integer max);

    /**
     * Adds an integer option with a range and dynamic suggestions (values must be within the range).
     */
    CommandBuilder integerOption(String name, String description, boolean required, Integer min, Integer max,
                                 AutocompleteProvider<Integer> autocompleteProvider);

    /**
     * Adds a boolean option to the command.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @return this builder
     */
    CommandBuilder booleanOption(String name, String description, boolean required);

    /**
     * Adds a user option to the command.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @return this builder
     */
    CommandBuilder userOption(String name, String description, boolean required);

    /**
     * Adds a channel option to the command.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @return this builder
     */
    CommandBuilder channelOption(String name, String description, boolean required);

    /**
     * Adds a role option to the command.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @return this builder
     */
    CommandBuilder roleOption(String name, String description, boolean required);

    /**
     * Adds a mentionable option to the command.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @return this builder
     */
    CommandBuilder mentionableOption(String name, String description, boolean required);

    /**
     * Adds a number option to the command.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @return this builder
     */
    CommandBuilder numberOption(String name, String description, boolean required);

    /**
     * Adds a number option to the command with min and max values.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @param min         the minimum value (null for no min)
     * @param max         the maximum value (null for no max)
     * @return this builder
     */
    CommandBuilder numberOption(String name, String description, boolean required, Double min, Double max);

    /**
     * Adds an attachment option to the command.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @return this builder
     */
    CommandBuilder attachmentOption(String name, String description, boolean required);

    /**
     * Adds an attachment option restricted to the given file types.
     * <p>
     * Each file type is either a generic category ({@code image}, {@code video}, {@code audio})
     * or a file extension without the leading dot (e.g. {@code svg}, {@code tar.gz}).
     * Discord filters the file picker accordingly; at most 10 file types are allowed.
     *
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @param fileTypes   the accepted file types
     * @return this builder
     * @see fr.farmvivi.fluxcord.api.command.option.CommandOption#getFileTypes()
     */
    CommandBuilder attachmentOption(String name, String description, boolean required, String... fileTypes);

    /**
     * Adds a generic option to the command.
     *
     * @param type        the option type
     * @param name        the option name
     * @param description the option description
     * @param required    true if the option is required
     * @return this builder
     */
    CommandBuilder option(OptionType type, String name, String description, boolean required);

    /**
     * Adds a generic option to the command.
     *
     * @param option the option
     * @return this builder
     */
    CommandBuilder option(CommandOption<?> option);

    /**
     * Adds a subcommand to the command.
     *
     * @param subcommandConsumer consumer to configure the subcommand
     * @return this builder
     */
    CommandBuilder subcommand(Consumer<CommandBuilder> subcommandConsumer);

    /**
     * Sets the command executor.
     *
     * @param executor the command executor
     * @return this builder
     */
    CommandBuilder executor(BiFunction<CommandContext, Command, CommandResult> executor);

    /**
     * Sets the command executor.
     * This is a shorthand for {@link #executor(BiFunction)}.
     *
     * @param executor the command executor
     * @return this builder
     */
    CommandBuilder execute(BiFunction<CommandContext, Command, CommandResult> executor);

    /**
     * Builds the command.
     *
     * @return the built command
     */
    Command build();
}
