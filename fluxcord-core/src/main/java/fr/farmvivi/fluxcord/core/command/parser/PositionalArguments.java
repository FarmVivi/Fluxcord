package fr.farmvivi.fluxcord.core.command.parser;

import fr.farmvivi.fluxcord.api.command.exception.CommandParseException;
import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import fr.farmvivi.fluxcord.api.command.option.OptionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared by the text and console parsers: how a line of arguments becomes option values.
 * <ul>
 *   <li>{@link #split}: whitespace-separated tokens, {@code "double"} and {@code 'single'} quotes group words;</li>
 *   <li>{@link #assign}: tokens are matched to the command's options in declaration order; a trailing
 *       {@code STRING} option takes every remaining token ({@code play never gonna give you up} is one query);
 *       a token that does not parse is fatal for a required option; an optional option that does not take
 *       the token is treated as omitted and the token goes to the next option.</li>
 * </ul>
 */
public final class PositionalArguments {
    private static final Logger logger = LoggerFactory.getLogger(PositionalArguments.class);

    private PositionalArguments() {
    }

    /** Parses one token into the value of one option, throwing {@link CommandParseException} when it cannot. */
    @FunctionalInterface
    public interface ValueParser {
        Object parse(String token, CommandOption<?> option) throws CommandParseException;
    }

    public static List<String> split(String line) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = '"';
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
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
                    if (!current.isEmpty()) {
                        args.add(current.toString().trim());
                        current = new StringBuilder();
                    }
                }
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (!current.isEmpty()) {
                    args.add(current.toString().trim());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            args.add(current.toString().trim());
        }
        return args;
    }

    /**
     * @return option name → parsed value (only the options that got a value)
     * @throws CommandParseException when a required option's token does not parse
     */
    public static Map<String, Object> assign(String line, List<CommandOption<?>> options, ValueParser parser)
            throws CommandParseException {
        Map<String, Object> values = new HashMap<>();
        if (options.isEmpty() || line.isEmpty()) {
            return values;
        }
        List<String> args = split(line);
        int optionIndex = 0;
        for (int i = 0; i < args.size() && optionIndex < options.size(); i++) {
            CommandOption<?> option = options.get(optionIndex);
            String token = args.get(i);
            boolean lastOption = optionIndex == options.size() - 1;
            if (lastOption && option.getType() == OptionType.STRING && i < args.size() - 1) {
                token = String.join(" ", args.subList(i, args.size()));
                i = args.size();
            }
            try {
                values.put(option.getName(), parser.parse(token, option));
                optionIndex++;
            } catch (CommandParseException e) {
                if (option.isRequired()) {
                    logger.warn("Failed to parse option '{}': {}", option.getName(), e.getMessage());
                    throw new CommandParseException("Required option '" + option.getName()
                            + "' could not be parsed: " + e.getMessage(), option.getName());
                }
                // the user most likely omitted this optional option: offer the same token to the next one
                logger.debug("Optional option '{}' skipped: {}", option.getName(), e.getMessage());
                optionIndex++;
                i--;
            }
        }
        return values;
    }
}
