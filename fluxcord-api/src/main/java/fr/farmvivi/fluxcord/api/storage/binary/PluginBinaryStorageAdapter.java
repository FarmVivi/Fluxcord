package fr.farmvivi.fluxcord.api.storage.binary;

import fr.farmvivi.fluxcord.api.plugin.Plugin;

/**
 * A plugin's window on the binary storage: the same scopes as {@link BinaryStorageManager}, with every path
 * placed under the plugin id ({@code <pluginId>/<path>}).
 */
public class PluginBinaryStorageAdapter {
    private final BinaryStorageManager storageManager;
    private final String namespace;

    public PluginBinaryStorageAdapter(Plugin plugin, BinaryStorageManager storageManager) {
        this.storageManager = storageManager;
        this.namespace = plugin.getId();
    }

    /** @return the plugin's view of the global scope */
    public ScopedBinaryStorage getGlobalStorage() {
        return storageManager.getGlobalStorage().namespaced(namespace);
    }

    /** @return the plugin's view of a user's scope */
    public ScopedBinaryStorage getUserStorage(String userId) {
        return storageManager.getUserStorage(userId).namespaced(namespace);
    }

    /** @return the plugin's view of a guild's scope */
    public ScopedBinaryStorage getGuildStorage(String guildId) {
        return storageManager.getGuildStorage(guildId).namespaced(namespace);
    }

    /** @return the plugin's view of a user's scope inside a guild */
    public ScopedBinaryStorage getUserGuildStorage(String userId, String guildId) {
        return storageManager.getUserGuildStorage(userId, guildId).namespaced(namespace);
    }
}
