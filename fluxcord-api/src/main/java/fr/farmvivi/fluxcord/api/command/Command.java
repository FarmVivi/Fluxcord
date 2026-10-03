package fr.farmvivi.fluxcord.api.command;

import fr.farmvivi.fluxcord.api.command.option.CommandOption;

import java.util.List;
import java.util.Set;

/**
 * Represents a command that can be executed by users.
 * Commands can have options, subcommands, and aliases.
 */
public interface Command {

    /**
     * Gets the name of the command.
     *
     * @return the command name
     */
    String getName();

    /**
     * Gets the description of the command.
     *
     * @return the command description
     */
    String getDescription();

    /**
     * Gets the category of the command.
     *
     * @return the command category
     */
    String getCategory();

    /**
     * Gets the options of the command.
     *
     * @return the command options
     */
    List<CommandOption<?>> getOptions();

    /**
     * Gets the subcommands of the command.
     *
     * @return the subcommands
     */
    List<Command> getSubcommands();

    /**
     * Gets the group of the command, if any.
     * This is used for slash command grouping.
     *
     * @return the command group, or null if not grouped
     */
    String getGroup();

    /**
     * Gets the permission required to execute the command.
     *
     * @return the permission string, or null if no specific permission is required
     */
    String getPermission();

    /**
     * Gets the translation key prefix for this command.
     * This is used for internationalization of command help and responses.
     *
     * @return the translation key prefix
     */
    String getTranslationKey();

    /**
     * Gets the aliases of the command.
     * Aliases can be used with text commands.
     *
     * @return the command aliases
     */
    Set<String> getAliases();

    /**
     * Checks if the command is guild-only.
     *
     * @return true if the command can only be executed in a guild
     */
    boolean isGuildOnly();

    /**
     * Whether this command's reply is shown only to whoever ran it.
     *
     * <p>A property of the command and not of each reply, because the choice is the same every time a
     * command runs and because a command that defers has already decided by the time its executor could
     * say anything: Discord takes the flag from the deferral, so setting it afterwards is ignored. The
     * service applies this to the context before the executor runs, which is early enough for both.
     *
     * <p>Defaults to false. The ones worth turning on are the acknowledgements — "listening", "stopped",
     * "volume set" — which say nothing to anybody but the person who asked and which bury a channel.
     *
     * @return true when only the caller sees the reply
     */
    default boolean isEphemeral() {
        return false;
    }

    /**
     * Gets the guild IDs where this command is available.
     * If empty, the command is available in all guilds.
     *
     * @return the guild IDs
     */
    Set<String> getGuildIds();

    /**
     * Checks if this command is a subcommand.
     *
     * @return true if this command is a subcommand
     */
    boolean isSubcommand();

    /**
     * Gets the parent command if this is a subcommand.
     *
     * @return the parent command, or null if this is not a subcommand
     */
    Command getParent();

    /**
     * Checks if this command is enabled.
     *
     * @return true if the command is enabled
     */
    boolean isEnabled();

    /**
     * Gets the cooldown of the command in seconds.
     *
     * @return the cooldown in seconds, or 0 if no cooldown
     */
    int getCooldown();

    /**
     * Executes the command with the given context.
     *
     * @param context the command context
     * @return the command result
     */
    CommandResult execute(CommandContext context);

    /**
     * Gets the full name of the command, including parent groups and commands.
     * For example, "group/parent/command".
     *
     * @return the full command name
     */
    default String getFullName() {
        StringBuilder name = new StringBuilder(getName());
        Command parent = getParent();

        while (parent != null) {
            name.insert(0, parent.getName() + "/");
            parent = parent.getParent();
        }

        return name.toString();
    }
}
