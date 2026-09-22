package fr.farmvivi.fluxcord.examples.commands;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;
import net.dv8tion.jda.api.EmbedBuilder;

import java.awt.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Example plugin demonstrating comprehensive command system features.
 * <p>
 * This plugin showcases:
 * - Slash command registration and handling
 * - Command permissions and cooldowns
 * - Command arguments and validation
 * - Embed responses and formatting
 * - Error handling and user feedback
 * - Configuration-driven command behavior
 * - Internationalization in commands
 * - Command statistics and monitoring
 */
public class CommandExamplePlugin extends AbstractPlugin {

    static final String USE_PERMISSION = "commandexample.use";
    static final String ADMIN_PERMISSION = "commandexample.admin";

    // Command execution statistics
    private final AtomicLong commandExecutionCount = new AtomicLong(0);
    private long pluginStartTime;

    @Override
    public void onEnable() {
        super.onEnable();

        // Check if plugin is enabled in config
        if (!getConfiguration().getBoolean("enabled", false)) {
            logger.info(getLanguage().getString("status.disabled"));
            return;
        }

        // Record start time for uptime calculation
        pluginStartTime = System.currentTimeMillis();

        // Register permissions
        registerPermissions();

        // Register commands
        registerCommands();

        logger.info(getLanguage().getString("status.enabled"));
        logger.info(getLanguage().getString("status.ready"));
    }

    @Override
    public void onDisable() {
        super.onDisable();
        logger.info("Command Example Plugin disabled. Total commands executed: {}",
                commandExecutionCount.get());
    }

    /**
     * Registers plugin-specific permissions.
     */
    private void registerPermissions() {
        getPermissions().registerPermission(new SimplePermission(
                USE_PERMISSION,
                "Allows usage of basic command examples",
                PermissionDefault.TRUE));
        getPermissions().registerPermission(new SimplePermission(
                ADMIN_PERMISSION,
                "Allows usage of administrative commands",
                PermissionDefault.OP));

        logger.debug("Permissions registered for command examples");
    }

    /**
     * Registers all example commands.
     */
    private void registerCommands() {
        int commandsRegistered = 0;

        // Ping command - simple response
        if (getConfiguration().getBoolean("commands.ping.enabled", true)) {
            registerPingCommand();
            commandsRegistered++;
        }

        // Echo command - with arguments
        if (getConfiguration().getBoolean("commands.echo.enabled", true)) {
            registerEchoCommand();
            commandsRegistered++;
        }

        // Info command - with embeds
        if (getConfiguration().getBoolean("commands.info.enabled", true)) {
            registerInfoCommand();
            commandsRegistered++;
        }

        // Admin command - with permissions
        if (getConfiguration().getBoolean("commands.admin.enabled", true)) {
            registerAdminCommand();
            commandsRegistered++;
        }

        logger.info(getLanguage().getString("status.commands_loaded", commandsRegistered));
    }

    /**
     * Registers the ping command - demonstrates basic command functionality.
     */
    private void registerPingCommand() {
        getCommands().registerCommand(builder -> builder
                .name("ping")
                .description(getLanguage().getString("ping.description"))
                // The core enforces the cooldown and answers commands.messages.cooldown itself
                .cooldown(getConfiguration().getInt("commands.ping.cooldown", 0))
                .executor(this::executePingCommand));
    }

    /**
     * Registers the echo command - demonstrates argument handling.
     */
    private void registerEchoCommand() {
        getCommands().registerCommand(builder -> builder
                .name("echo")
                .description(getLanguage().getString("echo.description"))
                .stringOption("message", "Message to echo back", true)
                .cooldown(getConfiguration().getInt("commands.echo.cooldown", 0))
                .executor(this::executeEchoCommand));
    }

    /**
     * Registers the info command - demonstrates embed responses.
     */
    private void registerInfoCommand() {
        getCommands().registerCommand(builder -> builder
                .name("info")
                .description(getLanguage().getString("info.description"))
                .executor(this::executeInfoCommand));
    }

    /**
     * Registers the admin command - demonstrates permission checks.
     */
    private void registerAdminCommand() {
        getCommands().registerCommand(builder -> builder
                .name("admin")
                .description(getLanguage().getString("admin.description"))
                // Declaring the permission is enough: the core refuses the command before the
                // executor runs, with the right message in the caller's language.
                .permission(ADMIN_PERMISSION)
                .executor(this::executeAdminCommand));
    }

    /**
     * Executes the ping command.
     */
    private CommandResult executePingCommand(CommandContext context, Command command) {
        commandExecutionCount.incrementAndGet();

        // Get response message
        String response = getLanguage().getString("ping.response");

        // Send response
        if (getConfiguration().getBoolean("responses.use_embeds", true)) {
            context.replyEmbed(embed("🏓 Ping", response, Color.GREEN));
        } else {
            context.reply(response);
        }

        if (getConfiguration().getBoolean("debug.log_command_usage", true)) {
            logger.info("Ping command executed by: {}", context.getUser().getName());
        }

        return CommandResult.success();
    }

    /**
     * Executes the echo command.
     */
    private CommandResult executeEchoCommand(CommandContext context, Command command) {
        String message = context.getOption("message", "");

        if (message.isEmpty()) {
            String errorMsg = getLanguage().getString("echo.no_message");
            context.reply(errorMsg);
            return CommandResult.error("No message provided");
        }

        // Check message length
        int maxLength = getConfiguration().getInt("commands.echo.max_length", 200);
        if (message.length() > maxLength) {
            String errorMsg = getLanguage().getString("echo.too_long", maxLength);
            context.reply(errorMsg);
            return CommandResult.error("Message too long");
        }

        commandExecutionCount.incrementAndGet();
        String response = getLanguage().getString("echo.response", message);

        // Send response
        if (getConfiguration().getBoolean("responses.use_embeds", true)) {
            context.replyEmbed(embed("📢 Echo", response, Color.BLUE));
        } else {
            context.reply(response);
        }

        return CommandResult.success();
    }

    /**
     * Executes the info command.
     */
    private CommandResult executeInfoCommand(CommandContext context, Command command) {
        // Increment statistics
        commandExecutionCount.incrementAndGet();

        // Calculate uptime
        long uptimeMs = System.currentTimeMillis() - pluginStartTime;
        String uptime = formatUptime(uptimeMs);

        // Get memory usage
        Runtime runtime = Runtime.getRuntime();
        long memoryUsed = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;

        // A field with an empty value renders badly on Discord, and these lines already carry
        // their own label ("Version: {0}"), so they belong in the description.
        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(getLanguage().getString("info.title"))
                .setColor(getEmbedColor())
                .setDescription(String.join("\n",
                        "📌 " + getLanguage().getString("info.version", getVersion()),
                        "⏰ " + getLanguage().getString("info.uptime", uptime),
                        "📊 " + getLanguage().getString("info.commands_executed", commandExecutionCount.get()),
                        "💾 " + getLanguage().getString("info.memory_usage", memoryUsed)))
                .setFooter("Command Example Plugin", null)
                .setTimestamp(java.time.Instant.now());

        if (getConfiguration().getBoolean("commands.info.show_detailed", false)) {
            embed.addField("🔧 Configuration", "Enabled: " + getConfiguration().getBoolean("enabled", false), false);
            embed.addField("🌐 Language", getLanguage().getDefaultLocale().toString(), false);
        }

        context.replyEmbed(embed);
        return CommandResult.success();
    }

    /**
     * Executes the admin command.
     */
    private CommandResult executeAdminCommand(CommandContext context, Command command) {
        // No permission check here: the command declares .permission(ADMIN_PERMISSION), so the core
        // already refused the call in the caller's language before reaching this executor.
        commandExecutionCount.incrementAndGet();

        context.replyEmbed(embed("🔧 Admin", getLanguage().getString("admin.response"), Color.ORANGE));
        logger.info("Admin command executed by: {}", context.getUser().getName());

        return CommandResult.success();
    }

    /**
     * Creates a simple embed with title, description and color.
     */
    private EmbedBuilder embed(String title, String description, Color color) {
        return new EmbedBuilder()
                .setTitle(title)
                .setDescription(description)
                .setColor(color)
                .setTimestamp(java.time.Instant.now());
    }

    /**
     * Gets the embed color from configuration.
     */
    private Color getEmbedColor() {
        String colorHex = getConfiguration().getString("responses.embed_color", "#7289DA");
        try {
            return Color.decode(colorHex);
        } catch (NumberFormatException e) {
            return Color.BLUE;
        }
    }

    /**
     * Formats uptime in a human-readable format.
     */
    private String formatUptime(long uptimeMs) {
        long seconds = uptimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours % 24, minutes % 60);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%ds", seconds);
        }
    }

    /**
     * Simple permission implementation.
     */
    private record SimplePermission(String name, String description,
                                    PermissionDefault defaultValue) implements Permission {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return description;
        }

        @Override
        public PermissionDefault getDefault() {
            return defaultValue;
        }
    }
}
