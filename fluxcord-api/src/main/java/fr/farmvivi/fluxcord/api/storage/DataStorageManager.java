package fr.farmvivi.fluxcord.api.storage;

/**
 * Entry point of the key/value storage: hands out {@link ScopedStorage} views over the configured backend.
 * Plugins normally go through {@code AbstractPlugin.getPluginDataStorage()}, which namespaces these views by
 * plugin id; the core uses this manager directly.
 */
public interface DataStorageManager {

    /** @return the view of the global scope */
    ScopedStorage getGlobalStorage();

    /** @return the view of a user's scope */
    ScopedStorage getUserStorage(String userId);

    /** @return the view of a guild's scope */
    ScopedStorage getGuildStorage(String guildId);

    /** @return the view of a user's scope inside a guild */
    ScopedStorage getUserGuildStorage(String userId, String guildId);

    /**
     * Flushes pending writes to the backend.
     *
     * @return true when the flush succeeded
     */
    boolean saveAll();

    /**
     * Flushes and releases the backend.
     *
     * @return true when the backend closed cleanly
     */
    boolean close();
}
