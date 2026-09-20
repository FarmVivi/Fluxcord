package fr.farmvivi.fluxcord.api.storage;

import org.jetbrains.annotations.NotNull;

/**
 * Represents a storage key with a scope and a key name.
 * This class handles the formatting of storage keys for different scopes
 * (global, user, guild, user-guild).
 */
public record StorageKey(String scope, String key) {
    /** The scope string shared by every global key. */
    public static String globalScope() {
        return "global";
    }

    /** The scope string of a user's keys ({@code user:<id>}). */
    public static String userScope(String userId) {
        return "user:" + userId;
    }

    /** The scope string of a guild's keys ({@code guild:<id>}). */
    public static String guildScope(String guildId) {
        return "guild:" + guildId;
    }

    /** The scope string of a user's keys inside a guild ({@code user:<id>:guild:<id>}). */
    public static String userGuildScope(String userId, String guildId) {
        return "user:" + userId + ":guild:" + guildId;
    }

    /**
     * Creates a global storage key.
     *
     * @param key the key name
     * @return a storage key with global scope
     */
    public static StorageKey global(String key) {
        return new StorageKey(globalScope(), key);
    }

    /**
     * Creates a user storage key.
     *
     * @param userId the user ID
     * @param key    the key name
     * @return a storage key with user scope
     */
    public static StorageKey user(String userId, String key) {
        return new StorageKey(userScope(userId), key);
    }

    /**
     * Creates a guild storage key.
     *
     * @param guildId the guild ID
     * @param key     the key name
     * @return a storage key with guild scope
     */
    public static StorageKey guild(String guildId, String key) {
        return new StorageKey(guildScope(guildId), key);
    }

    /**
     * Creates a user-guild storage key.
     *
     * @param userId  the user ID
     * @param guildId the guild ID
     * @param key     the key name
     * @return a storage key with user-guild scope
     */
    public static StorageKey userGuild(String userId, String guildId, String key) {
        return new StorageKey(userGuildScope(userId, guildId), key);
    }

    /**
     * Gets the full key string.
     *
     * @return the formatted key
     */
    @NotNull
    public String toString() {
        return scope + ":" + key;
    }

    /**
     * Gets the scope part of this key.
     *
     * @return the scope
     */
    public String getScope() {
        return scope;
    }

    /**
     * Gets the key name part of this key.
     *
     * @return the key name
     */
    public String getKey() {
        return key;
    }
}