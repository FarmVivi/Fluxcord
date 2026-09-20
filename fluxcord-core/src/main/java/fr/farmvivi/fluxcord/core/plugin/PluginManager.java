package fr.farmvivi.fluxcord.core.plugin;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.command.CommandService;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.plugin.PluginLifecycle;
import fr.farmvivi.fluxcord.api.plugin.PluginLoader;
import fr.farmvivi.fluxcord.api.plugin.events.*;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageManager;
import fr.farmvivi.fluxcord.api.command.PluginCommandAdapter;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.binary.PluginBinaryStorageAdapter;
import fr.farmvivi.fluxcord.core.language.LanguageFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Main implementation of PluginLoader that manages the plugin lifecycle.
 */
public class PluginManager implements PluginLoader, Closeable {
    private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);

    // Core services
    private final File pluginsFolder;
    private final EventManager eventManager;
    private final DiscordAPI discordAPI;
    private final LanguageManager languageManager;
    private final DataStorageManager dataStorageManager;
    private final BinaryStorageManager binaryStorageManager;
    private final PermissionManager permissionManager;
    private final AudioService audioService;
    private final CommandService commandService;

    // Plugin tracking
    private final Map<String, Plugin> plugins = new ConcurrentHashMap<>();
    private final Map<String, PluginClassLoader> classLoaders = new ConcurrentHashMap<>();
    private final Map<String, PluginDescriptor> pluginDescriptors = new ConcurrentHashMap<>();
    private final Map<String, String> pluginJarPaths = new ConcurrentHashMap<>();
    private final Set<String> failedPlugins = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new plugin manager.
     *
     * @param pluginsFolder        the folder where plugins are stored
     * @param eventManager         the event manager
     * @param discordAPI           the Discord API
     * @param languageManager      the language manager
     * @param dataStorageManager   the data storage manager
     * @param binaryStorageManager the binary storage manager
     * @param permissionManager    the permission manager
     */
    public PluginManager(
            File pluginsFolder,
            EventManager eventManager,
            DiscordAPI discordAPI,
            LanguageManager languageManager,
            DataStorageManager dataStorageManager,
            BinaryStorageManager binaryStorageManager,
            PermissionManager permissionManager,
            AudioService audioService,
            CommandService commandService) {
        this.pluginsFolder = pluginsFolder;
        this.eventManager = eventManager;
        this.discordAPI = discordAPI;
        this.languageManager = languageManager;
        this.dataStorageManager = dataStorageManager;
        this.binaryStorageManager = binaryStorageManager;
        this.permissionManager = permissionManager;
        this.audioService = audioService;
        this.commandService = commandService;

        if (!pluginsFolder.exists() && !pluginsFolder.mkdirs()) {
            logger.warn("Failed to create plugins folder: {}", pluginsFolder.getAbsolutePath());
        }
    }

    @Override
    public Plugin loadPlugin(String jarFile) {
        File file = new File(jarFile);
        if (!file.exists()) {
            logger.error("Plugin file does not exist: {}", jarFile);
            return null;
        }

        PluginClassLoader classLoader = null;
        try (JarFile jar = new JarFile(file)) {
            // Look for plugin.yml
            JarEntry entry = jar.getJarEntry("plugin.yml");
            if (entry == null) {
                logger.error("Plugin does not contain plugin.yml: {}", jarFile);
                return null;
            }

            // Parse plugin.yml
            PluginDescriptor descriptor = PluginDescriptor.fromYaml(jar.getInputStream(entry));

            // Create the class loader (closed below if anything fails before it is registered, so the jar is not
            // left locked on Windows and the failed plugin can be replaced)
            URL[] urls = {file.toURI().toURL()};
            classLoader = new PluginClassLoader(urls, getClass().getClassLoader(), descriptor);

            // Load the main class
            Class<?> mainClass = classLoader.loadClass(descriptor.main());
            if (!Plugin.class.isAssignableFrom(mainClass)) {
                logger.error("Main class does not implement Plugin: {}", descriptor.main());
                classLoader.close();
                return null;
            }

            // Instantiate the plugin
            Plugin plugin = (Plugin) mainClass.getDeclaredConstructor().newInstance();

            // Fire the plugin loading event
            if (eventManager != null) {
                PluginLoadingEvent loadingEvent = new PluginLoadingEvent(plugin);
                eventManager.fireEvent(loadingEvent);
            }

            // Create the plugin configuration using plugin ID
            PluginConfiguration pluginConfig = new PluginConfiguration(descriptor.id(), classLoader);

            // Create the plugin context
            PluginContextImpl context = new PluginContextImpl(
                    descriptor.id(),
                    descriptor.name(),
                    descriptor.version(),
                    LoggerFactory.getLogger(descriptor.id()),
                    eventManager,
                    discordAPI,
                    pluginConfig,
                    new File(pluginsFolder, descriptor.id()).getAbsolutePath(),
                    this,
                    classLoader,
                    audioService,
                    // built before onLoad: the plugin instance cannot answer getId() yet, the descriptor can
                    new PluginCommandAdapter(plugin, commandService),
                    new PluginPermissionAdapter(plugin, permissionManager, languageManager),
                    new PluginLanguageAdapter(descriptor.id(), descriptor.name(), languageManager),
                    new PluginDataStorageAdapter(descriptor.id(), dataStorageManager),
                    new PluginBinaryStorageAdapter(descriptor.id(), binaryStorageManager)
            );

            // Initialize the plugin (this will register the plugin namespace via PluginLanguageAdapter)
            plugin.setLifecycle(PluginLifecycle.LOADED);
            plugin.onLoad(context);

            // Initialize configuration migration after plugin is loaded
            pluginConfig.initializeMigration(plugin);

            // Plugin strings: jar lang/*.yml first, then plugins/<id>/lang/*.yml overrides (namespace registered by
            // the PluginLanguageAdapter of the context)
            int loadedStrings = LanguageFiles.loadJar(languageManager, descriptor.id(), jar)
                    + LanguageFiles.loadFolder(languageManager, descriptor.id(), new File(new File(pluginsFolder, descriptor.id()), "lang"));
            logger.debug("Loaded {} language entries for plugin '{}'", loadedStrings, descriptor.id());

            // Store the classloader using plugin ID
            classLoaders.put(descriptor.id(), classLoader);

            // Store the descriptor using plugin ID
            pluginDescriptors.put(descriptor.id(), descriptor);

            // Fire the plugin loaded event
            if (eventManager != null) {
                PluginLoadedEvent loadedEvent = new PluginLoadedEvent(plugin);
                eventManager.fireEvent(loadedEvent);
            }

            return plugin;
        } catch (Exception e) {
            logger.error("Failed to load plugin: {}", jarFile, e);
            closeQuietly(classLoader);
            return null;
        }
    }

    private static void closeQuietly(PluginClassLoader classLoader) {
        if (classLoader != null) {
            try {
                classLoader.close();
            } catch (IOException e) {
                logger.warn("Error closing class loader of a plugin that failed to load", e);
            }
        }
    }

    @Override
    public boolean enablePlugin(Plugin plugin) {
        if (plugin.getLifecycle() != PluginLifecycle.LOADED) {
            logger.warn("Cannot enable plugin {} ({}): not in LOADED state", plugin.getId(), plugin.getName());
            return false;
        }

        // Fire the plugin enable event - check if any listeners want to prevent enabling
        if (eventManager != null) {
            PluginEnableEvent enableEvent = new PluginEnableEvent(plugin);
            eventManager.fireEvent(enableEvent);

            if (enableEvent.isCancelled()) {
                logger.warn("Plugin {} ({}) enable was cancelled by a listener", plugin.getId(), plugin.getName());
                return false;
            }
        }

        try {
            // Follow the proper lifecycle
            executeLifecyclePhase(plugin, PluginLifecycle.PRE_ENABLING, Plugin::onPreEnable);
            executeLifecyclePhase(plugin, PluginLifecycle.ENABLING, Plugin::onEnable);
            executeLifecyclePhase(plugin, PluginLifecycle.POST_ENABLING, Plugin::onPostEnable);

            markEnabled(plugin);
            return true;
        } catch (Exception e) {
            logger.error("Failed to enable plugin: {} ({})", plugin.getId(), plugin.getName(), e);
            plugin.setLifecycle(PluginLifecycle.ERROR);
            return false;
        }
    }

    @Override
    public boolean disablePlugin(Plugin plugin) {
        if (plugin.getLifecycle() != PluginLifecycle.ENABLED) {
            logger.warn("Cannot disable plugin {} ({}): not in ENABLED state", plugin.getId(), plugin.getName());
            return false;
        }

        // Fire the plugin disable event - check if any listeners want to prevent disabling
        if (eventManager != null) {
            PluginDisableEvent disableEvent = new PluginDisableEvent(plugin);
            eventManager.fireEvent(disableEvent);

            if (disableEvent.isCancelled()) {
                logger.warn("Plugin {} ({}) disable was cancelled by a listener", plugin.getId(), plugin.getName());
                return false;
            }
        }

        try {
            // Follow the proper lifecycle
            executeLifecyclePhase(plugin, PluginLifecycle.PRE_DISABLING, Plugin::onPreDisable);
            executeLifecyclePhase(plugin, PluginLifecycle.DISABLING, Plugin::onDisable);
            executeLifecyclePhase(plugin, PluginLifecycle.POST_DISABLING, Plugin::onPostDisable);

            releaseResources(plugin);
            markDisabled(plugin);
            return true;
        } catch (Exception e) {
            logger.error("Failed to disable plugin: {} ({})", plugin.getId(), plugin.getName(), e);
            plugin.setLifecycle(PluginLifecycle.ERROR);
            return false;
        }
    }

    @Override
    public Plugin getPlugin(String id) {
        return plugins.get(id);
    }

    @Override
    public List<Plugin> getPlugins() {
        return new ArrayList<>(plugins.values());
    }

    @Override
    public boolean isPluginLoaded(String id) {
        return plugins.containsKey(id);
    }

    /**
     * Loads all plugins from the plugins folder in dependency order.
     */
    public void loadPlugins() {
        // First, scan all plugins to get their metadata
        scanPlugins();

        // Resolve dependencies to determine load order
        DependencyResolver resolver = new DependencyResolver(pluginDescriptors);
        List<String> loadOrder = resolver.resolve();

        // Add missing dependencies to failed list
        failedPlugins.addAll(resolver.getMissingDependencies());

        // Load plugins in the determined order
        for (String pluginName : loadOrder) {
            if (failedPlugins.contains(pluginName)) {
                continue; // Skip failed plugins
            }

            String jarPath = pluginJarPaths.get(pluginName);
            try {
                Plugin plugin = loadPlugin(jarPath);
                if (plugin != null) {
                    plugins.put(pluginName, plugin);
                    logger.info("Loaded plugin: {} v{}", pluginName, pluginDescriptors.get(pluginName).version());
                } else {
                    logger.error("Failed to load plugin: {}", pluginName);
                    markFailed(pluginName);
                }
            } catch (Exception e) {
                logger.error("Error loading plugin: {}", pluginName, e);
                markFailed(pluginName);
            }
        }

        // Log summary
        int successCount = plugins.size();
        int failedCount = failedPlugins.size();
        logger.info("Plugin loading complete: {} loaded successfully, {} failed", successCount, failedCount);
    }

    /**
     * Pre-enables all loaded plugins in dependency order.
     */
    public void preEnablePlugins() {
        logger.info("Pre-enabling plugins...");
        for (Plugin plugin : getPluginsInOrder()) {
            if (plugin.getLifecycle() == PluginLifecycle.LOADED && !failedPlugins.contains(plugin.getId())) {
                try {
                    executeLifecyclePhase(plugin, PluginLifecycle.PRE_ENABLING, Plugin::onPreEnable);
                } catch (Exception e) {
                    logger.error("Error pre-enabling plugin: {} ({})", plugin.getId(), plugin.getName(), e);
                    markFailed(plugin.getId());
                }
            }
        }
    }

    /**
     * Enables all pre-enabled plugins in dependency order.
     */
    public void enablePlugins() {
        logger.info("Enabling plugins...");
        for (Plugin plugin : getPluginsInOrder()) {
            if (plugin.getLifecycle() == PluginLifecycle.PRE_ENABLING && !failedPlugins.contains(plugin.getId())) {
                try {
                    executeLifecyclePhase(plugin, PluginLifecycle.ENABLING, Plugin::onEnable);
                } catch (Exception e) {
                    logger.error("Error enabling plugin: {} ({})", plugin.getId(), plugin.getName(), e);
                    markFailed(plugin.getId());
                }
            }
        }
    }

    /**
     * Post-enables all enabled plugins in dependency order.
     */
    public void postEnablePlugins() {
        logger.info("Post-enabling plugins...");
        for (Plugin plugin : getPluginsInOrder()) {
            if (plugin.getLifecycle() == PluginLifecycle.ENABLING && !failedPlugins.contains(plugin.getId())) {
                try {
                    executeLifecyclePhase(plugin, PluginLifecycle.POST_ENABLING, Plugin::onPostEnable);
                    markEnabled(plugin);
                } catch (Exception e) {
                    logger.error("Error post-enabling plugin: {} ({})", plugin.getId(), plugin.getName(), e);
                    markFailed(plugin.getId());
                }
            }
        }
    }

    /**
     * Pre-disables all enabled plugins in reverse dependency order.
     */
    public void preDisablePlugins() {
        logger.info("Pre-disabling plugins...");
        for (Plugin plugin : getPluginsInReverseOrder()) {
            if (plugin.getLifecycle() == PluginLifecycle.ENABLED) {
                try {
                    executeLifecyclePhase(plugin, PluginLifecycle.PRE_DISABLING, Plugin::onPreDisable);
                } catch (Exception e) {
                    logger.error("Error pre-disabling plugin: {} ({})", plugin.getId(), plugin.getName(), e);
                    // Continue anyway
                }
            }
        }
    }

    /**
     * Disables all pre-disabled plugins in reverse dependency order.
     */
    public void disablePlugins() {
        logger.info("Disabling plugins...");
        for (Plugin plugin : getPluginsInReverseOrder()) {
            if (plugin.getLifecycle() == PluginLifecycle.PRE_DISABLING) {
                try {
                    executeLifecyclePhase(plugin, PluginLifecycle.DISABLING, Plugin::onDisable);
                } catch (Exception e) {
                    logger.error("Error disabling plugin: {} ({})", plugin.getId(), plugin.getName(), e);
                    // Continue anyway
                }
            }
        }
    }

    /**
     * Post-disables all disabled plugins in reverse dependency order.
     */
    public void postDisablePlugins() {
        logger.info("Post-disabling plugins...");
        for (Plugin plugin : getPluginsInReverseOrder()) {
            if (plugin.getLifecycle() == PluginLifecycle.DISABLING) {
                try {
                    executeLifecyclePhase(plugin, PluginLifecycle.POST_DISABLING, Plugin::onPostDisable);
                    releaseResources(plugin);
                    markDisabled(plugin);
                } catch (Exception e) {
                    logger.error("Error post-disabling plugin: {} ({})", plugin.getId(), plugin.getName(), e);
                    // Continue anyway
                }
            }
        }

        // Cleanup resources
        cleanupResources();
    }

    /**
     * Reloads a specific plugin.
     *
     * @param pluginName the name of the plugin to reload
     * @return true if successful, false otherwise
     */
    public boolean reloadPlugin(String pluginName) {
        logger.info("Reloading plugin: {}", pluginName);

        Plugin plugin = getPlugin(pluginName);
        if (plugin == null) {
            logger.warn("Cannot reload plugin {}: not found", pluginName);
            return false;
        }

        // Get the plugin's JAR file
        PluginClassLoader classLoader = classLoaders.get(pluginName);
        if (classLoader == null) {
            logger.warn("Cannot reload plugin {}: classloader not found", pluginName);
            return false;
        }

        URL[] urls = classLoader.getURLs();
        if (urls.length == 0) {
            logger.warn("Cannot reload plugin {}: no JAR file", pluginName);
            return false;
        }

        String jarPath = urls[0].getPath();
        if (!new File(jarPath).exists()) {
            logger.warn("Cannot reload plugin {}: JAR file not found at {}", pluginName, jarPath);
            return false;
        }

        // Disable the plugin
        if (plugin.getLifecycle() == PluginLifecycle.ENABLED) {
            if (!disablePlugin(plugin)) {
                logger.error("Failed to disable plugin {} for reload", pluginName);
                return false;
            }
        }

        // Remove the plugin
        plugins.remove(pluginName);

        // Close the classloader
        try {
            classLoader.close();
        } catch (IOException e) {
            logger.error("Error closing classloader for plugin {}", pluginName, e);
        }
        classLoaders.remove(pluginName);

        // Load the plugin again
        try {
            Plugin newPlugin = loadPlugin(jarPath);
            if (newPlugin != null) {
                plugins.put(newPlugin.getId(), newPlugin); // keyed by id like everywhere else

                // Enable the plugin
                if (!enablePlugin(newPlugin)) {
                    logger.error("Failed to enable reloaded plugin: {}", pluginName);
                    return false;
                }

                logger.info("Successfully reloaded plugin: {} v{}", newPlugin.getName(), newPlugin.getVersion());
                return true;
            } else {
                logger.error("Failed to reload plugin: {}", pluginName);
                return false;
            }
        } catch (Exception e) {
            logger.error("Error reloading plugin: {}", pluginName, e);
            return false;
        }
    }

    /**
     * Reloads all plugins completely.
     * This will disable all plugins, reconnect to Discord, and re-enable all plugins.
     *
     * @return true if reload was successful, false otherwise
     */
    public boolean reloadPlugins() {
        logger.info("Starting complete plugin reload...");
        long startTime = System.currentTimeMillis();

        savePluginState();
        disableAllPlugins();
        disconnectFromDiscord();
        clearEventHandlers();
        reloadAllPlugins();
        preEnableAllPlugins();

        boolean discordConnected = reconnectToDiscord();
        boolean enabledOk = false;
        if (discordConnected) {
            enabledOk = enableAllPlugins();
        } else {
            logger.error("Skipping plugin enable phase due to Discord connection failure");
        }

        logReloadSummary(startTime, discordConnected);
        return enabledOk && discordConnected;
    }

    /**
     * Saves all plugin data prior to reload.
     */
    private void savePluginState() {
        if (dataStorageManager != null) {
            dataStorageManager.saveAll();
        }
    }

    /**
     * Disables every loaded plugin following the proper sequence.
     */
    private void disableAllPlugins() {
        logger.info("Disabling plugins...");
        preDisablePlugins();
        disablePlugins();
        postDisablePlugins();
    }

    /**
     * Disconnects the bot from Discord.
     */
    private void disconnectFromDiscord() {
        logger.info("Disconnecting from Discord...");
        try {
            discordAPI.setShutdownPresence();
            discordAPI.disconnect().join();
        } catch (Exception e) {
            logger.error("Failed to disconnect from Discord during reload", e);
            logger.warn("Continuing reload, but Discord reconnection may fail");
        }
    }

    /**
     * Clears all event handlers before plugins are reloaded.
     */
    private void clearEventHandlers() {
        logger.info("Cleaning up resources...");
        if (eventManager != null) {
            for (Plugin plugin : plugins.values()) {
                eventManager.unregisterAll(plugin);
            }
        }
    }

    /**
     * Reloads plugins from disk by scanning the plugins folder and loading
     * each plugin.
     */
    private void reloadAllPlugins() {
        logger.info("Reloading plugins...");
        try {
            loadPlugins();
        } catch (Exception e) {
            logger.error("Error during plugin loading phase", e);
        }
    }

    /**
     * Runs the pre-enable phase for all plugins.
     */
    private void preEnableAllPlugins() {
        logger.info("Pre-enabling plugins...");
        preEnablePlugins();
    }

    /**
     * Attempts to reconnect to Discord after plugins have been reloaded.
     *
     * @return true if the connection was successful
     */
    private boolean reconnectToDiscord() {
        logger.info("Reconnecting to Discord...");
        try {
            discordAPI.connect().join();
            discordAPI.setStartupPresence();
            return true;
        } catch (Exception e) {
            logger.error("Failed to reconnect to Discord after reload", e);
            logger.warn("Plugins requiring Discord features may not function properly");
            return false;
        }
    }

    /**
     * Enables all loaded plugins and sets the default presence.
     *
     * @return true if the enable phase completed without exception
     */
    private boolean enableAllPlugins() {
        logger.info("Enabling plugins...");
        try {
            enablePlugins();
            postEnablePlugins();
            discordAPI.setDefaultPresence();
            return true;
        } catch (Exception e) {
            logger.error("Error during plugin enabling phase", e);
            return false;
        }
    }

    /**
     * Logs a summary of the reload process including timing statistics.
     *
     * @param startTime        the time when the reload began
     * @param discordConnected whether Discord reconnection succeeded
     */
    private void logReloadSummary(long startTime, boolean discordConnected) {
        int totalPlugins = plugins.size();
        int enabledPlugins = (int) plugins.values().stream()
                .filter(p -> p.getLifecycle() == PluginLifecycle.ENABLED)
                .count();
        int failedPlugins = totalPlugins - enabledPlugins;

        long endTime = System.currentTimeMillis();
        double reloadTime = (endTime - startTime) / 1000.0;

        logger.info(
                "Plugin reload completed in {:.2f} seconds: {} total plugins, {} enabled, {} failed",
                reloadTime, totalPlugins, enabledPlugins, failedPlugins);
    }

    /**
     * Scans plugin JARs to collect metadata without loading them.
     */
    private void scanPlugins() {
        logger.info("Scanning plugins in {}", pluginsFolder.getAbsolutePath());

        // Clear previous state
        pluginDescriptors.clear();
        pluginJarPaths.clear();
        failedPlugins.clear();

        File[] files = pluginsFolder.listFiles((dir, name) -> name.endsWith(".jar"));
        if (files == null) {
            logger.warn("Failed to list files in plugins folder");
            return;
        }

        for (File file : files) {
            try (JarFile jar = new JarFile(file)) {
                // Look for plugin.yml
                JarEntry entry = jar.getJarEntry("plugin.yml");
                if (entry == null) {
                    logger.error("Plugin does not contain plugin.yml: {}", file.getName());
                    continue;
                }

                // Parse plugin.yml
                PluginDescriptor descriptor = PluginDescriptor.fromYaml(jar.getInputStream(entry));

                // Store metadata using plugin ID
                pluginDescriptors.put(descriptor.id(), descriptor);
                pluginJarPaths.put(descriptor.id(), file.getAbsolutePath());

                logger.debug("Scanned plugin: {} ({}) v{}", descriptor.id(), descriptor.name(), descriptor.version());
            } catch (Exception e) {
                logger.error("Failed to scan plugin: {}", file.getName(), e);
            }
        }

        logger.info("Scanned {} plugins", pluginDescriptors.size());
    }

    /**
     * Executes a specific lifecycle phase for a plugin.
     *
     * @param plugin       the plugin
     * @param newLifecycle the new lifecycle state
     * @param action       the action to perform
     */
    /**
     * Everything a plugin acquired from the shared services is released here, on both the phased shutdown
     * path and the single-plugin disable/reload path (they used to differ: the phased path only unregistered
     * event listeners, leaking permissions, commands and audio connections of the plugin).
     */
    private void releaseResources(Plugin plugin) {
        if (eventManager != null) {
            eventManager.unregisterAll(plugin);
        }
        if (discordAPI != null) {
            discordAPI.removeEventListeners(plugin);
        }
        if (permissionManager != null) {
            permissionManager.unregisterPermissions(plugin);
        }
        if (audioService != null) {
            audioService.closeAllConnectionsForPlugin(plugin);
        }
        if (commandService != null) {
            commandService.getRegistry().unregisterAll(plugin);
        }
    }

    private void markEnabled(Plugin plugin) {
        PluginLifecycle oldLifecycle = plugin.getLifecycle();
        plugin.setLifecycle(PluginLifecycle.ENABLED);
        fireLifecycleChangeEvent(plugin, oldLifecycle, PluginLifecycle.ENABLED);
        if (eventManager != null) {
            eventManager.fireEvent(new PluginEnabledEvent(plugin));
        }
        logger.info("Plugin fully enabled: {} ({}) v{}", plugin.getId(), plugin.getName(), plugin.getVersion());
    }

    private void markDisabled(Plugin plugin) {
        PluginLifecycle oldLifecycle = plugin.getLifecycle();
        plugin.setLifecycle(PluginLifecycle.DISABLED);
        fireLifecycleChangeEvent(plugin, oldLifecycle, PluginLifecycle.DISABLED);
        if (eventManager != null) {
            eventManager.fireEvent(new PluginDisabledEvent(plugin));
        }
        logger.info("Plugin fully disabled: {} ({})", plugin.getId(), plugin.getName());
    }

    /**
     * Records a boot failure: the plugin goes {@code ERROR}, whatever it registered so far is released, and every
     * plugin that hard-depends on it (transitively) is failed the same way — a dependant cannot run without its
     * dependency, and the resolver only knows about <em>missing</em> dependencies, not failed ones. Soft
     * dependants are left alone. Idempotent.
     */
    private void markFailed(String pluginId) {
        if (!failedPlugins.add(pluginId)) {
            return;
        }
        Plugin plugin = plugins.get(pluginId);
        if (plugin != null) {
            plugin.setLifecycle(PluginLifecycle.ERROR);
            try {
                releaseResources(plugin);
            } catch (Exception e) {
                logger.error("Error releasing resources of failed plugin {}", pluginId, e);
            }
        }
        for (PluginDescriptor descriptor : pluginDescriptors.values()) {
            if (descriptor.dependencies().contains(pluginId) && !failedPlugins.contains(descriptor.id())) {
                logger.error("Plugin {} cannot run: its dependency {} failed", descriptor.id(), pluginId);
                markFailed(descriptor.id());
            }
        }
    }

    /** Ids of the plugins that failed to load or enable during this boot (including dependants of failed ones). */
    public Set<String> getFailedPlugins() {
        return Collections.unmodifiableSet(failedPlugins);
    }

    private void executeLifecyclePhase(Plugin plugin, PluginLifecycle newLifecycle, PluginAction action) {
        PluginLifecycle oldLifecycle = plugin.getLifecycle();
        plugin.setLifecycle(newLifecycle);
        fireLifecycleChangeEvent(plugin, oldLifecycle, newLifecycle);
        action.execute(plugin);
    }

    /**
     * Fires a lifecycle change event.
     *
     * @param plugin       the plugin
     * @param oldLifecycle the old lifecycle state
     * @param newLifecycle the new lifecycle state
     */
    private void fireLifecycleChangeEvent(Plugin plugin, PluginLifecycle oldLifecycle, PluginLifecycle newLifecycle) {
        if (eventManager != null) {
            PluginLifecycleChangeEvent event = new PluginLifecycleChangeEvent(plugin, oldLifecycle, newLifecycle);
            eventManager.fireEvent(event);
        }
    }

    /**
     * Gets plugins in dependency order.
     *
     * @return ordered list of plugins
     */
    private List<Plugin> getPluginsInOrder() {
        // Create a map of plugin names to plugin instances
        Map<String, Plugin> pluginMap = new HashMap<>();
        for (Plugin plugin : plugins.values()) {
            pluginMap.put(plugin.getId(), plugin);
        }

        // Resolve dependencies
        DependencyResolver resolver = new DependencyResolver(pluginDescriptors);
        List<String> orderedNames = resolver.resolve();

        // Create ordered list of plugin instances
        List<Plugin> orderedPlugins = new ArrayList<>();
        for (String name : orderedNames) {
            Plugin plugin = pluginMap.get(name);
            if (plugin != null) {
                orderedPlugins.add(plugin);
            }
        }

        return orderedPlugins;
    }

    /**
     * Gets plugins in reverse dependency order.
     *
     * @return ordered list of plugins
     */
    private List<Plugin> getPluginsInReverseOrder() {
        List<Plugin> orderedPlugins = getPluginsInOrder();
        Collections.reverse(orderedPlugins);
        return orderedPlugins;
    }

    /**
     * Cleans up resources when shutting down.
     */
    private void cleanupResources() {
        // Plugins that never reached DISABLED (ERROR, or failed mid-shutdown) still hold registrations
        for (Plugin plugin : plugins.values()) {
            if (plugin.getLifecycle() != PluginLifecycle.DISABLED) {
                releaseResources(plugin);
            }
        }

        // Close class loaders
        for (PluginClassLoader classLoader : classLoaders.values()) {
            try {
                classLoader.close();
            } catch (IOException e) {
                logger.error("Error closing classloader", e);
            }
        }

        // Clear collections
        plugins.clear();
        classLoaders.clear();
        pluginDescriptors.clear();
        pluginJarPaths.clear();
        failedPlugins.clear();
    }

    @Override
    public void close() {
        preDisablePlugins();
        disablePlugins();
        postDisablePlugins();
    }

    /**
     * Functional interface for plugin lifecycle actions.
     */
    @FunctionalInterface
    private interface PluginAction {
        void execute(Plugin plugin);
    }
}