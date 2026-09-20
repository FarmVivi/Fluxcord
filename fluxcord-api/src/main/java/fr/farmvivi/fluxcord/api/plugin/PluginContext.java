package fr.farmvivi.fluxcord.api.plugin;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.command.CommandService;
import fr.farmvivi.fluxcord.api.command.PluginCommandAdapter;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageManager;
import fr.farmvivi.fluxcord.api.storage.binary.PluginBinaryStorageAdapter;
import org.slf4j.Logger;

/**
 * Provides access to core functionality and services for plugins.
 */
public interface PluginContext {
    /**
     * Gets the plugin ID as declared in plugin.yml.
     * This typically matches the Maven artifactId.
     *
     * @return the plugin ID
     */
    String getPluginId();

    /**
     * Gets the plugin name as declared in plugin.yml.
     * This typically matches the Maven name.
     *
     * @return the plugin name
     */
    String getPluginName();

    /**
     * Gets the plugin version as declared in plugin.yml.
     * This typically matches the Maven version.
     *
     * @return the plugin version
     */
    String getPluginVersion();

    /**
     * Gets the logger for the plugin.
     *
     * @return the logger instance
     */
    Logger getLogger();

    /**
     * Gets the event manager for registering and handling events.
     *
     * @return the event manager
     */
    EventManager getEventManager();

    /**
     * Gets the Discord API for interacting with Discord.
     *
     * @return the Discord API
     */
    DiscordAPI getDiscordAPI();

    /**
     * Gets the configuration for the plugin.
     *
     * @return the configuration
     */
    Configuration getConfiguration();

    /**
     * Gets the data folder path for the plugin to store its files.
     *
     * @return the plugin's data folder path
     */
    String getDataFolder();

    /**
     * Gets the plugin loader, which can be used to access other plugins.
     *
     * @return the plugin loader
     */
    PluginLoader getPluginLoader();

    /**
     * Gets the language manager for internationalization.
     *
     * @return the language manager
     */
    LanguageManager getLanguageManager();

    /**
     * Gets the data storage manager for persistent data.
     *
     * @return the data storage manager
     */
    DataStorageManager getDataStorageManager();

    /**
     * Gets the binary storage manager for large file storage.
     *
     * @return the binary storage manager
     */
    BinaryStorageManager getBinaryStorageManager();

    /**
     * Gets the permission manager for registering and checking permissions.
     *
     * @return the permission manager
     */
    PermissionManager getPermissionManager();

    /**
     * Gets the audio service for managing audio connections.
     *
     * @return the audio service, or null if audio is disabled
     */
    AudioService getAudioService();

    /**
     * Gets the command service for registering and managing commands.
     *
     * @return the command service
     */
    CommandService getCommandService();

    // ---- plugin-scoped views (P5) ----------------------------------------------------------------------------
    // The same services, namespaced by this plugin's id: commands/permissions/languages are registered on behalf
    // of the plugin (and released with it), storage keys and paths are prefixed. Prefer these over the shared
    // managers above; AbstractPlugin exposes them as getPlugin*() for convenience.

    /** @return this plugin's command registration façade */
    PluginCommandAdapter getCommands();

    /** @return this plugin's permission registration façade */
    PluginPermissionAdapter getPermissions();

    /** @return this plugin's language namespace ({@code <id>:<key>}) */
    PluginLanguageAdapter getLanguage();

    /** @return this plugin's data storage (keys prefixed with {@code <id>.}) */
    PluginDataStorageAdapter getStorage();

    /** @return this plugin's binary storage (paths under {@code <id>/}) */
    PluginBinaryStorageAdapter getBinaryStorage();
}