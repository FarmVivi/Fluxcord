package fr.farmvivi.fluxcord.core.command.system;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.option.OptionChoice;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.core.command.SimpleCommandBuilder;

import java.util.Set;

/**
 * {@code op <add|remove|list> [userId]}: manages the global operators (users who get every permission whose
 * default is {@code OP}). Usable from the console, or from Discord by an operator of the current guild.
 */
public class OpCommand {
    private final Command command;
    private final LanguageManager languageManager;
    private final PermissionManager permissionManager;

    public OpCommand(LanguageManager languageManager, PermissionManager permissionManager) {
        this.languageManager = languageManager;
        this.permissionManager = permissionManager;
        command = new SimpleCommandBuilder()
                .name("op")
                .description("Manages the bot operators")
                .category("System")
                .stringOption("action", "add, remove or list", true,
                        new OptionChoice<>("add", "add"), new OptionChoice<>("remove", "remove"), new OptionChoice<>("list", "list"))
                .stringOption("user", "Discord user ID", false)
                .executor(this::execute)
                .build();
    }

    public Command getCommand() {
        return command;
    }

    private CommandResult execute(CommandContext context, Command command) {
        context.setEphemeral(true);

        // Console has no user; from Discord only an operator (global or of this guild) may manage operators
        if (context.getUser() != null) {
            String userId = context.getUser().getId();
            String guildId = context.getGuild().map(g -> g.getId()).orElse(null);
            if (!permissionManager.isOperator(userId, guildId)) {
                context.replyError(languageManager.getString(context.getLocale(), "commands.op.not_operator"));
                return CommandResult.error("not an operator");
            }
        }

        String action = context.getOption("action", "list");
        String target = context.<String>getOption("user").orElse(null);

        switch (action) {
            case "add", "remove" -> {
                if (target == null || !target.matches("\\d{5,25}")) {
                    context.replyError(languageManager.getString(context.getLocale(), "commands.op.invalid_user"));
                    return CommandResult.error("invalid user id");
                }
                boolean grant = action.equals("add");
                boolean changed = permissionManager.setOperator(target, grant);
                String key = changed ? (grant ? "commands.op.added" : "commands.op.removed")
                        : (grant ? "commands.op.already" : "commands.op.not_found");
                context.replySuccess(languageManager.getString(context.getLocale(), key, target));
                return CommandResult.success();
            }
            default -> {
                Set<String> operators = permissionManager.getOperators();
                context.replyInfo(operators.isEmpty()
                        ? languageManager.getString(context.getLocale(), "commands.op.none")
                        : languageManager.getString(context.getLocale(), "commands.op.list", String.join(", ", operators)));
                return CommandResult.success();
            }
        }
    }
}
