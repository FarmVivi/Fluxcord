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

import net.dv8tion.jda.api.entities.Guild;
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
 *
 * <p>Each action lives in its own method: as one switch it reached a cognitive complexity of 32, which for
 * a command that grants and revokes permissions is exactly where nobody wants to be guessing.
 */
public class PermCommand {

    private static final String ACTION_SET = "set";
    private static final String ACTION_UNSET = "unset";
    private static final String ACTION_LIST = "list";
    private static final String ACTION_NODES = "nodes";

    private static final String SCOPE_GUILD = "guild";
    private static final String SCOPE_GLOBAL = "global";

    private static final String OPTION_ACTION = "action";
    private static final String OPTION_PERMISSION = "permission";
    private static final String OPTION_USER = "user";
    private static final String OPTION_VALUE = "value";
    private static final String OPTION_SCOPE = "scope";

    /** The {@link CommandResult} reason used whenever the arguments do not make a usable request. */
    private static final String REASON_USAGE = "usage";

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
                .aliases("perms", OPTION_PERMISSION)
                .stringOption(OPTION_ACTION, "set, unset, list or nodes", true,
                        new OptionChoice<>(ACTION_SET, ACTION_SET), new OptionChoice<>(ACTION_UNSET, ACTION_UNSET),
                        new OptionChoice<>(ACTION_LIST, ACTION_LIST), new OptionChoice<>(ACTION_NODES, ACTION_NODES))
                .stringOption(OPTION_PERMISSION, "Permission node (e.g. music-plugin.volume)", false,
                        this::suggestPermissions)
                .userOption(OPTION_USER, "The user", false)
                .booleanOption(OPTION_VALUE, "Grant (true) or deny (false)", false)
                .stringOption(OPTION_SCOPE, "guild (default on Discord) or global", false,
                        new OptionChoice<>(SCOPE_GUILD, SCOPE_GUILD), new OptionChoice<>(SCOPE_GLOBAL, SCOPE_GLOBAL))
                .executor(this::execute)
                .build();
    }

    public Command getCommand() {
        return command;
    }

    private CommandResult execute(CommandContext context, Command command) {
        context.setEphemeral(true);
        Locale locale = context.getLocale();
        String guildId = context.getGuild().map(Guild::getId).orElse(null);

        // The console has no user; from Discord only an operator (global or of this guild) may manage overrides.
        if (context.getUser() != null && !permissionManager.isOperator(context.getUser().getId(), guildId)) {
            context.replyError(languageManager.getString(locale, "commands.perm.not_operator"));
            return CommandResult.error("not an operator");
        }

        Request request = Request.parse(context, guildId);
        if (request.guildScope() && guildId == null) {
            context.replyError(languageManager.getString(locale, "commands.perm.guild_scope_needs_guild"));
            return CommandResult.error("guild scope outside a guild");
        }

        return switch (request.action()) {
            case ACTION_SET -> set(context, locale, guildId, request);
            case ACTION_UNSET -> unset(context, locale, guildId, request);
            case ACTION_LIST -> list(context, locale, guildId, request);
            default -> nodes(context, locale);
        };
    }

    private CommandResult set(CommandContext context, Locale locale, String guildId, Request request) {
        if (!validUser(request.user()) || request.permission() == null || request.value() == null) {
            context.replyError(languageManager.getString(locale, "commands.perm.usage_set"));
            return CommandResult.error(REASON_USAGE);
        }
        if (request.guildScope()) {
            permissionManager.setPermission(request.user(), guildId, request.permission(), request.value());
        } else {
            permissionManager.setPermission(request.user(), request.permission(), request.value());
        }
        context.replySuccess(languageManager.getString(locale, "commands.perm.set",
                request.permission(), request.user(), request.value(), request.scope()));
        return CommandResult.success();
    }

    private CommandResult unset(CommandContext context, Locale locale, String guildId, Request request) {
        if (!validUser(request.user()) || request.permission() == null) {
            context.replyError(languageManager.getString(locale, "commands.perm.usage_unset"));
            return CommandResult.error(REASON_USAGE);
        }
        boolean removed = request.guildScope()
                ? permissionManager.unsetPermission(request.user(), guildId, request.permission())
                : permissionManager.unsetPermission(request.user(), request.permission());
        context.replySuccess(languageManager.getString(locale,
                removed ? "commands.perm.unset" : "commands.perm.unset_nothing",
                request.permission(), request.user(), request.scope()));
        return CommandResult.success();
    }

    private CommandResult list(CommandContext context, Locale locale, String guildId, Request request) {
        String user = request.user();
        if (!validUser(user)) {
            context.replyError(languageManager.getString(locale, "commands.perm.usage_list"));
            return CommandResult.error(REASON_USAGE);
        }
        StringBuilder out = new StringBuilder();
        out.append(languageManager.getString(locale, "commands.perm.list_global",
                format(permissionManager.getUserPermissions(user))));
        if (guildId != null) {
            out.append('\n').append(languageManager.getString(locale, "commands.perm.list_guild",
                    format(permissionManager.getUserGuildPermissions(user, guildId))));
        }
        String effective = effectiveValues(user, guildId);
        out.append('\n').append(languageManager.getString(locale, "commands.perm.list_effective",
                effective.isEmpty() ? "-" : effective));
        context.replyInfo(out.toString());
        return CommandResult.success();
    }

    private CommandResult nodes(CommandContext context, Locale locale) {
        String nodes = permissionManager.getRegisteredPermissions().stream()
                .sorted(Comparator.comparing(Permission::getName))
                .map(p -> "`" + p.getName() + "` (" + p.getDefault() + ") - " + p.getDescription())
                .collect(Collectors.joining("\n"));
        context.replyInfo(nodes.isEmpty()
                ? languageManager.getString(locale, "commands.perm.no_nodes")
                : languageManager.getString(locale, "commands.perm.nodes", nodes));
        return CommandResult.success();
    }

    /** Every registered node with the value this user would actually get. */
    private String effectiveValues(String user, String guildId) {
        return permissionManager.getRegisteredPermissions().stream()
                .map(Permission::getName).sorted()
                .map(name -> name + "=" + (guildId != null
                        ? permissionManager.hasPermission(user, guildId, name)
                        : permissionManager.hasPermission(user, name)))
                .collect(Collectors.joining(", "));
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

    /**
     * The arguments, once read and normalised.
     *
     * @param action      what to do
     * @param permission  the node, or null
     * @param user        the target user id, or null
     * @param value       the override to store, or null
     * @param scope       {@code guild} or {@code global}, as the reply reports it
     * @param guildScope  whether {@code scope} is the guild one
     */
    private record Request(String action, String permission, String user, Boolean value,
                           String scope, boolean guildScope) {

        static Request parse(CommandContext context, String guildId) {
            String action = context.getOption(OPTION_ACTION, ACTION_NODES);
            String permission = context.<String>getOption(OPTION_PERMISSION).orElse(null);
            String user = context.<User>getOption(OPTION_USER).map(User::getId).orElse(null);

            // Text and console options are positional (action, permission, user...), so "perm list <userId>"
            // lands the id in the permission slot - which is what the documented usage promises.
            if (ACTION_LIST.equals(action) && user == null && validUser(permission)) {
                user = permission;
                permission = null;
            }
            String scope = context.getOption(OPTION_SCOPE, guildId != null ? SCOPE_GUILD : SCOPE_GLOBAL);
            return new Request(action, permission, user,
                    context.<Boolean>getOption(OPTION_VALUE).orElse(null), scope, SCOPE_GUILD.equals(scope));
        }
    }
}
