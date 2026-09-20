package com.example.plugin;

import com.example.plugin.commands.ExampleCommand;
import com.example.plugin.events.ExampleEventListener;
import com.example.plugin.services.ExampleDataService;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

/**
 * Template plugin demonstrating the structure and features available to Fluxcord plugins.
 *
 * <p>This plugin serves as a comprehensive starting point for plugin development and showcases:
 * <ul>
 *   <li>Plugin lifecycle management (onEnable/onDisable)</li>
 *   <li>Configuration system with feature toggles</li>
 *   <li>Event handling with Discord events</li>
 *   <li>Internationalization support (en-US and fr-FR)</li>
 *   <li>Data storage and persistence examples</li>
 *   <li>Permission management system</li>
 *   <li>Command system integration</li>
 *   <li>Service-oriented architecture</li>
 * </ul>
 *
 * <p>All example features can be disabled via configuration to serve as a clean starting point.
 *
 * <p>To create your own plugin based on this template:
 * <ol>
 *   <li>Copy this template directory to your plugin name</li>
 *   <li>Update pom.xml with your plugin details (artifactId, name, description)</li>
 *   <li>Rename the package from com.example.plugin to your package</li>
 *   <li>Rename TemplatePlugin class to your plugin class name</li>
 *   <li>Update plugin.yml with your plugin metadata</li>
 *   <li>Customize configuration, language files, and features</li>
 *   <li>Build with: mvn clean package</li>
 *   <li>Copy the JAR to the plugins/ directory of your bot</li>
 * </ol>
 *
 * <p><strong>Note:</strong> Plugin metadata (name, version, etc.) is automatically loaded from
 * the plugin.yml file, so you don't need to implement getName() and getVersion() methods.
 *
 * @author YourName
 * @version 1.0.0
 * @see AbstractPlugin
 * @since 1.0.0
 */
public class TemplatePlugin extends AbstractPlugin {

    // Plugin services and components
    private ExampleCommand exampleCommand;
    private ExampleEventListener eventListener;
    private ExampleDataService dataService;

    // Feature flags loaded from configuration
    private boolean exampleCommandsEnabled;
    private boolean exampleEventsEnabled;
    private boolean exampleStorageEnabled;

    /**
     * Called when the plugin is loaded but not yet enabled.
     * Use this for early initialization that doesn't depend on other plugins.
     */
    @Override
    public void onLoad(PluginContext context) {
        super.onLoad(context);
        logger.info("Loading {} v{}", getName(), getVersion());

        // Early initialization - minimal setup only
        loadFeatureFlags();
    }

    /**
     * Called before the plugin is enabled.
     * Use this for setup that must happen before onEnable but after dependencies are loaded.
     */
    @Override
    public void onPreEnable() {
        super.onPreEnable();
        logger.info("Pre-enabling {} v{}", getName(), getVersion());

        // Register permissions
        registerPermissions();
    }

    /**
     * Called when the plugin is enabled.
     * This is where you should initialize your plugin's main functionality.
     */
    @Override
    public void onEnable() {
        super.onEnable();
        logger.info("Enabling {} v{}", getName(), getVersion());

        // Initialize services
        initializeServices();

        // Register commands (if enabled)
        if (exampleCommandsEnabled) {
            registerCommands();
        }

        // Register event listeners (if enabled)  
        if (exampleEventsEnabled) {
            registerEventListeners();
        }

        // Log successful enablement with localized message
        String enabledMessage = getPluginLanguageManager().getString("lifecycle.plugin_enabled");
        logger.info(enabledMessage);

        // Example: Save plugin start time for statistics
        if (exampleStorageEnabled) {
            dataService.incrementUsageCounter("plugin_starts");
            getPluginDataStorage().getGlobalStorage().set("last_started", System.currentTimeMillis());
        }
    }

    /**
     * Called after all plugins have been enabled.
     * Use this for functionality that depends on other plugins being fully loaded.
     */
    @Override
    public void onPostEnable() {
        super.onPostEnable();
        logger.info("Post-enabling {} v{}", getName(), getVersion());

        // Final initialization that may depend on other plugins
        performPostEnableSetup();
    }

    /**
     * Called before the plugin is disabled.
     * Use this for cleanup that other plugins might still depend on.
     */
    @Override
    public void onPreDisable() {
        super.onPreDisable();
        logger.info("Pre-disabling {} v{}", getName(), getVersion());

        // Save any pending data
        if (dataService != null && exampleStorageEnabled) {
            dataService.incrementUsageCounter("plugin_stops");
        }
    }

    /**
     * Called when the plugin is disabled.
     * This is where you should clean up your plugin's resources.
     */
    @Override
    public void onDisable() {
        super.onDisable();
        logger.info("Disabling {} v{}", getName(), getVersion());

        // Cleanup services
        if (dataService != null) {
            dataService.cleanup();
        }

        // Log shutdown with localized message
        String disabledMessage = getPluginLanguageManager().getString("lifecycle.plugin_disabled");
        logger.info(disabledMessage);
    }

    /**
     * Called after all plugins have been disabled.
     * Use this for final cleanup.
     */
    @Override
    public void onPostDisable() {
        super.onPostDisable();
        logger.info("Post-disabling {} v{}", getName(), getVersion());

        // Final cleanup
        exampleCommand = null;
        eventListener = null;
        dataService = null;
    }

    /**
     * Discord message handling lives in the JDA listener registered in registerEventListeners()
     * (ExampleEventListener). This method is what it delegates to; it is not an @EventHandler because the
     * internal bus never dispatches JDA events.
     */
    public void onMessageReceived(MessageReceivedEvent event) {
        // Only process if example events are enabled
        if (!exampleEventsEnabled) {
            return;
        }

        // Skip bot messages
        if (event.getAuthor().isBot()) {
            return;
        }

        // Example: React to messages containing the plugin name (if configured)
        if (getConfiguration().getBoolean("features.respond_to_mentions", false) &&
                event.getMessage().getContentRaw().toLowerCase().contains("template")) {

            // React with an emoji
            event.getMessage().addReaction(Emoji.fromUnicode("👋")).queue();

            // Log the interaction
            logger.debug("Reacted to message mentioning template from user: {}",
                    event.getAuthor().getAsTag());
        }
    }

    /**
     * Loads feature flags from configuration to determine which examples to enable.
     */
    private void loadFeatureFlags() {
        try {
            exampleCommandsEnabled = getConfiguration().getBoolean("features.example_commands", false);
            exampleEventsEnabled = getConfiguration().getBoolean("features.example_events", false);
            exampleStorageEnabled = getConfiguration().getBoolean("features.example_storage", false);

            logger.debug("Feature flags loaded - Commands: {}, Events: {}, Storage: {}",
                    exampleCommandsEnabled, exampleEventsEnabled, exampleStorageEnabled);
        } catch (Exception e) {
            logger.warn("Failed to load feature flags, using defaults", e);
            exampleCommandsEnabled = false;
            exampleEventsEnabled = false;
            exampleStorageEnabled = false;
        }
    }

    /**
     * Registers plugin-specific permissions.
     * These permissions can be used to control access to plugin features.
     */
    private void registerPermissions() {
        // Basic usage permission
        getPluginPermissionManager().registerPermission(new SimplePermission(
                pluginPrefix("use"),
                "Allows usage of basic template features",
                PermissionDefault.TRUE));

        // Admin permission for configuration management
        getPluginPermissionManager().registerPermission(new SimplePermission(
                pluginPrefix("admin"),
                "Allows administrative template actions",
                PermissionDefault.OP));

        // Command-specific permissions
        if (exampleCommandsEnabled) {
            getPluginPermissionManager().registerPermission(new SimplePermission(
                    pluginPrefix("command.example"),
                    "Allows usage of example commands",
                    PermissionDefault.TRUE));
        }

        logger.debug("Permissions registered: {}", getPluginPermissionManager().getRegisteredPermissions().size());
    }

    /**
     * Initializes plugin services.
     */
    private void initializeServices() {
        // Initialize data service if storage is enabled
        if (exampleStorageEnabled) {
            dataService = new ExampleDataService(this);
            logger.debug("Data service initialized");
        }

        // Initialize command handlers if commands are enabled
        if (exampleCommandsEnabled) {
            exampleCommand = new ExampleCommand(this);
            logger.debug("Command handlers initialized");
        }

        // Initialize event listeners if events are enabled
        if (exampleEventsEnabled) {
            eventListener = new ExampleEventListener(this);
            logger.debug("Event listeners initialized");
        }
    }

    /**
     * Registers plugin commands with the command service.
     */
    private void registerCommands() {
        if (exampleCommand == null) {
            return;
        }

        // Register example command using CommandService
        commandService.registerCommand(this, builder -> {
            builder.name("template-example")
                    .description(getPluginLanguageManager().getString("commands.example"))
                    .executor((context, cmd) -> exampleCommand.execute(context));
        });

        logger.info("Example commands registered");
    }

    /**
     * Registers event listeners with the event manager.
     */
    private void registerEventListeners() {
        if (eventListener == null) {
            return;
        }

        // Fluxcord events (@EventHandler methods) and Discord events (ListenerAdapter overrides): two buses
        eventManager.registerListener(eventListener, this);
        addDiscordListeners(eventListener);
        logger.info("Event listeners registered");
    }

    /**
     * Performs setup that happens after all plugins are enabled.
     */
    private void performPostEnableSetup() {
        // Example: Check for integration with other plugins
        if (getConfiguration().getBoolean("integration.check_other_plugins", true)) {
            checkPluginIntegrations();
        }

        // Example: Perform any delayed initialization
        if (exampleStorageEnabled && dataService != null) {
            // Load any persistent data or perform migrations
            performDataMigrations();
        }
    }

    /**
     * Checks for integration opportunities with other plugins.
     */
    private void checkPluginIntegrations() {
        // Example: Check if music plugin is available for integration
        if (context.getPluginLoader().getPlugin("MusicPlugin") != null) {
            logger.info("Music plugin detected - integration features available");
        }

        // Example: Check if AI audio plugin is available
        if (context.getPluginLoader().getPlugin("AIAudioPlugin") != null) {
            logger.info("AI Audio plugin detected - enhanced features available");
        }
    }

    /**
     * Performs any necessary data migrations.
     */
    private void performDataMigrations() {
        try {
            // Example: Check configuration version for migrations
            int configVersion = getConfiguration().getInt("config_version", 1);

            if (configVersion < 1) {
                logger.info("Performing data migration to version 1");
                // Perform migration logic here
                getConfiguration().set("config_version", 1);
                getConfiguration().save();
            }
        } catch (Exception e) {
            logger.error("Failed to perform data migrations", e);
        }
    }

    /**
     * Utility method to create plugin-prefixed permission names.
     *
     * @param node the permission node name
     * @return the full permission name with plugin prefix
     */
    private String pluginPrefix(String node) {
        return getId() + "." + node;
    }

    // Simple internal Permission implementation for template usage
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