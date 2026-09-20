package fr.farmvivi.fluxcord.core.command.system;

import fr.farmvivi.fluxcord.api.command.*;
import fr.farmvivi.fluxcord.api.command.option.OptionChoice;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.core.command.SimpleCommandBuilder;
import fr.farmvivi.fluxcord.core.command.parser.event.ConsoleCommandEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * System command that displays help information about commands.
 */
public class HelpCommand {

    private final CommandService commandService;
    private final Command command;
    private final LanguageManager languageManager;

    /**
     * Creates a new help command.
     *
     * @param commandService  the command service
     * @param languageManager the language manager
     */
    public HelpCommand(CommandService commandService, LanguageManager languageManager) {
        this.commandService = commandService;
        this.languageManager = languageManager;

        CommandBuilder builder = new SimpleCommandBuilder()
                .name("help")
                .description("Shows information about available commands")
                .category("System")
                .aliases("?")
                .stringOption("command", "The command to get help for", false)
                .stringOption("category", "The category to get help for", false, this::provideCategories)
                .executor(this::execute);

        this.command = builder.build();
    }

    /**
     * Gets the command instance.
     *
     * @return the command
     */
    public Command getCommand() {
        return command;
    }

    /**
     * Provides a list of available categories for autocompletion.
     *
     * @param input the input string
     * @return a list of category choices
     */
    private List<OptionChoice<String>> provideCategories(String input) {
        String lowercaseInput = input.toLowerCase();

        return commandService.getRegistry().getCategories().stream()
                .filter(category -> category.toLowerCase().contains(lowercaseInput))
                .map(category -> OptionChoice.of(category, category))
                .limit(25)
                .collect(Collectors.toList());
    }

    /**
     * Executes the help command.
     *
     * @param context the command context
     * @param command the command
     * @return the command result
     */
    private CommandResult execute(CommandContext context, Command command) {
        // Set ephemeral for help responses as they are typically personal
        context.setEphemeral(true);

        // Check if we're getting help for a specific command
        if (context.hasOption("command")) {
            String commandName = context.getOption("command", "");
            // Text and console users type "help Music" as naturally as "help play": a name that is a category
            // rather than a command gets the category help
            boolean isCommand = commandService.getRegistry().getCommand(commandName).isPresent()
                    || commandService.getRegistry().getCommandByAlias(commandName).isPresent();
            if (!isCommand && !commandService.getRegistry().getCommandsByCategory(commandName).isEmpty()) {
                return showCategoryHelp(context, commandName);
            }
            return showCommandHelp(context, commandName);
        }

        // Check if we're getting help for a specific category
        if (context.hasOption("category")) {
            String category = context.getOption("category", "");
            return showCategoryHelp(context, category);
        }

        // Otherwise, show general help
        return showGeneralHelp(context);
    }

    /**
     * Shows help for a specific command.
     *
     * @param context     the command context
     * @param commandName the command name
     * @return the command result
     */
    private CommandResult showCommandHelp(CommandContext context, String commandName) {
        // Find the command
        Command targetCommand = commandService.getRegistry().getCommand(commandName)
                .orElseGet(() -> commandService.getRegistry().getCommandByAlias(commandName).orElse(null));

        if (targetCommand == null) {
            context.replyError(languageManager.getString(context.getLocale(), "commands.help.command_not_found", commandName));
            return CommandResult.error("Command not found");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(languageManager.getString(context.getLocale(), "commands.help.title", targetCommand.getName()))
                .setDescription(targetCommand.getDescription());

        // Add usage
        StringBuilder usage = new StringBuilder();
        usage.append("**").append(languageManager.getString(context.getLocale(), "commands.help.usage")).append(":** ");

        // Detect command type and show appropriate prefix
        if (context.getOriginalEvent() instanceof SlashCommandInteractionEvent) {
            // Slash command
            usage.append("`/").append(targetCommand.getName());
        } else if (context.getOriginalEvent() instanceof ConsoleCommandEvent) {
            // Console command - no prefix
            usage.append("`").append(targetCommand.getName());
        } else if (context.getOriginalEvent() instanceof MessageReceivedEvent) {
            // Text command - use prefix
            String prefix = commandService.getPrefix();
            if (context.isFromGuild()) {
                prefix = commandService.getPrefix(context.getGuild().get().getId());
            }
            usage.append("`").append(prefix).append(targetCommand.getName());
        } else {
            // Fallback - just show command name
            usage.append("`").append(targetCommand.getName());
        }

        if (!targetCommand.getOptions().isEmpty()) {
            for (var option : targetCommand.getOptions()) {
                if (option.isRequired()) {
                    usage.append(" <").append(option.getName()).append(">");
                } else {
                    usage.append(" [").append(option.getName()).append("]");
                }
            }
        }

        usage.append("`");
        embed.addField(languageManager.getString(context.getLocale(), "commands.help.usage"), usage.toString(), false);

        // Add category
        embed.addField(languageManager.getString(context.getLocale(), "commands.help.category"), targetCommand.getCategory(), true);

        // Add aliases if any
        if (!targetCommand.getAliases().isEmpty()) {
            embed.addField(languageManager.getString(context.getLocale(), "commands.help.aliases"), String.join(", ", targetCommand.getAliases()), true);
        }

        // Add permissions if any
        if (targetCommand.getPermission() != null) {
            embed.addField(languageManager.getString(context.getLocale(), "commands.help.permission"), targetCommand.getPermission(), true);
        }

        // Add options if any
        if (!targetCommand.getOptions().isEmpty()) {
            StringBuilder optionsStr = new StringBuilder();
            for (var option : targetCommand.getOptions()) {
                optionsStr.append("**").append(option.getName()).append("**");
                if (option.isRequired()) {
                    optionsStr.append(" (").append(languageManager.getString(context.getLocale(), "commands.help.options_required")).append(")");
                }
                optionsStr.append(": ").append(option.getDescription()).append("\n");
            }
            embed.addField(languageManager.getString(context.getLocale(), "commands.help.options"), optionsStr.toString(), false);
        }

        // Add subcommands if any
        if (!targetCommand.getSubcommands().isEmpty()) {
            StringBuilder subcommandsStr = new StringBuilder();
            for (var subcommand : targetCommand.getSubcommands()) {
                subcommandsStr.append("**").append(subcommand.getName()).append("**")
                        .append(": ").append(subcommand.getDescription()).append("\n");
            }
            embed.addField(languageManager.getString(context.getLocale(), "commands.help.subcommands"), subcommandsStr.toString(), false);
        }

        context.replyEmbed(embed);
        return CommandResult.success();
    }

    /**
     * Shows help for a specific category.
     *
     * @param context  the command context
     * @param category the category
     * @return the command result
     */
    private CommandResult showCategoryHelp(CommandContext context, String requestedCategory) {
        List<Command> commands = commandService.getRegistry().getCommandsByCategory(requestedCategory);
        String category = commands.isEmpty() ? requestedCategory : commands.get(0).getCategory();

        if (commands.isEmpty()) {
            context.replyError(languageManager.getString(context.getLocale(), "commands.help.category_not_found", category));
            return CommandResult.error("Category not found");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(languageManager.getString(context.getLocale(), "commands.help.category_title", category))
                .setDescription(languageManager.getString(context.getLocale(), "commands.help.category_description"));

        commands.stream()
                .sorted(Comparator.comparing(Command::getName))
                .forEach(cmd -> embed.addField(cmd.getName(), cmd.getDescription(), false));

        context.replyEmbed(embed);
        return CommandResult.success();
    }

    /**
     * Shows general help with all categories and commands.
     *
     * @param context the command context
     * @return the command result
     */
    private CommandResult showGeneralHelp(CommandContext context) {
        // Build dynamic description with correct prefix based on command type
        String helpPrefix;
        if (context.getOriginalEvent() instanceof SlashCommandInteractionEvent) {
            helpPrefix = "/help";
        } else if (context.getOriginalEvent() instanceof ConsoleCommandEvent) {
            helpPrefix = "help";
        } else if (context.getOriginalEvent() instanceof MessageReceivedEvent) {
            String prefix = commandService.getPrefix();
            if (context.isFromGuild()) {
                prefix = commandService.getPrefix(context.getGuild().get().getId());
            }
            helpPrefix = prefix + "help";
        } else {
            helpPrefix = "help";
        }

        String description = languageManager.getString(context.getLocale(), "commands.help.general_description", helpPrefix);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(languageManager.getString(context.getLocale(), "commands.help.general_title"))
                .setDescription(description);

        // Group commands by category
        Map<String, List<Command>> commandsByCategory = commandService.getRegistry().getCommands().stream()
                .filter(Command::isEnabled)
                .collect(Collectors.groupingBy(Command::getCategory));

        // Sort categories and add them to the embed
        List<String> categories = new ArrayList<>(commandsByCategory.keySet());
        categories.sort(String::compareToIgnoreCase);

        for (String category : categories) {
            List<Command> commands = commandsByCategory.get(category);

            StringBuilder value = new StringBuilder();
            for (Command cmd : commands.stream().sorted(Comparator.comparing(Command::getName)).toList()) {
                value.append("`").append(cmd.getName()).append("` - ").append(cmd.getDescription()).append("\n");
            }

            embed.addField(category, value.toString(), false);

            // Check if we need to split the embed due to Discord's limits
            if (embed.getFields().size() >= MessageEmbed.MAX_FIELD_AMOUNT ||
                    embed.length() >= MessageEmbed.EMBED_MAX_LENGTH_BOT - 100) {

                // Send the current embed
                context.replyEmbed(embed);

                // Create a new embed for the next categories
                embed = new EmbedBuilder()
                        .setTitle(languageManager.getString(context.getLocale(), "commands.help.continued"));
            }
        }

        // Send the final embed
        if (!embed.getFields().isEmpty()) {
            context.replyEmbed(embed);
        }

        return CommandResult.success();
    }
}
