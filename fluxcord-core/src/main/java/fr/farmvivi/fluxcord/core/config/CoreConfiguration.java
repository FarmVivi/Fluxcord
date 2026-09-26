package fr.farmvivi.fluxcord.core.config;

import fr.farmvivi.fluxcord.api.config.ConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Core configuration manager with versioning and migration support.
 */
public class CoreConfiguration extends EnvAwareYamlConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(CoreConfiguration.class);
    private static final String CONFIG_VERSION_KEY = "config_version";
    private static final int CURRENT_CONFIG_VERSION = 1;

    /**
     * Creates a new core configuration.
     *
     * @param configFile the configuration file
     * @throws ConfigurationException if there's an error loading the configuration
     */
    public CoreConfiguration(File configFile) throws ConfigurationException {
        super(configFile);

        // Create default config if it doesn't exist
        if (!configFile.exists()) {
            createDefaultConfig(configFile);
            // Inform the user but do not exit here (tests and callers decide)
            logger.info("Created default config.yml");
            logger.info("Please edit config.yml and restart the bot");
        }

        // Handle versioning and migration
        handleConfigVersioning();
    }

    /**
     * Creates the default configuration file.
     *
     * @param configFile the configuration file to create
     * @throws ConfigurationException if creation fails
     */
    private void createDefaultConfig(File configFile) throws ConfigurationException {
        try {
            // Copy default config from resources
            try (InputStream defaultConfigStream = getClass().getResourceAsStream("/config.yml")) {
                if (defaultConfigStream == null) {
                    throw new ConfigurationException("Default config.yml not found in core resources");
                }

                Files.copy(defaultConfigStream, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                logger.info("Created default core configuration at {}", configFile.getAbsolutePath());
            }

            // Reload to parse the created configuration
            reload();
        } catch (IOException e) {
            throw new ConfigurationException("Failed to create default configuration", e);
        }
    }

    /**
     * Handles configuration versioning and migration.
     */
    private void handleConfigVersioning() {
        int currentVersion = getInt(CONFIG_VERSION_KEY, 0);

        if (currentVersion == 0) {
            // Legacy configuration without version: treat as migration 0 -> CURRENT
            logger.info("Migrating legacy core configuration to version {}", CURRENT_CONFIG_VERSION);
            migrateConfiguration(0, CURRENT_CONFIG_VERSION);
        } else if (currentVersion < CURRENT_CONFIG_VERSION) {
            // Configuration needs migration
            logger.info("Migrating core configuration from version {} to {}",
                    currentVersion, CURRENT_CONFIG_VERSION);
            migrateConfiguration(currentVersion, CURRENT_CONFIG_VERSION);
        } else if (currentVersion > CURRENT_CONFIG_VERSION) {
            // Configuration is from a newer version
            logger.warn("Core configuration version {} is newer than expected {}. " +
                            "This may cause compatibility issues.",
                    currentVersion, CURRENT_CONFIG_VERSION);
        }
    }

    /**
     * Migrates configuration from one version to another.
     *
     * @param fromVersion the current version
     * @param toVersion   the target version
     */
    private void migrateConfiguration(int fromVersion, int toVersion) {
        // Create backup before migration
        createBackup();

        // Apply migrations step by step
        for (int version = fromVersion; version < toVersion; version++) {
            try {
                applyMigration(version, version + 1);
                logger.debug("Applied core configuration migration {} -> {}", version, version + 1);
            } catch (Exception e) {
                logger.error("Failed to apply core configuration migration {} -> {}: {}",
                        version, version + 1, e.getMessage());
                return; // Stop migration on error
            }
        }

        // Update version and save
        set(CONFIG_VERSION_KEY, toVersion);
        try {
            save();
            logger.info("Successfully migrated core configuration to version {}", toVersion);
        } catch (ConfigurationException e) {
            logger.error("Failed to save migrated core configuration: {}", e.getMessage());
        }
    }

    /**
     * Creates a backup of the current configuration.
     */
    private void createBackup() {
        if (getConfigFile() == null || !getConfigFile().exists()) {
            return;
        }

        try {
            File backupFile = new File(getConfigFile().getParent(),
                    "config.yml.backup." + System.currentTimeMillis());
            Files.copy(getConfigFile().toPath(), backupFile.toPath());
            logger.info("Created configuration backup at {}", backupFile.getAbsolutePath());
        } catch (IOException e) {
            logger.warn("Failed to create configuration backup: {}", e.getMessage());
        }
    }

    /**
     * Applies a specific migration step.
     *
     * @param fromVersion the version to migrate from
     * @param toVersion   the version to migrate to
     */
    private void applyMigration(int fromVersion, int toVersion) {
        // Apply version-specific migrations
        switch (fromVersion) {
            case 0 -> migrateFrom0To1();
            default -> logger.debug("No specific migration needed from version {} to {}",
                    fromVersion, toVersion);
        }
    }

    /**
     * Migration from version 0 (legacy) to version 1.
     */
    private void migrateFrom0To1() {
        // This is for migrating from the old configuration to the new structured one
        logger.info("Migrating from legacy configuration format to version 1");

        // Ensure all required sections exist with defaults
        if (!contains("discord.token")) {
            set("discord.token", "YOUR_BOT_TOKEN");
        }

        if (!contains("language.default")) {
            set("language.default", "en-US");
        }

        if (!contains("commands.default-prefix")) {
            set("commands.default-prefix", "!");
        }

        if (!contains("commands.system")) {
            set("commands.system.help", true);
            set("commands.system.version", true);
            set("commands.system.shutdown", true);
        }
    }

    /**
     * Gets the current configuration version.
     *
     * @return the configuration version
     */
    public int getConfigVersion() {
        return getInt(CONFIG_VERSION_KEY, 0);
    }

    /**
     * Validates that the configuration has all required fields.
     *
     * @throws ConfigurationException if required fields are missing
     */
    public void validateConfiguration() throws ConfigurationException {
        // Check required discord configuration
        if (!contains("discord.token") || getString("discord.token", "").equals("YOUR_BOT_TOKEN")) {
            throw new ConfigurationException("Discord token is required. Please set discord.token in config.yml");
        }

        // Validate other critical settings
        if (!contains("language.default")) {
            throw new ConfigurationException("Default language is required. Please set language.default in config.yml");
        }

        if (!contains("commands.default-prefix")) {
            throw new ConfigurationException("Default command prefix is required. Please set commands.default-prefix in config.yml");
        }
    }
}