package fr.farmvivi.fluxcord.core.command.system;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.option.AutocompleteContext;
import fr.farmvivi.fluxcord.api.command.option.OptionChoice;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.core.command.SimpleCommandBuilder;

import net.dv8tion.jda.api.entities.User;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code perm <set|unset|list|nodes> [permission] [user] [value] [scope]}: stored permission overrides for
 * users, persisted through the data storage. Usable from the console, or from Discord by an operator.
 * <ul>
 *   <li>{@code set <permission> <user> <true|false> [guild|global]} — store an override (default scope:
 *       the current guild on Discord, global from the console);</li>
 *   <li>{@code unset <permission> <user> [guild|global]} — remove it;</li>
 *   <li>{@code list <user>} — the user's overrides and effective values;</li>
 *   <li>{@code nodes} — permissions registered by the plugins, with their defaults.</li>
 * </ul>
 */
public class PermCommand {
    private final Command command;
    private final LanguageManager languageManager;
    private final PermissionManager permissionManager;

    public PermCommand(LanguageManager languageManager, PermissionManager permissionManager) {
        this.languageManager = languageManager;
        this.permissionManager = permissionManager;
        command = new SimpleCommandBuilder()
                .name("perm")
                .description("Manages user permission overrides")
                .category("System")
                .aliases("perms", "permission")
                .stringOption("action", "set, unset, list or nodes", true,
                        new OptionChoice<>("set", "set"), new OptionChoice<>("unset", "unset"),
                        new OptionChoice<>("list", "list"), new OptionChoice<>("nodes", "nodes"))
                .stringOption("permission", "Permission node (e.g. music-plugin.volume)", false, this::suggestPermissions)
                .userOption("user", "The user", false)
                .booleanOption("value", "Grant (true) or deny (false)", false)
                .stringOption("scope", "guild (default on Discord) or global", false,
                        new OptionChoice<>("guild", "guild"), new OptionChoice<>("global", "global"))
                .executor(this::execute)
                .build();
    }

    public Command getCommand() {
        return command;
    }

    private CommandResult execute(CommandContext context, Command command) {
        context.setEphemeral(true);
        Locale locale = context.getLocale();
        String guildId = context.getGuild().map(g -> g.getId()).orElse(null);

        // Console has no user; from Discord only an operator (global or of this guild) may manage overrides
        if (context.getUser() != null && !permissionManager.isOperator(context.getUser().getId(), guildId)) {
            context.replyError(languageManager.getString(locale, "commands.perm.not_operator"));
            return CommandResult.error("not an operator");
        }

        String action = context.getOption("action", "nodes");
        String permission = context.<String>getOption("permission").orElse(null);
        String user = context.<User>getOption("user").map(User::getId).orElse(null);
        // Text/console options are positional (action, permission, user...): "perm list <userId>" lands the id in
        // the permission slot, which is what the documented usage promises
        if ("list".equals(action) && user == null && validUser(permission)) {
            user = permission;
            permission = null;
        }
        Boolean value = context.<Boolean>getOption("value").orElse(null);
        String scope = context.getOption("scope", guildId != null ? "guild" : "global");
        boolean guildScope = "guild".equals(scope);

        if (guildScope && guildId == null) {
            context.replyError(languageManager.getString(locale, "commands.perm.guild_scope_needs_guild"));
            return CommandResult.error("guild scope outside a guild");
        }

        switch (action) {
            case "set" -> {
                if (!validUser(user) || permission == null || value == null) {
                    context.replyError(languageManager.getString(locale, "commands.perm.usage_set"));
                    return CommandResult.error("usage");
                }
                if (guildScope) {
                    permissionManager.setPermission(user, guildId, permission, value);
                } else {
                    permissionManager.setPermission(user, permission, value);
                }
                context.replySuccess(languageManager.getString(locale, "commands.perm.set", permission, user, value, scope));
                return CommandResult.success();
            }
            case "unset" -> {
                if (!validUser(user) || permission == null) {
                    context.replyError(languageManager.getString(locale, "commands.perm.usage_unset"));
                    return CommandResult.error("usage");
                }
                boolean removed = guildScope
                        ? permissionManager.unsetPermission(user, guildId, permission)
                        : permissionManager.unsetPermission(user, permission);
                context.replySuccess(languageManager.getString(locale,
                        removed ? "commands.perm.unset" : "commands.perm.unset_nothing", permission, user, scope));
                return CommandResult.success();
            }
            case "list" -> {
                if (!validUser(user)) {
                    context.replyError(languageManager.getString(locale, "commands.perm.usage_list"));
                    return CommandResult.error("usage");
                }
                StringBuilder out = new StringBuilder();
                Map<String, Boolean> global = permissionManager.getUserPermissions(user);
                out.append(languageManager.getString(locale, "commands.perm.list_global", format(global)));
                if (guildId != null) {
                    Map<String, Boolean> guild = permissionManager.getUserGuildPermissions(user, guildId);
                    out.append('\n').append(languageManager.getString(locale, "commands.perm.list_guild", format(guild)));
                }
                String userId = user;
                String effective = permissionManager.getRegisteredPermissions().stream()
                        .map(Permission::getName).sorted()
                        .map(name -> name + "=" + (guildId != null
                                ? permissionManager.hasPermission(userId, guildId, name)
                                : permissionManager.hasPermission(userId, name)))
                        .collect(Collectors.joining(", "));
                out.append('\n').append(languageManager.getString(locale, "commands.perm.list_effective",
                        effective.isEmpty() ? "-" : effective));
                context.replyInfo(out.toString());
                return CommandResult.success();
            }
            default -> {
                String nodes = permissionManager.getRegisteredPermissions().stream()
                        .sorted(Comparator.comparing(Permission::getName))
                        .map(p -> "`" + p.getName() + "` (" + p.getDefault() + ") - " + p.getDescription())
                        .collect(Collectors.joining("\n"));
                context.replyInfo(nodes.isEmpty()
                        ? languageManager.getString(locale, "commands.perm.no_nodes")
                        : languageManager.getString(locale, "commands.perm.nodes", nodes));
                return CommandResult.success();
            }
        }
    }

    /** Registered permission nodes matching what the user typed; the core applies Discord's 25-choice cap. */
    private List<OptionChoice<String>> suggestPermissions(AutocompleteContext context) {
        String typed = context.partial().toLowerCase(Locale.ROOT);
        return permissionManager.getRegisteredPermissions().stream()
                .map(Permission::getName)
                .filter(name -> typed.isEmpty() || name.toLowerCase(Locale.ROOT).contains(typed))
                .sorted()
                .map(name -> new OptionChoice<>(name, name))
                .toList();
    }

    private static boolean validUser(String user) {
        return user != null && user.matches("\\d{5,25}");
    }

    private static String format(Map<String, Boolean> overrides) {
        if (overrides.isEmpty()) {
            return "-";
        }
        return overrides.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
    }
}
