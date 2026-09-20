package fr.farmvivi.fluxcord.core.permissions;

import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.api.permissions.events.PermissionChangeEvent;
import fr.farmvivi.fluxcord.api.permissions.events.PermissionCheckEvent;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Implementation of the PermissionManager interface.
 * This manages permissions for users globally and per-guild.
 */
public class SimplePermissionManager implements PermissionManager {
    private static final Logger logger = LoggerFactory.getLogger(SimplePermissionManager.class);
    private static final String PERMISSION_KEY_PREFIX = "permission.";

    private final EventManager eventManager;
    private final DataStorageManager dataStorageManager;

    // Maps permission name to Permission object
    private final Map<String, Permission> registeredPermissions = new ConcurrentHashMap<>();

    // Maps permission name to owning plugin
    private final Map<String, Plugin> permissionOwners = new ConcurrentHashMap<>();

    // Maps plugin to its permissions
    private final Map<Plugin, Set<Permission>> pluginPermissions = new ConcurrentHashMap<>();

    // Cache for performance
    private final Map<String, Map<String, Boolean>> userPermissionCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Map<String, Boolean>>> userGuildPermissionCache = new ConcurrentHashMap<>();

    /**
     * Creates a new permission manager.
     *
     * @param eventManager       the event manager
     * @param dataStorageManager the data storage manager
     */
    public SimplePermissionManager(EventManager eventManager, DataStorageManager dataStorageManager) {
        this.eventManager = eventManager;
        this.dataStorageManager = dataStorageManager;
    }

    @Override
    public void registerPermission(Permission permission, Plugin owner) {
        if (permission == null) {
            throw new IllegalArgumentException("Permission cannot be null");
        }
        if (owner == null) {
            throw new IllegalArgumentException("Owner plugin cannot be null");
        }

        String permName = permission.getName();
        if (registeredPermissions.containsKey(permName)) {
            logger.warn("Permission {} is already registered by plugin {}",
                    permName, permissionOwners.get(permName).getName());
            return;
        }

        registeredPermissions.put(permName, permission);
        permissionOwners.put(permName, owner);

        // Add to plugin permissions map
        pluginPermissions.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet())
                .add(permission);

        // Cached results may have been derived from "unregistered → false": drop them
        invalidateCachedResults(permName);

        logger.debug("Registered permission {} owned by plugin {}",
                permName, owner.getName());
    }

    /**
     * Removes every cached result for one permission, in every user and user-guild cache. Called when the
     * permission's default changes (register/unregister), since defaults are cached like overrides.
     */
    private void invalidateCachedResults(String permission) {
        userPermissionCache.values().forEach(perms -> perms.remove(permission));
        userGuildPermissionCache.values().forEach(guilds -> guilds.values().forEach(perms -> perms.remove(permission)));
    }

    @Override
    public boolean hasPermission(String userId, String permission) {
        if (userId == null || permission == null) {
            return false;
        }

        // Fire event to allow interception (one per command: skip the allocation when nobody listens)
        if (eventManager.hasListeners(PermissionCheckEvent.class)) {
            PermissionCheckEvent event = new PermissionCheckEvent(userId, null, permission, false);
            eventManager.fireEvent(event);
            if (event.isCancelled()) {
                return event.getResult();
            }
        }

        // Check cache first
        Map<String, Boolean> userPerms = userPermissionCache.computeIfAbsent(userId,
                id -> new ConcurrentHashMap<>());

        if (userPerms.containsKey(permission)) {
            return userPerms.get(permission);
        }

        // Check storage
        Boolean storedValue = dataStorageManager.getUserStorage(userId)
                .get(PERMISSION_KEY_PREFIX + permission, Boolean.class)
                .orElse(null);

        boolean result;
        if (storedValue != null) {
            result = storedValue;
        } else {
            // Check default
            Permission registeredPerm = registeredPermissions.get(permission);
            if (registeredPerm != null) {
                result = getDefaultValueFor(registeredPerm.getDefault(), userId, null);
            } else {
                result = false;
            }
        }

        // Cache result
        userPerms.put(permission, result);
        return result;
    }

    @Override
    public boolean hasPermission(String userId, String guildId, String permission) {
        if (userId == null || guildId == null || permission == null) {
            return false;
        }

        // Fire event to allow interception (one per command: skip the allocation when nobody listens)
        if (eventManager.hasListeners(PermissionCheckEvent.class)) {
            PermissionCheckEvent event = new PermissionCheckEvent(userId, guildId, permission, false);
            eventManager.fireEvent(event);
            if (event.isCancelled()) {
                return event.getResult();
            }
        }

        // Check guild-specific permission first
        Map<String, Map<String, Boolean>> userGuilds = userGuildPermissionCache
                .computeIfAbsent(userId, id -> new ConcurrentHashMap<>());

        Map<String, Boolean> guildPerms = userGuilds
                .computeIfAbsent(guildId, id -> new ConcurrentHashMap<>());

        if (guildPerms.containsKey(permission)) {
            return guildPerms.get(permission);
        }

        // Check storage
        Boolean storedValue = dataStorageManager.getUserGuildStorage(userId, guildId)
                .get(PERMISSION_KEY_PREFIX + permission, Boolean.class)
                .orElse(null);

        if (storedValue != null) {
            guildPerms.put(permission, storedValue);
            return storedValue;
        }

        // Fall back to global permission
        return hasPermission(userId, permission);
    }

    @Override
    public void setPermission(String userId, String permission, boolean value) {
        if (userId == null || permission == null) {
            return;
        }

        // Get current value for event
        boolean oldValue = hasPermission(userId, permission);

        // Fire event
        PermissionChangeEvent event = new PermissionChangeEvent(
                userId, null, permission, oldValue, value);
        eventManager.fireEvent(event);

        // Store new value
        dataStorageManager.getUserStorage(userId)
                .set(PERMISSION_KEY_PREFIX + permission, value);

        // Update cache
        Map<String, Boolean> userPerms = userPermissionCache.computeIfAbsent(userId,
                id -> new ConcurrentHashMap<>());
        userPerms.put(permission, value);

        logger.debug("Set permission {} for user {} to {}", permission, userId, value);
    }

    @Override
    public void setPermission(String userId, String guildId, String permission, boolean value) {
        if (userId == null || guildId == null || permission == null) {
            return;
        }

        // Get current value for event
        boolean oldValue = hasPermission(userId, guildId, permission);

        // Fire event
        PermissionChangeEvent event = new PermissionChangeEvent(
                userId, guildId, permission, oldValue, value);
        eventManager.fireEvent(event);

        // Store new value
        dataStorageManager.getUserGuildStorage(userId, guildId)
                .set(PERMISSION_KEY_PREFIX + permission, value);

        // Update cache
        Map<String, Map<String, Boolean>> userGuilds = userGuildPermissionCache
                .computeIfAbsent(userId, id -> new ConcurrentHashMap<>());

        Map<String, Boolean> guildPerms = userGuilds
                .computeIfAbsent(guildId, id -> new ConcurrentHashMap<>());

        guildPerms.put(permission, value);

        logger.debug("Set permission {} for user {} in guild {} to {}",
                permission, userId, guildId, value);
    }

    @Override
    public void clearPermissions(String userId) {
        if (userId == null) {
            return;
        }

        // Get all permission keys for this user
        Set<String> keys = dataStorageManager.getUserStorage(userId).getKeys();
        List<String> permKeys = keys.stream()
                .filter(key -> key.startsWith(PERMISSION_KEY_PREFIX))
                .collect(Collectors.toList());

        // Remove each permission
        for (String key : permKeys) {
            dataStorageManager.getUserStorage(userId).remove(key);
        }

        // Clear cache
        userPermissionCache.remove(userId);

        logger.debug("Cleared all permissions for user {}", userId);
    }

    @Override
    public void clearPermissions(String userId, String guildId) {
        if (userId == null || guildId == null) {
            return;
        }

        // Get all permission keys for this user in this guild
        Set<String> keys = dataStorageManager.getUserGuildStorage(userId, guildId)
                .getKeys();

        List<String> permKeys = keys.stream()
                .filter(key -> key.startsWith(PERMISSION_KEY_PREFIX))
                .collect(Collectors.toList());

        // Remove each permission
        for (String key : permKeys) {
            dataStorageManager.getUserGuildStorage(userId, guildId)
                    .remove(key);
        }

        // Clear cache
        Map<String, Map<String, Boolean>> userGuilds = userGuildPermissionCache.get(userId);
        if (userGuilds != null) {
            userGuilds.remove(guildId);
        }

        logger.debug("Cleared all permissions for user {} in guild {}", userId, guildId);
    }

    @Override
    public Set<Permission> getRegisteredPermissions() {
        return new HashSet<>(registeredPermissions.values());
    }

    @Override
    public Map<String, Boolean> getUserPermissions(String userId) {
        if (userId == null) {
            return Collections.emptyMap();
        }

        // Get from storage
        Set<String> keys = dataStorageManager.getUserStorage(userId).getKeys();
        Map<String, Boolean> permissions = new HashMap<>();

        // Process only permission keys
        for (String key : keys) {
            if (key.startsWith(PERMISSION_KEY_PREFIX)) {
                String permName = key.substring(PERMISSION_KEY_PREFIX.length());
                Boolean value = dataStorageManager.getUserStorage(userId)
                        .get(key, Boolean.class)
                        .orElse(null);

                if (value != null) {
                    permissions.put(permName, value);
                }
            }
        }

        return permissions;
    }

    @Override
    public Map<String, Boolean> getUserGuildPermissions(String userId, String guildId) {
        if (userId == null || guildId == null) {
            return Collections.emptyMap();
        }

        // Get from storage
        Set<String> keys = dataStorageManager.getUserGuildStorage(userId, guildId)
                .getKeys();

        Map<String, Boolean> permissions = new HashMap<>();

        // Process only permission keys
        for (String key : keys) {
            if (key.startsWith(PERMISSION_KEY_PREFIX)) {
                String permName = key.substring(PERMISSION_KEY_PREFIX.length());
                Boolean value = dataStorageManager.getUserGuildStorage(userId, guildId)
                        .get(key, Boolean.class)
                        .orElse(null);

                if (value != null) {
                    permissions.put(permName, value);
                }
            }
        }

        return permissions;
    }

    @Override
    public Permission getPermission(String name) {
        return registeredPermissions.get(name);
    }

    @Override
    public Set<Permission> getPermissions(Plugin plugin) {
        if (plugin == null) {
            return Collections.emptySet();
        }

        Set<Permission> permissions = pluginPermissions.get(plugin);
        return permissions != null ? Collections.unmodifiableSet(permissions) : Collections.emptySet();
    }

    @Override
    public int unregisterPermissions(Plugin plugin) {
        if (plugin == null) {
            return 0;
        }

        Set<Permission> permissions = pluginPermissions.get(plugin);
        if (permissions == null || permissions.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (Permission permission : new HashSet<>(permissions)) {
            String name = permission.getName();
            registeredPermissions.remove(name);
            permissionOwners.remove(name);
            invalidateCachedResults(name);
            count++;
        }

        pluginPermissions.remove(plugin);
        logger.debug("Unregistered {} permissions for plugin {}", count, plugin.getName());

        return count;
    }

    /**
     * Helper method to resolve default permission values.
     *
     * @param defaultValue the default permission value
     * @param userId       the user ID
     * @param guildId      the guild ID (may be null)
     * @return the resolved boolean value
     */
    private boolean getDefaultValueFor(PermissionDefault defaultValue, String userId, String guildId) {
        // In a more advanced implementation, we'd check if users are "operators"
        // For now, we just use the TRUE/FALSE values directly
        switch (defaultValue) {
            case TRUE:
                return true;
            case OP:
                return isOperator(userId, guildId);
            case NOT_OP:
                return !isOperator(userId, guildId);
            case FALSE:
            default:
                return false;
        }
    }

    /**
     * Checks if a user is an operator.
     * In a more advanced implementation, this would check against a list of operators.
     *
     * @param userId  the user ID
     * @param guildId the guild ID (may be null)
     * @return true if the user is an operator
     */
    private boolean isOperator(String userId, String guildId) {
        // For now, we'll use a simple check against the storage
        if (guildId != null) {
            return dataStorageManager.getUserGuildStorage(userId, guildId)
                    .get("isOperator", Boolean.class)
                    .orElse(false);
        } else {
            return dataStorageManager.getUserStorage(userId)
                    .get("isOperator", Boolean.class)
                    .orElse(false);
        }
    }

    /**
     * Clears all permission caches.
     * This can be used to force a reload of permissions from storage.
     */
    public void clearCaches() {
        userPermissionCache.clear();
        userGuildPermissionCache.clear();
        logger.debug("Cleared permission caches");
    }
}