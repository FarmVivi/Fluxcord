package fr.farmvivi.fluxcord.api.storage.binary;

/**
 * Entry point of the binary (file) storage: hands out {@link ScopedBinaryStorage} views over the configured
 * backend. Plugins normally go through {@code AbstractPlugin.getPluginBinaryStorage()}, which namespaces these
 * views by plugin id.
 */
public interface BinaryStorageManager {

    /** @return the view of the global scope */
    ScopedBinaryStorage getGlobalStorage();

    /** @return the view of a user's scope */
    ScopedBinaryStorage getUserStorage(String userId);

    /** @return the view of a guild's scope */
    ScopedBinaryStorage getGuildStorage(String guildId);

    /** @return the view of a user's scope inside a guild */
    ScopedBinaryStorage getUserGuildStorage(String userId, String guildId);

    /**
     * Releases the backend.
     *
     * @return true when the backend closed cleanly
     */
    boolean close();
}
