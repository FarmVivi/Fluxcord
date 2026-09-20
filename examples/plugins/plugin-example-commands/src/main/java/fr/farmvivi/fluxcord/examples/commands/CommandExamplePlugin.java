package fr.farmvivi.fluxcord.examples.commands;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

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
        // Basic command usage permission
        getPermissions().registerPermission(new SimplePermission(
                "commandexample.use",
                "Allows usage of basic command examples",
                PermissionDefault.TRUE));

        // Admin command permission
        getPermissions().registerPermission(new SimplePermission(
                "commandexample.admin",
                "Allows usage of administrative commands",
                PermissionDefault.OP));

        // Cooldown bypass permission
        getPermissions().registerPermission(new SimplePermission(
                "commandexample.cooldown.bypass",
                "Allows bypassing command cooldowns",
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
        getCommands().registerCommand(builder -> {
            builder.name("ping")
                    .description(getLanguage().getString("ping.description"))
                    .executor(this::executePingCommand);
        });
    }

    /**
     * Registers the echo command - demonstrates argument handling.
     */
    private void registerEchoCommand() {
        getCommands().registerCommand(builder -> {
            builder.name("echo")
                    .description(getLanguage().getString("echo.description"))
                    .stringOption("message", "Message to echo back", true)
                    .executor(this::executeEchoCommand);
        });
    }

    /**
     * Registers the info command - demonstrates embed responses.
     */
    private void registerInfoCommand() {
        getCommands().registerCommand(builder -> {
            builder.name("info")
                    .description(getLanguage().getString("info.description"))
                    .executor(this::executeInfoCommand);
        });
    }

    /**
     * Registers the admin command - demonstrates permission checks.
     */
    private void registerAdminCommand() {
        getCommands().registerCommand(builder -> {
            builder.name("admin")
                    .description(getLanguage().getString("admin.description"))
                    .executor(this::executeAdminCommand);
        });
    }

    /**
     * Executes the ping command.
     */
    private CommandResult executePingCommand(CommandContext context, Command command) {
        // Check cooldown
        if (isOnCooldown(context, "ping")) {
            return CommandResult.error("On cooldown");
        }

        // Increment statistics
        commandExecutionCount.incrementAndGet();

        // Get response message
        String response = getLanguage().getString("ping.response");

        // Send response
        if (getConfiguration().getBoolean("responses.use_embeds", true)) {
            MessageEmbed embed = createSimpleEmbed("🏓 Ping", response, Color.GREEN);
            context.replyEmbed(new EmbedBuilder(embed));
        } else {
            context.reply(response);
        }

        // Log command usage if enabled
        if (getConfiguration().getBoolean("debug.log_command_usage", true)) {
            logger.info("Ping command executed by: {}", context.getUser().getAsTag());
        }

        return CommandResult.success();
    }

    /**
     * Executes the echo command.
     */
    private CommandResult executeEchoCommand(CommandContext context, Command command) {
        // Check cooldown
        if (isOnCooldown(context, "echo")) {
            return CommandResult.error("On cooldown");
        }

        // Get message argument
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

        // Increment statistics
        commandExecutionCount.incrementAndGet();

        // Format response
        String response = getLanguage().getString("echo.response", message);

        // Send response
        if (getConfiguration().getBoolean("responses.use_embeds", true)) {
            MessageEmbed embed = createSimpleEmbed("📢 Echo", response, Color.BLUE);
            context.replyEmbed(new EmbedBuilder(embed));
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

        // Create detailed embed
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle(getLanguage().getString("info.title"));
        embed.setColor(getEmbedColor());

        embed.addField("📌 " + getLanguage().getString("info.version", getVersion()), "", false);
        embed.addField("⏰ " + getLanguage().getString("info.uptime", uptime), "", false);
        embed.addField("📊 " + getLanguage().getString("info.commands_executed", commandExecutionCount.get()), "", false);
        embed.addField("💾 " + getLanguage().getString("info.memory_usage", memoryUsed), "", false);

        if (getConfiguration().getBoolean("commands.info.show_detailed", false)) {
            embed.addField("🔧 Configuration", "Enabled: " + getConfiguration().getBoolean("enabled", false), false);
            embed.addField("🌐 Language", getLanguage().getDefaultLocale().toString(), false);
        }

        embed.setFooter("Command Example Plugin", null);
        embed.setTimestamp(java.time.Instant.now());

        context.replyEmbed(embed);
        return CommandResult.success();
    }

    /**
     * Executes the admin command.
     */
    private CommandResult executeAdminCommand(CommandContext context, Command command) {
        // Check permissions
        if (!getPermissions().hasPermission(context.getUser().getId(), "commandexample.admin")) {
            String errorMsg = getLanguage().getString("admin.no_permission");
            context.reply(errorMsg);
            return CommandResult.error("No permission");
        }

        // Increment statistics
        commandExecutionCount.incrementAndGet();

        // Execute admin action
        String response = getLanguage().getString("admin.response");

        // Send response
        MessageEmbed embed = createSimpleEmbed("🔧 Admin", response, Color.ORANGE);
        context.replyEmbed(new EmbedBuilder(embed));

        // Log admin command usage
        logger.info("Admin command executed by: {}", context.getUser().getAsTag());

        return CommandResult.success();
    }

    /**
     * Checks if a command is on cooldown for the user.
     */
    private boolean isOnCooldown(CommandContext context, String commandName) {
        // Skip cooldown check for bypass permission
        if (getPermissions().hasPermission(context.getUser().getId(), "commandexample.cooldown.bypass")) {
            return false;
        }

        // Check command-specific cooldown
        int cooldown = getConfiguration().getInt("commands." + commandName + ".cooldown", 0);
        if (cooldown > 0 && getCommands().isOnCooldown(context.getUser().getId(), commandName)) {
            int remaining = getCommands().getRemainingCooldown(context.getUser().getId(), commandName);
            String cooldownMsg = getLanguage().getString("general.cooldown", remaining);
            context.reply(cooldownMsg);
            return true;
        }

        return false;
    }

    /**
     * Creates a simple embed with title, description, and color.
     */
    private MessageEmbed createSimpleEmbed(String title, String description, Color color) {
        EmbedBuilder embed = new EmbedBuilder();
        embed.setTitle(title);
        embed.setDescription(description);
        embed.setColor(color);
        embed.setTimestamp(java.time.Instant.now());
        return embed.build();
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
