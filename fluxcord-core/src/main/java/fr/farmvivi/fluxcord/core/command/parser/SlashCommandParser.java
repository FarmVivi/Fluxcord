package fr.farmvivi.fluxcord.core.command.parser;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.exception.CommandParseException;
import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import fr.farmvivi.fluxcord.api.command.option.OptionType;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.core.command.SimpleCommandContext;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parser for slash commands.
 * This parser extracts command information from SlashCommandInteractionEvents.
 */
public class SlashCommandParser implements CommandParser {

    private static final Logger logger = LoggerFactory.getLogger(SlashCommandParser.class);

    private final LanguageManager languageManager;

    /**
     * Creates a new SlashCommandParser.
     *
     * @param languageManager the language manager to use for localization
     */
    public SlashCommandParser(LanguageManager languageManager) {
        this.languageManager = languageManager;
    }

    @Override
    public boolean canParse(Event event) {
        return event instanceof SlashCommandInteractionEvent;
    }

    @Override
    public CommandContext parse(Event event, Command command) throws CommandParseException {
        if (!(event instanceof SlashCommandInteractionEvent slashEvent)) {
            throw new CommandParseException("Event is not a slash command interaction event");
        }

        // Get user, guild, and channel
        User user = slashEvent.getUser();
        Guild guild = slashEvent.getGuild();

        // Get locale
        Locale locale = slashEvent.getUserLocale().toLocale();
        logger.debug("User locale from interaction: {}", locale);
        // Fallback to default if locale is not set or invalid
        if (locale.getLanguage().isEmpty()) {
            locale = languageManager.getDefaultLocale();
            logger.debug("Falling back to default locale: {}", locale);
        }

        // Route to the subcommand Discord selected (a parent with subcommands has no executor of its own)
        if (!command.getSubcommands().isEmpty()) {
            command = CommandParser.selectSubcommand(command, slashEvent.getSubcommandName());
        }

        // Parse options
        Map<String, Object> options = new HashMap<>();
        for (CommandOption<?> option : command.getOptions()) {
            String name = option.getName();
            OptionMapping mapping = slashEvent.getOption(name);

            if (mapping != null) {
                Object value = parseOptionValue(option.getType(), mapping);
                options.put(name, value);
            }
        }

        // Create and validate context
        SimpleCommandContext context = new SimpleCommandContext(
                event, command, user, guild, slashEvent.getChannel(),
                locale, options, languageManager
        );

        context.validateOptions();
        return context;
    }

    @Override
    public String extractCommandName(Event event) throws CommandParseException {
        if (!(event instanceof SlashCommandInteractionEvent slashEvent)) {
            throw new CommandParseException("Event is not a slash command interaction event");
        }

        return slashEvent.getName();
    }

    @Override
    public boolean isCommandInvocation(Event event) {
        return event instanceof SlashCommandInteractionEvent;
    }

    /**
     * Parses an option value from a slash command option mapping.
     *
     * @param type    the option type
     * @param mapping the option mapping from JDA
     * @return the parsed value
     * @throws CommandParseException if parsing fails
     */
    private Object parseOptionValue(OptionType type, OptionMapping mapping) throws CommandParseException {
        try {
            return switch (type) {
                case STRING -> mapping.getAsString();
                case INTEGER -> mapping.getAsInt();
                case BOOLEAN -> mapping.getAsBoolean();
                case USER -> mapping.getAsUser();
                case CHANNEL -> mapping.getAsChannel();
                case ROLE -> mapping.getAsRole();
                case MENTIONABLE -> {
                    Member member = mapping.getAsMember();
                    if (member != null) {
                        yield member;
                    }

                    Role role = mapping.getAsRole();
                    if (role != null) {
                        yield role;
                    }

                    User user = mapping.getAsUser();
                    if (user != null) {
                        yield user;
                    }

                    Channel channel = mapping.getAsChannel();
                    if (channel != null) {
                        yield channel;
                    }

                    yield mapping.getAsString();
                }
                case NUMBER -> mapping.getAsDouble();
                case ATTACHMENT -> mapping.getAsAttachment();
            };
        } catch (Exception e) {
            logger.error("Failed to parse option value for type {}: {}", type, e.getMessage());
            throw new CommandParseException("Failed to parse option value: " + e.getMessage());
        }
    }
}
