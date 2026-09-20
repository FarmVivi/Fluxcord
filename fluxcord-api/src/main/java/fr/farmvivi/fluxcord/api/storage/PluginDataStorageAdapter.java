package fr.farmvivi.fluxcord.api.storage;

import fr.farmvivi.fluxcord.api.plugin.Plugin;

/**
 * A plugin's window on the data storage: the same scopes as {@link DataStorageManager}, with every key
 * namespaced by the plugin id ({@code <pluginId>.<key>}) so plugins never collide with each other or with the
 * core.
 */
public class PluginDataStorageAdapter {
    private final DataStorageManager storageManager;
    private final String namespace;

    public PluginDataStorageAdapter(Plugin plugin, DataStorageManager storageManager) {
        this(plugin.getId(), storageManager);
    }

    /** Same, from the plugin id alone (the core builds the adapter before the plugin instance is initialised). */
    public PluginDataStorageAdapter(String pluginId, DataStorageManager storageManager) {
        this.storageManager = storageManager;
        this.namespace = pluginId;
    }

    /** @return the plugin's view of the global scope */
    public ScopedStorage getGlobalStorage() {
        return storageManager.getGlobalStorage().namespaced(namespace);
    }

    /** @return the plugin's view of a user's scope */
    public ScopedStorage getUserStorage(String userId) {
        return storageManager.getUserStorage(userId).namespaced(namespace);
    }

    /** @return the plugin's view of a guild's scope */
    public ScopedStorage getGuildStorage(String guildId) {
        return storageManager.getGuildStorage(guildId).namespaced(namespace);
    }

    /** @return the plugin's view of a user's scope inside a guild */
    public ScopedStorage getUserGuildStorage(String userId, String guildId) {
        return storageManager.getUserGuildStorage(userId, guildId).namespaced(namespace);
    }

    /**
     * Flushes pending writes of the whole storage (not only this plugin's keys).
     *
     * @return true when the flush succeeded
     */
    public boolean saveAll() {
        return storageManager.saveAll();
    }
}
