package fr.farmvivi.fluxcord.core;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.command.CommandService;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageManager;
import fr.farmvivi.fluxcord.core.audio.AudioServiceImpl;
import fr.farmvivi.fluxcord.core.audio.AudioSettings;
import fr.farmvivi.fluxcord.core.command.SimpleCommandService;
import fr.farmvivi.fluxcord.core.config.CoreConfiguration;
import fr.farmvivi.fluxcord.core.console.ConsoleCommandService;
import fr.farmvivi.fluxcord.core.discord.JDADiscordAPI;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import fr.farmvivi.fluxcord.core.health.HealthServer;
import fr.farmvivi.fluxcord.core.language.LanguageFileLoader;
import fr.farmvivi.fluxcord.core.language.SimpleLanguageManager;
import fr.farmvivi.fluxcord.core.permissions.SimplePermissionManager;
import fr.farmvivi.fluxcord.core.plugin.PluginManager;
import fr.farmvivi.fluxcord.core.storage.StorageFactory;
import fr.farmvivi.fluxcord.core.storage.binary.BinaryStorageFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;

/**
 * Main class for Fluxcord.
 * This class is responsible for loading and managing plugins.
 */
public class Fluxcord {
    public static final String NAME;
    public static final String VERSION;
    public static final boolean PRODUCTION;
    private static final Logger logger;

    // Instances for global access/management
    private static PluginManager pluginManager;
    private static DiscordAPI discordAPI;
    private static SimpleEventManager eventManager;
    private static Configuration coreConfig;
    private static LanguageManager languageManager;
    private static DataStorageManager dataStorageManager;
    private static BinaryStorageManager binaryStorageManager;
    private static SimplePermissionManager permissionManager;
    private static AudioService audioService;
    private static CommandService commandService;
    private static ConsoleCommandService consoleCommandService;
    private static HealthServer healthServer;

    static {
        Properties properties = new Properties();
        try {
            properties.load(Fluxcord.class.getClassLoader().getResourceAsStream("project.properties"));
        } catch (IOException e) {
            Logger initLogger = LoggerFactory.getLogger("FluxcordInit");
            initLogger.error("Cannot read properties file 'project.properties'", e);
            System.exit(1);
        }

        NAME = properties.getProperty("name");
        VERSION = properties.getProperty("version");
        PRODUCTION = !VERSION.contains("-SNAPSHOT");

        logger = LoggerFactory.getLogger(NAME);
    }

    /**
     * Main entry point.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        long startTimeMillis = System.currentTimeMillis();

        logger.info("Démarrage de {} v{} en cours...", NAME, VERSION);

        // Log system information
        logSystemInfo();

        // Initialize bot components
        if (!initializeComponents()) {
            return;
        }

        // Register shutdown hook for clean shutdown
        registerShutdownHook();

        // Start health HTTP server (not ready yet)
        startHealthServer();

        // Start the bot using the proper sequence
        startBot();

        long startFinishedTimeMillis = System.currentTimeMillis();
        logger.info("Started in {}s!", (float) (startFinishedTimeMillis - startTimeMillis) / 1000);

        // Keep the main thread alive
        CountDownLatch latch = new CountDownLatch(1);
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Logs system information.
     */
    private static void logSystemInfo() {
        if (logger.isInfoEnabled()) {
            logger.info("System.getProperty('os.name') == '{}'", System.getProperty("os.name"));
            logger.info("System.getProperty('os.version') == '{}'", System.getProperty("os.version"));
            logger.info("System.getProperty('os.arch') == '{}'", System.getProperty("os.arch"));
            logger.info("System.getProperty('java.version') == '{}'", System.getProperty("java.version"));
            logger.info("System.getProperty('java.vendor') == '{}'", System.getProperty("java.vendor"));
            logger.info("System.getProperty('sun.arch.data.model') == '{}'", System.getProperty("sun.arch.data.model"));
            logger.info("System.getProperty('user.timezone') == '{}'", System.getProperty("user.timezone"));
            logger.info("System.getProperty('user.country') == '{}'", System.getProperty("user.country"));
            logger.info("System.getProperty('user.language') == '{}'", System.getProperty("user.language"));
        }
    }

    /**
     * Initializes all components needed for the bot.
     *
     * @return true if initialization was successful, false otherwise
     */
    private static boolean initializeComponents() {
        File pluginsFolder = ensurePluginsFolder();

        File configFile = new File("config.yml");
        if (!loadCoreConfiguration(configFile)) {
            return false;
        }

        String token;
        try {
            token = coreConfig.getString("discord.token");
            if (token == null || token.equals("YOUR_BOT_TOKEN")) {
                logger.error("Please set your bot token in config.yml");
                System.exit(1);
                return false;
            }
        } catch (Exception e) {
            logger.error("Failed to get discord.token from config.yml", e);
            System.exit(1);
            return false;
        }

        String defaultLanguage = coreConfig.getString("language.default", "en-US");
        Locale defaultLocale = Locale.forLanguageTag(defaultLanguage);
        if (defaultLocale.getLanguage().isEmpty()) {
            logger.warn("Invalid default language '{}', falling back to en-US", defaultLanguage);
            defaultLocale = Locale.US;
        }

        String defaultPrefix = coreConfig.getString("commands.default-prefix", "!");

        createLanguageServices(defaultLocale);
        createEventAndDiscord(token);
        createStorageManagers();
        createCommandAndPluginManagers(pluginsFolder, defaultPrefix);

        return true;
    }

    private static File ensurePluginsFolder() {
        File pluginsFolder = new File("plugins");
        if (!pluginsFolder.exists()) {
            pluginsFolder.mkdirs();
        }
        return pluginsFolder;
    }

    private static boolean loadCoreConfiguration(File configFile) {
        try {
            coreConfig = new CoreConfiguration(configFile);
            return true;
        } catch (Exception e) {
            logger.error("Failed to load config.yml", e);
            System.exit(1);
            return false;
        }
    }

    private static void createLanguageServices(Locale defaultLocale) {
        languageManager = new SimpleLanguageManager(defaultLocale);

        LanguageFileLoader languageFileLoader = new LanguageFileLoader(languageManager, new File("lang"));
        languageFileLoader.loadLanguageFiles();
    }

    private static void createEventAndDiscord(String token) {
        eventManager = new SimpleEventManager();
        discordAPI = new JDADiscordAPI(token);
    }

    private static void createStorageManagers() {
        dataStorageManager = StorageFactory.createStorageManager(coreConfig, eventManager);
        binaryStorageManager = BinaryStorageFactory.createBinaryStorageManager(coreConfig, eventManager);
        permissionManager = new SimplePermissionManager(eventManager, dataStorageManager);
        audioService = new AudioServiceImpl(eventManager, AudioSettings.fromConfig(coreConfig));
    }

    private static void createCommandAndPluginManagers(File pluginsFolder, String defaultPrefix) {
        commandService = new SimpleCommandService(
                eventManager,
                languageManager,
                permissionManager,
                coreConfig,
                dataStorageManager,
                defaultPrefix
        );

        // Create console command service
        consoleCommandService = new ConsoleCommandService(commandService);

        pluginManager = new PluginManager(
                pluginsFolder,
                eventManager,
                discordAPI,
                languageManager,
                dataStorageManager,
                binaryStorageManager,
                permissionManager,
                audioService,
                commandService
        );
    }

    /**
     * Registers the shutdown hook for clean shutdown.
     */
    private static void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down...");

            // Shutdown sequence
            shutdownBot();

            logger.info("Goodbye!");
        }));
    }

    /**
     * Starts the bot using the proper sequence.
     */
    private static void startBot() {
        // 1. Load plugins
        pluginManager.loadPlugins();

        // 2. Pre-enable plugins (they can now configure JDABuilder)
        pluginManager.preEnablePlugins();

        // 3. Connect to Discord
        try {
            discordAPI.connect().join();

            // Set the startup presence right after connecting
            discordAPI.setStartupPresence();

            // Set JDA instance for command service
            commandService.setJDA(discordAPI.getJDA());

            // Set JDA instance for console command service
            consoleCommandService.setJDA(discordAPI.getJDA());
        } catch (Exception e) {
            logger.error("Failed to connect to Discord", e);
            System.exit(1);
            return;
        }

        // 4. Enable plugins
        pluginManager.enablePlugins();

        // 5. Enable command service after plugins are loaded
        commandService.enable();

        // 6. Post-enable plugins
        pluginManager.postEnablePlugins();

        // 7. Start console command service
        consoleCommandService.start();

        // 8. Set the default presence after all plugins are enabled
        discordAPI.setDefaultPresence();

        // 9. Mark ready for health server
        if (healthServer != null) {
            healthServer.setReady(true);
        }
    }

    /**
     * Shuts down the bot using the proper sequence.
     */
    private static void shutdownBot() {
        // Use the new close() method which handles the full shutdown sequence
        if (pluginManager != null) {
            try {
                pluginManager.close();
            } catch (Exception e) {
                logger.error("Error during plugin shutdown", e);
            }
        }

        // Disable command service
        if (commandService != null) {
            commandService.disable();
        }

        // Stop console command service
        if (consoleCommandService != null) {
            consoleCommandService.stop();
        }

        // Disconnect from Discord
        if (discordAPI != null) {
            try {
                discordAPI.disconnect().join();
            } catch (Exception e) {
                logger.error("Failed to disconnect from Discord", e);
            }
        }

        // Shutdown other services
        if (eventManager != null) {
            eventManager.shutdown();
        }

        if (dataStorageManager != null) {
            dataStorageManager.close();
        }

        if (binaryStorageManager != null) {
            binaryStorageManager.close();
        }

        if (healthServer != null) {
            healthServer.stop();
        }
    }

    private static void startHealthServer() {
        String envPort = System.getenv("HEALTH_PORT");
        int port;
        try {
            port = envPort != null && !envPort.isBlank() ? Integer.parseInt(envPort) : 8081;
        } catch (NumberFormatException nfe) {
            logger.warn("Invalid HEALTH_PORT '{}', falling back to 8081", envPort);
            port = 8081;
        }
        try {
            healthServer = new HealthServer(port);
            healthServer.setVersion(VERSION);
            healthServer.start();
        } catch (Exception e) {
            logger.warn("Failed to start health server on port {}: {}", port, e.getMessage());
        }
    }

    /**
     * Gets the language manager.
     *
     * @return the language manager
     */
    public static LanguageManager getLanguageManager() {
        return languageManager;
    }

    /**
     * Gets the plugin manager.
     *
     * @return the plugin manager
     */
    public static PluginManager getPluginManager() {
        return pluginManager;
    }

    /**
     * Gets the Discord API.
     *
     * @return the Discord API
     */
    public static DiscordAPI getDiscordAPI() {
        return discordAPI;
    }

    /**
     * Gets the event manager.
     *
     * @return the event manager
     */
    public static SimpleEventManager getEventManager() {
        return eventManager;
    }

    /**
     * Gets the data storage manager.
     *
     * @return the data storage manager
     */
    public static DataStorageManager getDataStorageManager() {
        return dataStorageManager;
    }

    /**
     * Gets the binary storage manager.
     *
     * @return the binary storage manager
     */
    public static BinaryStorageManager getBinaryStorageManager() {
        return binaryStorageManager;
    }

    /**
     * Gets the core configuration.
     *
     * @return the core configuration
     */
    public static Configuration getCoreConfig() {
        return coreConfig;
    }

    /**
     * Gets the permission manager.
     *
     * @return the permission manager
     */
    public static PermissionManager getPermissionManager() {
        return permissionManager;
    }

    /**
     * Gets the audio service.
     *
     * @return the audio service, or null if audio is disabled
     */
    public static AudioService getAudioService() {
        return audioService;
    }

    /**
     * Gets the command service.
     *
     * @return the command service
     */
    public static CommandService getCommandService() {
        return commandService;
    }

    /**
     * Gets the console command service.
     *
     * @return the console command service
     */
    public static ConsoleCommandService getConsoleCommandService() {
        return consoleCommandService;
    }
}