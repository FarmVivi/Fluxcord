package fr.farmvivi.fluxcord.api.storage.binary;

import fr.farmvivi.fluxcord.api.storage.StorageKey;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a binary storage key with a scope and path.
 * This class handles the formatting of binary storage keys for different scopes
 * (global, user, guild, user-guild).
 */
public record BinaryStorageKey(String scope, String path) {

    /**
     * Creates a global binary storage key.
     *
     * @param path the file path
     * @return a binary storage key with global scope
     */
    public static BinaryStorageKey global(String path) {
        return new BinaryStorageKey(StorageKey.globalScope(), normalizePath(path));
    }

    /**
     * Creates a user binary storage key.
     *
     * @param userId the user ID
     * @param path   the file path
     * @return a binary storage key with user scope
     */
    public static BinaryStorageKey user(String userId, String path) {
        return new BinaryStorageKey(StorageKey.userScope(userId), normalizePath(path));
    }

    /**
     * Creates a guild binary storage key.
     *
     * @param guildId the guild ID
     * @param path    the file path
     * @return a binary storage key with guild scope
     */
    public static BinaryStorageKey guild(String guildId, String path) {
        return new BinaryStorageKey(StorageKey.guildScope(guildId), normalizePath(path));
    }

    /**
     * Creates a user-guild binary storage key.
     *
     * @param userId  the user ID
     * @param guildId the guild ID
     * @param path    the file path
     * @return a binary storage key with user-guild scope
     */
    public static BinaryStorageKey userGuild(String userId, String guildId, String path) {
        return new BinaryStorageKey(StorageKey.userGuildScope(userId, guildId), normalizePath(path));
    }

    /**
     * Normalizes a path to use forward slashes and no leading slash.
     *
     * @param path the path to normalize
     * @return the normalized path
     */
    static String normalizePath(String path) {
        if (path == null) {
            return "";
        }

        // Replace backslashes with forward slashes
        String normalized = path.replace('\\', '/');

        // Remove leading slash if present
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        return normalized;
    }

    /**
     * Gets the full key string.
     *
     * @return the formatted key
     */
    @NotNull
    public String toString() {
        return scope + ":" + path;
    }

    /**
     * Gets the full path including scope.
     *
     * @return the full path
     */
    public String getFullPath() {
        return scope + "/" + path;
    }
}