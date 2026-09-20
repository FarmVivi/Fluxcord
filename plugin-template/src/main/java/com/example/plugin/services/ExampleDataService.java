package com.example.plugin.services;

import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;

/**
 * Example service class demonstrating data management and plugin services.
 * Shows how to organize plugin business logic.
 */
public class ExampleDataService {

    private final AbstractPlugin plugin;

    public ExampleDataService(AbstractPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Example method demonstrating user data storage.
     */
    public void saveUserPreference(String userId, String key, Object value) {
        if (!plugin.getConfiguration().getBoolean("storage.enabled", true)) {
            plugin.getLogger().warn("Storage is disabled, cannot save user preference");
            return;
        }

        try {
            plugin.getStorage().getUserStorage(userId).set(key, value);
            plugin.getStorage().saveAll();

            plugin.getLogger().debug("Saved user preference: {} = {} for user {}",
                    key, value, userId);
        } catch (Exception e) {
            plugin.getLogger().error("Failed to save user preference", e);
        }
    }

    /**
     * Example method demonstrating user data retrieval.
     */
    public <T> T getUserPreference(String userId, String key, T defaultValue) {
        if (!plugin.getConfiguration().getBoolean("storage.enabled", true)) {
            return defaultValue;
        }

        try {
            return plugin.getStorage().getUserStorage(userId)
                    .get(key, (Class<T>) defaultValue.getClass())
                    .orElse(defaultValue);
        } catch (Exception e) {
            plugin.getLogger().error("Failed to get user preference", e);
            return defaultValue;
        }
    }

    /**
     * Example method demonstrating guild data management.
     */
    public void updateGuildSetting(String guildId, String setting, Object value) {
        try {
            plugin.getStorage().getGuildStorage(guildId).set("settings." + setting, value);
            plugin.getStorage().saveAll();

            plugin.getLogger().info("Updated guild setting {} = {} for guild {}",
                    setting, value, guildId);
        } catch (Exception e) {
            plugin.getLogger().error("Failed to update guild setting", e);
        }
    }

    /**
     * Example method demonstrating plugin statistics.
     */
    public void incrementUsageCounter(String feature) {
        try {
            long currentCount = plugin.getStorage().getGlobalStorage()
                    .get("stats." + feature, Long.class)
                    .orElse(0L);
            plugin.getStorage().getGlobalStorage()
                    .set("stats." + feature, currentCount + 1);

            // Save periodically (not every increment for performance)
            if (currentCount % 10 == 0) {
                plugin.getStorage().saveAll();
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to increment usage counter", e);
        }
    }

    /**
     * Cleanup method to be called on plugin disable.
     */
    public void cleanup() {
        try {
            plugin.getStorage().saveAll();
            plugin.getLogger().info("Data service cleanup completed");
        } catch (Exception e) {
            plugin.getLogger().error("Failed to cleanup data service", e);
        }
    }
}