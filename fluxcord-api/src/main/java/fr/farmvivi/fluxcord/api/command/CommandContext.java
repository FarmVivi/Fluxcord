package fr.farmvivi.fluxcord.api.command;

import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.Event;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;

/**
 * The execution context of a command.
 * Provides access to command options, the user, guild, channel, and methods to reply.
 */
public interface CommandContext {

    /**
     * Gets the original JDA event that triggered this command.
     *
     * @return the original JDA event
     */
    Event getOriginalEvent();

    /**
     * Gets the command that is being executed.
     *
     * @return the command
     */
    Command getCommand();

    /**
     * Gets the user who executed the command.
     *
     * @return the user
     */
    User getUser();

    /**
     * Gets the guild where the command was executed.
     *
     * @return the guild, or empty if the command was executed in a DM
     */
    Optional<Guild> getGuild();

    /**
     * Gets the channel where the command was executed.
     *
     * @return the channel
     */
    MessageChannel getChannel();

    /**
     * Gets the locale of the user or guild.
     *
     * @return the locale
     */
    Locale getLocale();

    /**
     * Gets an option value by name.
     *
     * @param name the option name
     * @param <T>  the option type
     * @return the option value, or empty if not provided
     */
    <T> Optional<T> getOption(String name);

    /**
     * Gets an option value by name with a default value.
     *
     * @param name         the option name
     * @param defaultValue the default value
     * @param <T>          the option type
     * @return the option value, or the default value if not provided
     */
    <T> T getOption(String name, T defaultValue);

    /**
     * Gets a required option value by name.
     *
     * @param name the option name
     * @param <T>  the option type
     * @return the option value
     * @throws IllegalArgumentException if the option is not provided
     */
    <T> T getRequiredOption(String name);

    /**
     * Gets the definition of an option by name.
     *
     * @param name the option name
     * @param <T>  the option type
     * @return the option definition, or empty if not found
     */
    <T> Optional<CommandOption<T>> getOptionDefinition(String name);

    /**
     * Checks if the command was invoked with a specific option.
     *
     * @param name the option name
     * @return true if the option was provided
     */
    boolean hasOption(String name);

    /**
     * Replies to the command with a message.
     *
     * @param message the message content
     */
    void reply(String message);

    /**
     * Replies to the command with a message and components.
     *
     * @param message    the message content
     * @param components the message components
     */
    void reply(String message, Collection<MessageTopLevelComponent> components);

    /**
     * Replies to the command with an embed.
     *
     * @param embed the embed builder
     */
    void replyEmbed(EmbedBuilder embed);

    /**
     * Replies to the command with an embed and components.
     *
     * @param embed      the embed builder
     * @param components the message components
     */
    void replyEmbed(EmbedBuilder embed, Collection<MessageTopLevelComponent> components);

    /**
     * Replies to the command with a success message.
     *
     * @param message the success message
     */
    void replySuccess(String message);

    /**
     * Replies to the command with an info message.
     *
     * @param message the info message
     */
    void replyInfo(String message);

    /**
     * Replies to the command with a warning message.
     *
     * @param message the warning message
     */
    void replyWarning(String message);

    /**
     * Replies to the command with an error message.
     *
     * @param message the error message
     */
    void replyError(String message);

    /**
     * Defers the reply to the command.
     * This is useful for commands that take a long time to execute.
     */
    void deferReply();

    /**
     * Defers the reply to the command with ephemeral flag.
     *
     * @param ephemeral true if the reply should be ephemeral
     */
    void deferReply(boolean ephemeral);

    /**
     * Checks if the command is from a guild.
     *
     * @return true if the command was executed in a guild
     */
    default boolean isFromGuild() {
        return getGuild().isPresent();
    }

    /**
     * Checks if the reply has been deferred.
     *
     * @return true if the reply has been deferred
     */
    boolean isDeferred();

    /**
     * Whether a reply (not a mere deferral) has already been sent through this context. The core uses it to
     * avoid answering a failed command twice when the command already explained the failure itself.
     *
     * @return true once any {@code reply*} method has been called
     */
    default boolean hasReplied() {
        return false;
    }

    /**
     * Checks if the reply should be ephemeral.
     *
     * @return true if the reply should be ephemeral
     */
    boolean isEphemeral();

    /**
     * Sets whether the reply should be ephemeral.
     *
     * @param ephemeral true if the reply should be ephemeral
     */
    void setEphemeral(boolean ephemeral);

    /**
     * Gets the JDA instance associated with this command context.
     *
     * @return the JDA instance, or null if not available
     */
    JDA getJDA();
}
