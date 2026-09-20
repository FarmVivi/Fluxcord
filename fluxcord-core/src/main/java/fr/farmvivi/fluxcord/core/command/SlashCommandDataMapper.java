package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import fr.farmvivi.fluxcord.api.command.option.OptionChoice;
import fr.farmvivi.fluxcord.api.command.option.OptionType2;
import net.dv8tion.jda.api.interactions.FileType;
import net.dv8tion.jda.api.interactions.InteractionContextType;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData;

import java.util.Locale;

/**
 * Maps the Fluxcord {@link Command} model to the JDA {@link CommandData} sent to Discord on slash-command sync.
 * Pure translation, no state: names are lower-cased, options carry their bounds/choices/autocomplete/file types
 * (for top-level and subcommand options alike), a command with a permission is hidden by default
 * ({@link DefaultMemberPermissions#DISABLED}) and guild-only commands are limited to the guild context.
 */
final class SlashCommandDataMapper {
    private SlashCommandDataMapper() {
    }

    static CommandData toCommandData(Command command) {
        SlashCommandData data = Commands.slash(command.getName().toLowerCase(Locale.ROOT), command.getDescription());

        for (CommandOption<?> option : command.getOptions()) {
            data.addOptions(toOptionData(option));
        }

        if (!command.getSubcommands().isEmpty()) {
            if (command.getGroup() != null) {
                data.addSubcommandGroups(toSubcommandGroupData(command));
            } else {
                for (Command subcommand : command.getSubcommands()) {
                    data.addSubcommands(toSubcommandData(subcommand));
                }
            }
        }

        data.setDefaultPermissions(command.getPermission() != null
                ? DefaultMemberPermissions.DISABLED : DefaultMemberPermissions.ENABLED);
        if (command.isGuildOnly()) {
            data.setContexts(InteractionContextType.GUILD);
        } else {
            data.setContexts(InteractionContextType.GUILD, InteractionContextType.BOT_DM, InteractionContextType.PRIVATE_CHANNEL);
        }
        return data;
    }

    static OptionData toOptionData(CommandOption<?> option) {
        OptionData data = new OptionData(option.getType().getJdaType(), option.getName(),
                option.getDescription(), option.isRequired());

        if (option.getMinValue() != null) {
            if (option.getType() == OptionType2.INTEGER) {
                data.setMinValue(option.getMinValue().longValue());
            } else if (option.getType() == OptionType2.NUMBER) {
                data.setMinValue(option.getMinValue().doubleValue());
            }
        }
        if (option.getMaxValue() != null) {
            if (option.getType() == OptionType2.INTEGER) {
                data.setMaxValue(option.getMaxValue().longValue());
            } else if (option.getType() == OptionType2.NUMBER) {
                data.setMaxValue(option.getMaxValue().doubleValue());
            }
        }
        if (option.getMinLength() != null) {
            data.setMinLength(option.getMinLength());
        }
        if (option.getMaxLength() != null) {
            data.setMaxLength(option.getMaxLength());
        }

        for (OptionChoice<?> choice : option.getChoices()) {
            if (choice.value() instanceof String string) {
                data.addChoice(choice.name(), string);
            } else if (choice.value() instanceof Integer integer) {
                data.addChoice(choice.name(), integer);
            } else if (choice.value() instanceof Long l) {
                data.addChoice(choice.name(), l);
            } else if (choice.value() instanceof Double doubleValue) {
                data.addChoice(choice.name(), doubleValue);
            }
        }

        if (option.getAutocompleteProvider() != null) {
            data.setAutoComplete(true);
        }
        if (option.getType() == OptionType2.ATTACHMENT && !option.getFileTypes().isEmpty()) {
            data.addFileTypes(option.getFileTypes().stream().map(SlashCommandDataMapper::toFileType).toList());
        }
        return data;
    }

    static SubcommandData toSubcommandData(Command subcommand) {
        SubcommandData data = new SubcommandData(subcommand.getName().toLowerCase(Locale.ROOT), subcommand.getDescription());
        for (CommandOption<?> option : subcommand.getOptions()) {
            data.addOptions(toOptionData(option));
        }
        return data;
    }

    private static SubcommandGroupData toSubcommandGroupData(Command command) {
        SubcommandGroupData group = new SubcommandGroupData(command.getGroup().toLowerCase(Locale.ROOT), command.getDescription());
        for (Command subcommand : command.getSubcommands()) {
            group.addSubcommands(toSubcommandData(subcommand));
        }
        return group;
    }

    private static FileType toFileType(String fileType) {
        return switch (fileType.toLowerCase(Locale.ROOT)) {
            case "image" -> FileType.IMAGE;
            case "video" -> FileType.VIDEO;
            case "audio" -> FileType.AUDIO;
            default -> FileType.ofExtension(fileType);
        };
    }
}
