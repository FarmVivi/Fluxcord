package fr.farmvivi.fluxcord.api.plugin;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.command.PluginCommandAdapter;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
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
     * Gets the audio service for managing audio connections.
     *
     * @return the audio service, or null if audio is disabled
     */
    AudioService getAudioService();

    // ---- plugin-scoped views ----------------------------------------------------------------------------------
    // Commands, permissions and translations are registered on behalf of this plugin (and released with it);
    // storage keys and paths are namespaced by its id. The shared managers are reachable through the adapters
    // (e.g. PluginCommandAdapter.getCommandService()) when a plugin really needs to cross its own scope.

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