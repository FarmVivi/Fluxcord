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

import java.io.File;

/**
 * Convenience base class for plugins.
 * Provides common functionality and access to core services.
 */
public abstract class AbstractPlugin implements Plugin {
    // Core services
    protected PluginContext context;
    protected Logger logger;
    protected EventManager eventManager;
    protected DiscordAPI discordAPI;
    protected Configuration configuration;
    protected String dataFolder;
    protected LanguageManager languageManager;
    protected DataStorageManager dataStorageManager;
    protected BinaryStorageManager binaryStorageManager;
    protected PermissionManager permissionManager;
    protected AudioService audioService;
    protected CommandService commandService;

    // Plugin-specific managers
    protected PluginPermissionAdapter pluginPermissionAdapter;
    protected PluginLanguageAdapter pluginLanguageAdapter;
    protected PluginDataStorageAdapter pluginDataStorageAdapter;
    protected PluginBinaryStorageAdapter pluginBinaryStorageAdapter;
    protected PluginCommandAdapter pluginCommandAdapter;

    // Plugin state
    private PluginLifecycle lifecycle = PluginLifecycle.DISCOVERED;

    @Override
    public String getId() {
        return context.getPluginId();
    }

    @Override
    public String getName() {
        return context.getPluginName();
    }

    @Override
    public String getVersion() {
        return context.getPluginVersion();
    }

    @Override
    public void onLoad(PluginContext context) {
        this.context = context;
        this.logger = context.getLogger();
        this.eventManager = context.getEventManager();
        this.discordAPI = context.getDiscordAPI();
        this.configuration = context.getConfiguration();
        this.dataFolder = context.getDataFolder();
        this.languageManager = context.getLanguageManager();
        this.dataStorageManager = context.getDataStorageManager();
        this.binaryStorageManager = context.getBinaryStorageManager();
        this.permissionManager = context.getPermissionManager();
        this.audioService = context.getAudioService();
        this.commandService = context.getCommandService();

        // Plugin-scoped views come from the context (P5); a context that does not provide them (a bare mock in
        // a unit test) gets them built here from the shared managers
        this.pluginLanguageAdapter = context.getLanguage() != null
                ? context.getLanguage() : new PluginLanguageAdapter(this, languageManager);
        this.pluginPermissionAdapter = context.getPermissions() != null
                ? context.getPermissions() : new PluginPermissionAdapter(this, permissionManager, languageManager);
        this.pluginDataStorageAdapter = context.getStorage() != null
                ? context.getStorage() : new PluginDataStorageAdapter(this, dataStorageManager);
        this.pluginBinaryStorageAdapter = context.getBinaryStorage() != null
                ? context.getBinaryStorage() : new PluginBinaryStorageAdapter(this, binaryStorageManager);
        this.pluginCommandAdapter = context.getCommands() != null
                ? context.getCommands() : new PluginCommandAdapter(this, commandService);

        // Create data directory if it doesn't exist
        File dataDir = new File(dataFolder);
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            logger.warn("Failed to create data directory for plugin: {}", getName());
        }

        logger.info("Loading {} v{}", getName(), getVersion());
    }

    @Override
    public void onPreEnable() {
        logger.info("Pre-enabling {} v{}", getName(), getVersion());
    }

    @Override
    public void onEnable() {
        logger.info("Enabling {} v{}", getName(), getVersion());
    }

    @Override
    public void onPostEnable() {
        logger.info("Post-enabling {} v{}", getName(), getVersion());
    }

    @Override
    public void onPreDisable() {
        logger.info("Pre-disabling {} v{}", getName(), getVersion());
    }

    @Override
    public void onDisable() {
        logger.info("Disabling {} v{}", getName(), getVersion());

        // Automatically unregister all event handlers
        if (eventManager != null) {
            eventManager.unregisterListener(this);
            eventManager.unregisterAll(this);
        }
    }

    @Override
    public void onPostDisable() {
        logger.info("Post-disabling {} v{}", getName(), getVersion());
    }

    @Override
    public PluginLifecycle getLifecycle() {
        return lifecycle;
    }

    @Override
    public void setLifecycle(PluginLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * Gets the plugin context.
     *
     * @return the plugin context
     */
    public PluginContext getContext() {
        return context;
    }

    /**
     * Convenience accessor for the plugin configuration.
     *
     * @return configuration instance
     */
    public Configuration getConfiguration() {
        return configuration;
    }

    /**
     * Convenience accessor for the plugin data folder path.
     *
     * @return absolute path to plugin data folder
     */
    public String getDataFolder() {
        return dataFolder;
    }

    /**
     * Gets the plugin-specific permission manager.
     * This provides convenient methods for registering and checking permissions
     * specifically for this plugin.
     *
     * @return the plugin permission manager
     */
    public PluginPermissionAdapter getPluginPermissionManager() {
        return pluginPermissionAdapter;
    }

    /**
     * Gets the plugin-specific language manager.
     * This provides convenient methods for translating strings specifically for this plugin.
     *
     * @return the plugin language manager
     */
    public PluginLanguageAdapter getPluginLanguageManager() {
        return pluginLanguageAdapter;
    }

    /**
     * Gets the plugin-specific data storage adapter.
     * This provides convenient methods for storing and retrieving data
     * specifically for this plugin with automatic namespacing.
     *
     * @return the plugin data storage adapter
     */
    public PluginDataStorageAdapter getPluginDataStorage() {
        return pluginDataStorageAdapter;
    }

    /**
     * Gets the plugin-specific binary storage adapter.
     * This provides convenient methods for storing and retrieving binary files
     * specifically for this plugin with automatic namespacing.
     *
     * @return the plugin binary storage adapter
     */
    public PluginBinaryStorageAdapter getPluginBinaryStorage() {
        return pluginBinaryStorageAdapter;
    }

    /**
     * Gets the plugin-specific command adapter.
     * This provides convenient methods for registering and managing commands
     * specifically for this plugin.
     *
     * @return the plugin command adapter
     */
    public PluginCommandAdapter getPluginCommandAdapter() {
        return pluginCommandAdapter;
    }

    /**
     * Registers JDA listeners (e.g. {@code ListenerAdapter} subclasses) for Discord events. Works whether Discord
     * is connected yet or not, and the core removes them when the plugin is disabled. Use this for Discord
     * events; {@code @EventHandler} methods only receive Fluxcord events, never JDA ones.
     *
     * @param listeners the JDA listeners
     */
    protected void addDiscordListeners(Object... listeners) {
        discordAPI.addEventListeners(this, listeners);
    }

    /**
     * Returns whether this plugin is currently enabled.
     *
     * @return true if this plugin is enabled
     */
    public boolean isEnabled() {
        return lifecycle == PluginLifecycle.ENABLED;
    }

    /**
     * Gets the SLF4J logger for this plugin.
     *
     * @return the logger instance
     */
    public Logger getLogger() {
        return logger;
    }
}