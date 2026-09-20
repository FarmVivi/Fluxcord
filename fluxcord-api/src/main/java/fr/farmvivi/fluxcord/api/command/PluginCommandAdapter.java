package fr.farmvivi.fluxcord.api.command;

import fr.farmvivi.fluxcord.api.plugin.Plugin;
import net.dv8tion.jda.api.entities.Guild;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Adapter for plugin-specific command management.
 * Provides convenient methods for registering and managing commands for a specific plugin.
 */
public class PluginCommandAdapter {
    private final Plugin plugin;
    private final CommandService commandService;

    /**
     * Creates a new plugin command adapter.
     *
     * @param plugin         the plugin
     * @param commandService the command service
     */
    public PluginCommandAdapter(Plugin plugin, CommandService commandService) {
        this.plugin = plugin;
        this.commandService = commandService;
    }

    /**
     * Gets the command service.
     *
     * @return the command service
     */
    public CommandService getCommandService() {
        return commandService;
    }

    /**
     * Registers a command for this plugin.
     *
     * @param command the command to register
     * @return true if the command was registered
     */
    public boolean registerCommand(Command command) {
        return commandService.registerCommand(command, plugin);
    }

    /**
     * Creates and registers a command using a builder.
     *
     * @param builderConsumer consumer to configure the command builder
     * @return true if the command was registered
     */
    public boolean registerCommand(Consumer<CommandBuilder> builderConsumer) {
        return commandService.registerCommand(plugin, builderConsumer);
    }

    /**
     * Gets a command by name.
     *
     * @param name the command name
     * @return the command, or empty if not found
     */
    public Optional<Command> getCommand(String name) {
        return commandService.getRegistry().getCommand(name);
    }

    /**
     * Gets a command by alias.
     *
     * @param alias the command alias
     * @return the command, or empty if not found
     */
    public Optional<Command> getCommandByAlias(String alias) {
        return commandService.getRegistry().getCommandByAlias(alias);
    }

    /**
     * Gets all commands registered by this plugin.
     *
     * @return the commands registered by this plugin
     */
    public List<Command> getCommands() {
        return commandService.getRegistry().getCommands(plugin);
    }

    /**
     * Unregisters a command by name.
     *
     * @param name the command name
     * @return true if the command was unregistered
     */
    public boolean unregisterCommand(String name) {
        Optional<Command> command = commandService.getRegistry().getCommand(name);
        if (command.isEmpty()) {
            return false;
        }

        Optional<Plugin> commandPlugin = commandService.getRegistry().getPlugin(command.get());
        if (commandPlugin.isEmpty() || !commandPlugin.get().equals(plugin)) {
            // Not our command
            return false;
        }

        return commandService.getRegistry().unregister(name);
    }

    /**
     * Unregisters all commands registered by this plugin.
     *
     * @return the number of commands unregistered
     */
    public int unregisterAll() {
        return commandService.getRegistry().unregisterAll(plugin);
    }

    /**
     * Synchronizes all commands with Discord.
     * This updates the slash commands available to users.
     */
    public void synchronizeCommands() {
        commandService.synchronizeCommands();
    }

    /**
     * Synchronizes guild commands with Discord.
     * This updates the slash commands available to users in a specific guild.
     *
     * @param guild the guild
     */
    public void synchronizeGuildCommands(Guild guild) {
        commandService.synchronizeGuildCommands(guild);
    }

    /**
     * Synchronizes global commands with Discord.
     * This updates the slash commands available to users in all guilds.
     */
    public void synchronizeGlobalCommands() {
        commandService.synchronizeGlobalCommands();
    }

    /** @return true while {@code userId} must wait before using {@code commandName} again */
    public boolean isOnCooldown(String userId, String commandName) {
        return commandService.isOnCooldown(userId, commandName);
    }

    /** @return seconds left on the cooldown, 0 when none */
    public int getRemainingCooldown(String userId, String commandName) {
        return commandService.getRemainingCooldown(userId, commandName);
    }

    /**
     * Gets the prefix for text commands.
     *
     * @return the command prefix
     */
    public String getPrefix() {
        return commandService.getPrefix();
    }

    /**
     * Gets the prefix for text commands in a specific guild.
     *
     * @param guildId the guild ID
     * @return the command prefix for the guild
     */
    public String getPrefix(String guildId) {
        return commandService.getPrefix(guildId);
    }
}