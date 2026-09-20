package com.example.plugin.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.command.CommandResult;
import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;

/**
 * Example command demonstrating basic command functionality.
 * This command can be enabled/disabled via configuration.
 */
public class ExampleCommand {

    private final AbstractPlugin plugin;

    public ExampleCommand(AbstractPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Execute the example command.
     * Demonstrates basic command handling with i18n support.
     */
    public CommandResult execute(CommandContext context) {
        // Check if command is enabled in config
        if (!plugin.getConfiguration().getBoolean("commands.enabled", true)) {
            return CommandResult.error("Commands are disabled");
        }

        // Check permissions (example)
        if (!plugin.getPermissions().hasPermission(
                context.getUser().getId(), "template.use")) {
            String message = plugin.getLanguage()
                    .getString("errors.no_permission");
            context.reply(message);
            return CommandResult.error("No permission");
        }

        // Get localized message
        String response = plugin.getLanguage()
                .getString("messages.example_message");

        context.reply(response);
        plugin.getLogger().info("Example command executed by user: {}",
                context.getUser().getAsTag());

        return CommandResult.success();
    }
}