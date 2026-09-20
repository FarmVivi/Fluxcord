package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.command.event.CommandExecuteEvent;
import fr.farmvivi.fluxcord.api.command.event.CommandExecutedEvent;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * The execution pipeline of a parsed command: gating (service enabled → command enabled → guild-only →
 * permission → cooldown → cancellable {@link CommandExecuteEvent}), the call itself, cooldown bookkeeping,
 * metrics and the {@link CommandExecutedEvent}. Console invocations ({@code context.getUser() == null}) are
 * trusted: they skip the guild, permission and cooldown checks.
 * <p>
 * Refusals return an error {@link CommandResult} whose message is already localised; they are not counted as
 * executions.
 */
public class CommandExecutor {
    private static final Logger logger = LoggerFactory.getLogger(CommandExecutor.class);
    static final String CONSOLE_USER = "CONSOLE";

    private final EventManager eventManager;
    private final LanguageManager languageManager;
    private final PermissionManager permissionManager;
    private final BooleanSupplier serviceEnabled;

    private final AtomicLong executions = new AtomicLong();
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong totalExecutionTimeNs = new AtomicLong();
    /** user id → command name → cooldown expiry (epoch millis) */
    private final Map<String, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    public CommandExecutor(EventManager eventManager, LanguageManager languageManager,
                           PermissionManager permissionManager, BooleanSupplier serviceEnabled) {
        this.eventManager = eventManager;
        this.languageManager = languageManager;
        this.permissionManager = permissionManager;
        this.serviceEnabled = serviceEnabled;
    }

    public CommandResult execute(Command command, CommandContext context) {
        Locale locale = context.getLocale();

        if (!serviceEnabled.getAsBoolean()) {
            return refuse(locale, "commands.messages.system_disabled");
        }
        if (!command.isEnabled()) {
            return refuse(locale, "commands.messages.disabled");
        }

        boolean console = context.getUser() == null;
        String userId = console ? CONSOLE_USER : context.getUser().getId();

        if (!console) {
            if (isGuildOnly(command) && !context.isFromGuild()) {
                return refuse(locale, "commands.messages.guild_only");
            }
            String permission = effectivePermission(command);
            if (permission != null) {
                String guildId = context.getGuild().map(Guild::getId).orElse(null);
                if (!hasPermission(userId, guildId, permission)) {
                    return refuse(locale, "commands.messages.permission_error",
                            "You don't have permission to use this command");
                }
            }
            if (isOnCooldown(userId, command.getFullName())) {
                return refuse(locale, "commands.messages.cooldown", getRemainingCooldown(userId, command.getFullName()));
            }
        }

        CommandExecuteEvent executeEvent = new CommandExecuteEvent(command, context);
        eventManager.fireEvent(executeEvent);
        if (executeEvent.isCancelled()) {
            return refuse(locale, "commands.messages.execution_cancelled");
        }

        long start = System.nanoTime();
        CommandResult result;
        try {
            result = command.execute(context);
            if (command.getCooldown() > 0 && !console) {
                applyCooldown(userId, command.getFullName(), command.getCooldown());
            }
        } catch (Exception e) {
            logger.error("Error executing command {}: {}", command.getName(), e.getMessage(), e);
            result = CommandResult.error(languageManager.getString(locale, "commands.messages.execution_error", e.getMessage()));
        }
        long elapsedNs = System.nanoTime() - start;

        executions.incrementAndGet();
        totalExecutionTimeNs.addAndGet(elapsedNs);
        (result.isSuccess() ? successes : failures).incrementAndGet();

        eventManager.fireEvent(new CommandExecutedEvent(command, context, result, elapsedNs / 1_000_000));
        return result;
    }

    /** A subcommand without a permission of its own inherits the closest ancestor's. */
    static String effectivePermission(Command command) {
        for (Command c = command; c != null; c = c.getParent()) {
            if (c.getPermission() != null) {
                return c.getPermission();
            }
        }
        return null;
    }

    /** A subcommand is guild-only when it or any ancestor is. */
    static boolean isGuildOnly(Command command) {
        for (Command c = command; c != null; c = c.getParent()) {
            if (c.isGuildOnly()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPermission(String userId, String guildId, String permission) {
        try {
            return permissionManager.hasPermission(userId, guildId, permission);
        } catch (Exception e) {
            logger.error("Permission check failed for {} on {}: {}", userId, permission, e.getMessage(), e);
            return false;
        }
    }

    private CommandResult refuse(Locale locale, String key, Object... args) {
        return CommandResult.error(languageManager.getString(locale, key, args));
    }

    // ---- cooldowns -------------------------------------------------------------------------------------------

    public boolean isOnCooldown(String userId, String commandName) {
        return remainingMillis(userId, commandName) > 0;
    }

    /** @return whole seconds left, 0 when not on cooldown */
    public int getRemainingCooldown(String userId, String commandName) {
        long remaining = remainingMillis(userId, commandName);
        return remaining > 0 ? (int) (remaining / 1000) : 0;
    }

    private long remainingMillis(String userId, String commandName) {
        Map<String, Long> userCooldowns = cooldowns.get(userId);
        Long expiry = userCooldowns == null ? null : userCooldowns.get(commandName);
        return expiry == null ? 0 : expiry - System.currentTimeMillis();
    }

    private void applyCooldown(String userId, String commandName, int cooldownSeconds) {
        cooldowns.computeIfAbsent(userId, k -> new ConcurrentHashMap<>())
                .put(commandName, System.currentTimeMillis() + cooldownSeconds * 1000L);
    }

    // ---- metrics ---------------------------------------------------------------------------------------------

    public long getExecutionCount() {
        return executions.get();
    }

    public long getSuccessCount() {
        return successes.get();
    }

    public long getFailureCount() {
        return failures.get();
    }

    public double getAverageExecutionTimeMs() {
        long count = executions.get();
        return count == 0 ? 0 : (double) totalExecutionTimeNs.get() / (count * 1_000_000);
    }
}
