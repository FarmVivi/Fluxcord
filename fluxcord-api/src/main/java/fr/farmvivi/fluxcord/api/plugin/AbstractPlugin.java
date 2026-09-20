package fr.farmvivi.fluxcord.api.plugin;

import fr.farmvivi.fluxcord.api.command.PluginCommandAdapter;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.binary.PluginBinaryStorageAdapter;
import org.slf4j.Logger;

import java.io.File;

/**
 * Base class for plugins: keeps the {@link PluginContext} handed to {@link #onLoad(PluginContext)}, logs each
 * lifecycle phase, and offers short accessors to the plugin-scoped views of the core services
 * ({@link #getCommands()}, {@link #getPermissions()}, {@link #getLanguage()}, {@link #getStorage()},
 * {@link #getBinaryStorage()}). Everything else is one call away on {@link #getContext()}.
 */
public abstract class AbstractPlugin implements Plugin {
    protected PluginContext context;
    protected Logger logger;
    protected EventManager eventManager;
    protected DiscordAPI discordAPI;
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

        File dataDir = new File(context.getDataFolder());
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

    /** @return the context handed to {@link #onLoad(PluginContext)} (null before load) */
    public PluginContext getContext() {
        return context;
    }

    /** @return this plugin's {@code config.yml} */
    public Configuration getConfiguration() {
        return context.getConfiguration();
    }

    /** @return this plugin's data folder ({@code plugins/<id>}) */
    public String getDataFolder() {
        return context.getDataFolder();
    }

    public Logger getLogger() {
        return logger;
    }

    /** Commands registered on behalf of this plugin (released with it). */
    public PluginCommandAdapter getCommands() {
        return context.getCommands();
    }

    /** Permissions registered on behalf of this plugin. */
    public PluginPermissionAdapter getPermissions() {
        return context.getPermissions();
    }

    /** Translations in this plugin's namespace. */
    public PluginLanguageAdapter getLanguage() {
        return context.getLanguage();
    }

    /** Key/value storage with this plugin's keys namespaced. */
    public PluginDataStorageAdapter getStorage() {
        return context.getStorage();
    }

    /** Binary storage under this plugin's folder. */
    public PluginBinaryStorageAdapter getBinaryStorage() {
        return context.getBinaryStorage();
    }

    /** @return true while the plugin is fully enabled */
    public boolean isEnabled() {
        return lifecycle == PluginLifecycle.ENABLED;
    }

    /**
     * Registers JDA listeners on behalf of this plugin: added to the builder before the connection and to the
     * live JDA afterwards, removed automatically when the plugin is disabled or reloaded.
     *
     * @param listeners JDA {@code ListenerAdapter}s / {@code EventListener}s
     */
    protected void addDiscordListeners(Object... listeners) {
        discordAPI.addEventListeners(this, listeners);
    }
}
