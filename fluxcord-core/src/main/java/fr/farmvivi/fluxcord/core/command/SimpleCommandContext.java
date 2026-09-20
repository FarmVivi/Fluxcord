package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.exception.CommandParseException;
import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import fr.farmvivi.fluxcord.api.command.option.OptionType;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Implementation of CommandContext that provides all necessary functionality
 * for command execution.
 */
public class SimpleCommandContext implements CommandContext {
    private static final Logger logger = LoggerFactory.getLogger(SimpleCommandContext.class);

    private final Event originalEvent;
    private final Command command;
    private final User user;
    private final Guild guild;
    private final MessageChannel channel;
    private final Locale locale;
    private final Map<String, Object> options;
    private final LanguageManager languageManager;
    private boolean deferred = false;
    private boolean replied = false;
    private boolean ephemeral = false;

    /**
     * Creates a new SimpleCommandContext with specified parameters.
     *
     * @param originalEvent   the original JDA event
     * @param command         the command being executed
     * @param user            the user executing the command
     * @param guild           the guild where the command is executed (may be null)
     * @param channel         the channel where the command is executed
     * @param locale          the locale of the user or guild
     * @param options         the options provided by the user
     * @param languageManager the language manager for translations
     */
    public SimpleCommandContext(Event originalEvent, Command command, User user,
                                Guild guild, MessageChannel channel,
                                Locale locale, Map<String, Object> options,
                                LanguageManager languageManager) {
        this.originalEvent = originalEvent;
        this.command = command;
        this.user = user;
        this.guild = guild;
        this.channel = channel;
        this.locale = locale;
        this.options = options != null ? new HashMap<>(options) : new HashMap<>();
        this.languageManager = languageManager;
    }

    @Override
    public Event getOriginalEvent() {
        return originalEvent;
    }

    @Override
    public Command getCommand() {
        return command;
    }

    @Override
    public User getUser() {
        return user;
    }

    @Override
    public Optional<Guild> getGuild() {
        return Optional.ofNullable(guild);
    }

    @Override
    public MessageChannel getChannel() {
        return channel;
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    @Override
    public <T> Optional<T> getOption(String name) {
        Object value = options.get(name);
        if (value == null) {
            return Optional.empty();
        }

        try {
            return Optional.of((T) value);
        } catch (ClassCastException e) {
            return Optional.empty();
        }
    }

    @Override
    public <T> T getOption(String name, T defaultValue) {
        Object value = options.get(name);
        if (value == null) {
            return defaultValue;
        }

        try {
            return (T) value;
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    @Override
    public <T> T getRequiredOption(String name) {
        Object value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Required option '" + name + "' is missing");
        }

        try {
            return (T) value;
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("Option '" + name + "' has incorrect type", e);
        }
    }

    @Override
    public <T> Optional<CommandOption<T>> getOptionDefinition(String name) {
        for (CommandOption<?> option : command.getOptions()) {
            if (option.getName().equals(name)) {
                try {
                    return Optional.of((CommandOption<T>) option);
                } catch (ClassCastException e) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean hasOption(String name) {
        return options.containsKey(name);
    }

    @Override
    public void reply(String message) {
        CommandMessageBuilder messageBuilder = new CommandMessageBuilder(originalEvent, languageManager, locale);
        messageBuilder.setContent(message);
        messageBuilder.setDiffer(deferred);
        messageBuilder.setEphemeral(ephemeral);
        messageBuilder.replyNow();
        replied = true;
    }

    @Override
    public void reply(String message, Collection<MessageTopLevelComponent> components) {
        CommandMessageBuilder messageBuilder = new CommandMessageBuilder(originalEvent, languageManager, locale);
        messageBuilder.setContent(message);
        messageBuilder.setDiffer(deferred);
        messageBuilder.setEphemeral(ephemeral);
        if (components != null && !components.isEmpty()) {
            messageBuilder.setComponents(components);
        }
        messageBuilder.replyNow();
        replied = true;
    }

    @Override
    public void replyEmbed(EmbedBuilder embed) {
        CommandMessageBuilder messageBuilder = new CommandMessageBuilder(originalEvent, languageManager, locale);
        messageBuilder.setDiffer(deferred);
        messageBuilder.setEphemeral(ephemeral);
        messageBuilder.addEmbeds(embed.build());
        messageBuilder.replyNow();
        replied = true;
    }

    @Override
    public void replyEmbed(EmbedBuilder embed, Collection<MessageTopLevelComponent> components) {
        CommandMessageBuilder messageBuilder = new CommandMessageBuilder(originalEvent, languageManager, locale);
        messageBuilder.setDiffer(deferred);
        messageBuilder.setEphemeral(ephemeral);
        messageBuilder.addEmbeds(embed.build());
        if (components != null && !components.isEmpty()) {
            messageBuilder.setComponents(components);
        }
        messageBuilder.replyNow();
        replied = true;
    }

    @Override
    public void replySuccess(String message) {
        CommandMessageBuilder messageBuilder = new CommandMessageBuilder(originalEvent, languageManager, locale);
        messageBuilder.setDiffer(deferred);
        messageBuilder.setEphemeral(ephemeral);
        messageBuilder.success(message);
        messageBuilder.replyNow();
        replied = true;
    }

    @Override
    public void replyInfo(String message) {
        CommandMessageBuilder messageBuilder = new CommandMessageBuilder(originalEvent, languageManager, locale);
        messageBuilder.setDiffer(deferred);
        messageBuilder.setEphemeral(ephemeral);
        messageBuilder.info(message);
        messageBuilder.replyNow();
        replied = true;
    }

    @Override
    public void replyWarning(String message) {
        CommandMessageBuilder messageBuilder = new CommandMessageBuilder(originalEvent, languageManager, locale);
        messageBuilder.setDiffer(deferred);
        messageBuilder.setEphemeral(ephemeral);
        messageBuilder.warning(message);
        messageBuilder.replyNow();
        replied = true;
    }

    @Override
    public void replyError(String message) {
        CommandMessageBuilder messageBuilder = new CommandMessageBuilder(originalEvent, languageManager, locale);
        messageBuilder.setDiffer(deferred);
        messageBuilder.setEphemeral(ephemeral);
        messageBuilder.error(message);
        messageBuilder.replyNow();
        replied = true;
    }

    @Override
    public void deferReply() {
        deferReply(ephemeral);
    }

    @Override
    public void deferReply(boolean ephemeral) {
        this.deferred = true;
        this.ephemeral = ephemeral;

        // Directly perform deferral for interaction events
        if (originalEvent instanceof net.dv8tion.jda.api.interactions.callbacks.IReplyCallback callback && !callback.isAcknowledged()) {
            logger.debug("Deferring reply for interaction event.");
            callback.deferReply(ephemeral).queue();
        }
    }

    @Override
    public boolean hasReplied() {
        return replied;
    }

    @Override
    public boolean isDeferred() {
        return deferred;
    }

    @Override
    public boolean isEphemeral() {
        return ephemeral;
    }

    @Override
    public void setEphemeral(boolean ephemeral) {
        this.ephemeral = ephemeral;
    }

    @Override
    public JDA getJDA() {
        // Get JDA from the original event
        if (originalEvent != null) {
            return originalEvent.getJDA();
        }
        return null;
    }

    /**
     * Adds an option to the context.
     * This is used by parsers to populate the options.
     *
     * @param name  the option name
     * @param value the option value
     */
    public void addOption(String name, Object value) {
        this.options.put(name, value);
    }

    /**
     * Validates all options against their definitions.
     *
     * @throws CommandParseException if validation fails
     */
    public void validateOptions() throws CommandParseException {
        for (CommandOption<?> option : command.getOptions()) {
            String name = option.getName();
            if (option.isRequired() && !options.containsKey(name)) {
                throw new CommandParseException("Required option '" + name + "' is missing", name);
            }

            if (options.containsKey(name)) {
                Object value = options.get(name);
                if (!isValidOptionType(option, value)) {
                    throw new CommandParseException(
                            "Option '" + name + "' has incorrect type. Expected " + option.getType(), name);
                }
            }
        }
    }

    /**
     * Checks if the value has the correct type for the option.
     *
     * @param option the option definition
     * @param value  the value to check
     * @return true if the value has the correct type
     */
    /** The Java type each option type accepts (the parsers produce exactly these). */
    static boolean matchesType(OptionType type, Object value) {
        return switch (type) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof Integer || value instanceof Long;
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case USER -> value instanceof User;
            case CHANNEL -> value instanceof Channel;
            case ROLE -> value instanceof Role;
            case MENTIONABLE -> value instanceof IMentionable;
            case ATTACHMENT -> value instanceof Message.Attachment;
        };
    }

    private boolean isValidOptionType(CommandOption option, Object value) {
        if (value == null) {
            return !option.isRequired();
        }
        if (!matchesType(option.getType(), value)) {
            return false;
        }

        try {
            return option.isValid(value);
        } catch (ClassCastException e) {
            return false;
        }
    }
}
