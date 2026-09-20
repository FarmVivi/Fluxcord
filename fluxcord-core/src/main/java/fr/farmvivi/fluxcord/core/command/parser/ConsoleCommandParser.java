package fr.farmvivi.fluxcord.core.command.parser;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.exception.CommandParseException;
import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.core.command.SimpleCommandContext;
import fr.farmvivi.fluxcord.core.command.parser.event.ConsoleCommandEvent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for console commands.
 * This parser extracts command information from console input.
 */
public class ConsoleCommandParser implements CommandParser {

    private static final Logger logger = LoggerFactory.getLogger(ConsoleCommandParser.class);

    private final LanguageManager languageManager;

    /**
     * Creates a new console command parser.
     *
     * @param languageManager the language manager to use for localization
     */
    public ConsoleCommandParser(LanguageManager languageManager) {
        this.languageManager = languageManager;
    }

    @Override
    public boolean canParse(Event event) {
        return event instanceof ConsoleCommandEvent;
    }

    @Override
    public CommandContext parse(Event event, Command command) throws CommandParseException {
        if (!(event instanceof ConsoleCommandEvent consoleEvent)) {
            throw new CommandParseException("Event is not a console command event");
        }

        String input = consoleEvent.getInput();

        // Parse the input
        String[] parts = input.split("\\s+", 2);
        String commandName = parts[0].toLowerCase();
        String argsStr = parts.length > 1 ? parts[1] : "";

        // Check if this is the correct command
        if (!commandName.equalsIgnoreCase(command.getName()) &&
                command.getAliases().stream().noneMatch(a -> a.equalsIgnoreCase(commandName))) {
            throw new CommandParseException("Command name does not match");
        }

        // Route to a subcommand: the next token names it
        if (!command.getSubcommands().isEmpty()) {
            String[] subParts = argsStr.split("\s+", 2);
            command = CommandParser.selectSubcommand(command, subParts[0]);
            argsStr = subParts.length > 1 ? subParts[1] : "";
        }

        // Parse arguments for console (simplified - just string splitting)
        Map<String, Object> options = parseOptions(argsStr, command, consoleEvent.getJDA());

        // Create console context with locale US since console has no user
        SimpleCommandContext context = new SimpleCommandContext(
                event, command, null, null, null, languageManager.getDefaultLocale(), options, languageManager
        );

        context.validateOptions();
        return context;
    }

    @Override
    public String extractCommandName(Event event) throws CommandParseException {
        if (!(event instanceof ConsoleCommandEvent consoleEvent)) {
            throw new CommandParseException("Event is not a console command event");
        }

        String input = consoleEvent.getInput();
        String[] parts = input.split("\\s+", 2);
        return parts[0].toLowerCase();
    }

    @Override
    public boolean isCommandInvocation(Event event) {
        if (!(event instanceof ConsoleCommandEvent consoleEvent)) {
            return false;
        }

        String input = consoleEvent.getInput().trim();
        logger.debug("Console command detected: '{}'", input);

        // Any non-empty input is considered a command attempt from console
        return !input.isEmpty();
    }

    /**
     * Parses command options from a string for console commands.
     *
     * @param argsStr the arguments string
     * @param command the command
     * @return the parsed options
     * @throws CommandParseException if parsing fails
     */
    private Map<String, Object> parseOptions(String argsStr, Command command, JDA jda) throws CommandParseException {
        Map<String, Object> options = new HashMap<>();
        List<CommandOption<?>> commandOptions = command.getOptions();

        if (commandOptions.isEmpty() || argsStr.isEmpty()) {
            return options;
        }

        List<String> args = splitArguments(argsStr);
        int optionIndex = 0;

        for (String arg : args) {
            if (optionIndex >= commandOptions.size()) {
                break;
            }

            CommandOption<?> option = commandOptions.get(optionIndex);
            try {
                Object value = parseOptionValue(arg, option, jda);
                options.put(option.getName(), value);
                optionIndex++;
            } catch (CommandParseException e) {
                logger.warn("Failed to parse console option '{}': {}", option.getName(), e.getMessage());
                if (option.isRequired()) {
                    throw new CommandParseException("Required option '" + option.getName() +
                            "' could not be parsed: " + e.getMessage(), option.getName());
                }
                // Skip optional argument that failed to parse
            }
        }

        return options;
    }

    /**
     * Splits a string into command arguments, respecting quotes.
     *
     * @param argsStr the arguments string
     * @return the list of arguments
     */
    private List<String> splitArguments(String argsStr) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = '"';

        for (int i = 0; i < argsStr.length(); i++) {
            char c = argsStr.charAt(i);

            if (c == '"' || c == '\'') {
                if (inQuotes) {
                    if (c == quoteChar) {
                        inQuotes = false;
                    } else {
                        current.append(c);
                    }
                } else {
                    inQuotes = true;
                    quoteChar = c;

                    // If there's content before the quote, add it as a separate arg
                    if (current.length() > 0) {
                        args.add(current.toString().trim());
                        current = new StringBuilder();
                    }
                }
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    args.add(current.toString().trim());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString().trim());
        }

        return args;
    }

    /**
     * Parses an option value from a string for console commands.
     *
     * @param arg    the argument string
     * @param option the option
     * @return the parsed value
     * @throws CommandParseException if parsing fails
     */
    private Object parseOptionValue(String arg, CommandOption<?> option, JDA jda) throws CommandParseException {
        try {
            return switch (option.getType()) {
                case STRING -> arg;
                case INTEGER -> Integer.parseInt(arg);
                case BOOLEAN -> parseBoolean(arg);
                case NUMBER -> Double.parseDouble(arg);
                // Users can be given by ID (or <@id> mention) and are resolved through Discord
                case USER -> {
                    String id = arg.replaceAll("[^0-9]", "");
                    if (id.isEmpty() || jda == null) {
                        throw new CommandParseException("Expected a Discord user ID for option " + option.getName());
                    }
                    User user = jda.getUserById(id);
                    if (user == null) {
                        user = jda.retrieveUserById(id).complete();
                    }
                    yield user;
                }
                case CHANNEL, ROLE, MENTIONABLE, ATTACHMENT -> {
                    logger.warn("Option type {} not supported in console commands", option.getType());
                    throw new CommandParseException("Option type not supported in console");
                }
            };
        } catch (Exception e) {
            if (e instanceof CommandParseException) {
                throw e;
            }
            throw new CommandParseException("Failed to parse option value: " + e.getMessage());
        }
    }

    /**
     * Parses a boolean value from a string.
     *
     * @param arg the argument string
     * @return the parsed boolean
     * @throws CommandParseException if parsing fails
     */
    private boolean parseBoolean(String arg) throws CommandParseException {
        arg = arg.toLowerCase();
        if (arg.equals("true") || arg.equals("yes") || arg.equals("y") || arg.equals("1")) {
            return true;
        } else if (arg.equals("false") || arg.equals("no") || arg.equals("n") || arg.equals("0")) {
            return false;
        } else {
            throw new CommandParseException("Invalid boolean value: " + arg);
        }
    }
}