package fr.farmvivi.fluxcord.core.permissions;

import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.permissions.PermissionManager;
import fr.farmvivi.fluxcord.api.permissions.events.PermissionChangeEvent;
import fr.farmvivi.fluxcord.api.permissions.events.PermissionCheckEvent;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.api.storage.UserGuildStorage;
import fr.farmvivi.fluxcord.api.storage.UserStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

/**
 * Permission resolution: explicit user-guild override → explicit user override → the registered default
 * ({@code TRUE}/{@code FALSE}/{@code OP}/{@code NOT_OP}) → {@code false} for unknown permissions.
 *
 * <p>Only the stored overrides are cached (a storage read each); defaults are evaluated on every check so
 * that operator changes and plugin re-registrations apply immediately.
 *
 * <p>Operators: the IDs listed in the core configuration ({@code permissions.operators}). Within a guild,
 * a {@link #setGuildOperatorResolver resolver} (wired by the core once Discord is connected) can also grant
 * operator status to the guild owner and administrators.
 */
public class SimplePermissionManager implements PermissionManager {
    private static final Logger logger = LoggerFactory.getLogger(SimplePermissionManager.class);
    private static final String PERMISSION_KEY_PREFIX = "permission.";

    private final EventManager eventManager;
    private final DataStorageManager dataStorageManager;
    private final Set<String> configuredOperators;
    private volatile BiPredicate<String, String> guildOperatorResolver = (userId, guildId) -> false;

    // Maps permission name to Permission object
    private final Map<String, Permission> registeredPermissions = new ConcurrentHashMap<>();
    // Maps permission name to owning plugin
    private final Map<String, Plugin> permissionOwners = new ConcurrentHashMap<>();
    // Maps plugin to its permissions
    private final Map<Plugin, Set<Permission>> pluginPermissions = new ConcurrentHashMap<>();

    // Caches of the *stored* overrides: userId -> permission -> Optional(value) (empty = nothing stored)
    private final Map<String, Map<String, Optional<Boolean>>> userOverrides = new ConcurrentHashMap<>();
    // userId -> guildId -> permission -> Optional(value)
    private final Map<String, Map<String, Map<String, Optional<Boolean>>>> userGuildOverrides = new ConcurrentHashMap<>();

    public SimplePermissionManager(EventManager eventManager, DataStorageManager dataStorageManager) {
        this(eventManager, dataStorageManager, Set.of());
    }

    /**
     * @param configuredOperators user IDs that are operators for the whole process (from the core config)
     */
    public SimplePermissionManager(EventManager eventManager, DataStorageManager dataStorageManager,
                                   Collection<String> configuredOperators) {
        this.eventManager = eventManager;
        this.dataStorageManager = dataStorageManager;
        this.configuredOperators = Set.copyOf(configuredOperators);
        if (!this.configuredOperators.isEmpty()) {
            logger.info("{} operator(s) configured", this.configuredOperators.size());
        }
    }

    /**
     * Installs the guild-level operator check (e.g. "is guild owner or has ADMINISTRATOR"). The core calls
     * this once Discord is connected; until then only global operators exist.
     */
    public void setGuildOperatorResolver(BiPredicate<String, String> resolver) {
        this.guildOperatorResolver = resolver != null ? resolver : (userId, guildId) -> false;
    }

    // --- registry -------------------------------------------------------------------------------

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
        pluginPermissions.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet()).add(permission);

        logger.debug("Registered permission {} owned by plugin {}", permName, owner.getName());
    }

    @Override
    public Set<Permission> getRegisteredPermissions() {
        return new HashSet<>(registeredPermissions.values());
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
        Set<Permission> permissions = pluginPermissions.remove(plugin);
        if (permissions == null || permissions.isEmpty()) {
            return 0;
        }
        for (Permission permission : permissions) {
            registeredPermissions.remove(permission.getName());
            permissionOwners.remove(permission.getName());
        }
        logger.debug("Unregistered {} permissions for plugin {}", permissions.size(), plugin.getName());
        return permissions.size();
    }

    // --- checks ---------------------------------------------------------------------------------

    @Override
    public boolean hasPermission(String userId, String permission) {
        if (userId == null || permission == null) {
            return false;
        }
        return check(userId, null, permission);
    }

    @Override
    public boolean hasPermission(String userId, String guildId, String permission) {
        if (userId == null || guildId == null || permission == null) {
            return false;
        }
        return check(userId, guildId, permission);
    }

    private boolean check(String userId, String guildId, String permission) {
        // Fire event to allow interception (one per command: skip the allocation when nobody listens)
        if (eventManager.hasListeners(PermissionCheckEvent.class)) {
            PermissionCheckEvent event = new PermissionCheckEvent(userId, guildId, permission, false);
            eventManager.fireEvent(event);
            if (event.isCancelled()) {
                return event.getResult();
            }
        }
        return resolve(userId, guildId, permission);
    }

    /** Override chain then default, without events. */
    private boolean resolve(String userId, String guildId, String permission) {
        if (guildId != null) {
            Optional<Boolean> guildValue = storedUserGuildValue(userId, guildId, permission);
            if (guildValue.isPresent()) {
                return guildValue.get();
            }
        }
        Optional<Boolean> userValue = storedUserValue(userId, permission);
        if (userValue.isPresent()) {
            return userValue.get();
        }
        Permission registered = registeredPermissions.get(permission);
        return registered != null && getDefaultValueFor(registered.getDefault(), userId, guildId);
    }

    private Optional<Boolean> storedUserValue(String userId, String permission) {
        return userOverrides.computeIfAbsent(userId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(permission, p -> userStorage(userId).get(PERMISSION_KEY_PREFIX + p, Boolean.class));
    }

    private Optional<Boolean> storedUserGuildValue(String userId, String guildId, String permission) {
        return userGuildOverrides.computeIfAbsent(userId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(guildId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(permission, p -> userGuildStorage(userId, guildId).get(PERMISSION_KEY_PREFIX + p, Boolean.class));
    }

    private boolean getDefaultValueFor(PermissionDefault defaultValue, String userId, String guildId) {
        return switch (defaultValue) {
            case TRUE -> true;
            case OP -> guildId != null ? isOperator(userId, guildId) : isOperator(userId);
            case NOT_OP -> !(guildId != null ? isOperator(userId, guildId) : isOperator(userId));
            case FALSE -> false;
        };
    }

    // --- overrides ------------------------------------------------------------------------------

    @Override
    public void setPermission(String userId, String permission, boolean value) {
        if (userId == null || permission == null) {
            return;
        }
        boolean oldValue = resolve(userId, null, permission);
        eventManager.fireEvent(new PermissionChangeEvent(userId, null, permission, oldValue, value));

        userStorage(userId).set(PERMISSION_KEY_PREFIX + permission, value);
        userOverrides.computeIfAbsent(userId, id -> new ConcurrentHashMap<>()).put(permission, Optional.of(value));
        logger.debug("Set permission {} for user {} to {}", permission, userId, value);
    }

    @Override
    public void setPermission(String userId, String guildId, String permission, boolean value) {
        if (userId == null || guildId == null || permission == null) {
            return;
        }
        boolean oldValue = resolve(userId, guildId, permission);
        eventManager.fireEvent(new PermissionChangeEvent(userId, guildId, permission, oldValue, value));

        userGuildStorage(userId, guildId).set(PERMISSION_KEY_PREFIX + permission, value);
        userGuildOverrides.computeIfAbsent(userId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(guildId, id -> new ConcurrentHashMap<>())
                .put(permission, Optional.of(value));
        logger.debug("Set permission {} for user {} in guild {} to {}", permission, userId, guildId, value);
    }

    @Override
    public void clearPermissions(String userId) {
        if (userId == null) {
            return;
        }
        UserStorage storage = userStorage(userId);
        for (String key : storage.getKeys()) {
            if (key.startsWith(PERMISSION_KEY_PREFIX)) {
                storage.remove(key);
            }
        }
        userOverrides.remove(userId);
        logger.debug("Cleared all permissions for user {}", userId);
    }

    @Override
    public void clearPermissions(String userId, String guildId) {
        if (userId == null || guildId == null) {
            return;
        }
        UserGuildStorage storage = userGuildStorage(userId, guildId);
        for (String key : storage.getKeys()) {
            if (key.startsWith(PERMISSION_KEY_PREFIX)) {
                storage.remove(key);
            }
        }
        Map<String, Map<String, Optional<Boolean>>> guilds = userGuildOverrides.get(userId);
        if (guilds != null) {
            guilds.remove(guildId);
        }
        logger.debug("Cleared all permissions for user {} in guild {}", userId, guildId);
    }

    @Override
    public boolean unsetPermission(String userId, String permission) {
        if (userId == null || permission == null) {
            return false;
        }
        boolean removed = userStorage(userId).remove(PERMISSION_KEY_PREFIX + permission);
        Map<String, Optional<Boolean>> perms = userOverrides.get(userId);
        if (perms != null) {
            perms.remove(permission);
        }
        return removed;
    }

    @Override
    public boolean unsetPermission(String userId, String guildId, String permission) {
        if (userId == null || guildId == null || permission == null) {
            return false;
        }
        boolean removed = userGuildStorage(userId, guildId).remove(PERMISSION_KEY_PREFIX + permission);
        Map<String, Map<String, Optional<Boolean>>> guilds = userGuildOverrides.get(userId);
        if (guilds != null && guilds.get(guildId) != null) {
            guilds.get(guildId).remove(permission);
        }
        return removed;
    }

    @Override
    public Map<String, Boolean> getUserPermissions(String userId) {
        if (userId == null) {
            return Collections.emptyMap();
        }
        return storedPermissions(userStorage(userId).getKeys(), key -> userStorage(userId).get(key, Boolean.class));
    }

    @Override
    public Map<String, Boolean> getUserGuildPermissions(String userId, String guildId) {
        if (userId == null || guildId == null) {
            return Collections.emptyMap();
        }
        return storedPermissions(userGuildStorage(userId, guildId).getKeys(),
                key -> userGuildStorage(userId, guildId).get(key, Boolean.class));
    }

    private static Map<String, Boolean> storedPermissions(Set<String> keys, java.util.function.Function<String, Optional<Boolean>> reader) {
        Map<String, Boolean> permissions = new HashMap<>();
        for (String key : keys) {
            if (key.startsWith(PERMISSION_KEY_PREFIX)) {
                reader.apply(key).ifPresent(value -> permissions.put(key.substring(PERMISSION_KEY_PREFIX.length()), value));
            }
        }
        return permissions;
    }

    // --- operators ------------------------------------------------------------------------------

    @Override
    public boolean isOperator(String userId) {
        return userId != null && configuredOperators.contains(userId);
    }

    @Override
    public boolean isOperator(String userId, String guildId) {
        if (isOperator(userId)) {
            return true;
        }
        if (userId == null || guildId == null) {
            return false;
        }
        try {
            return guildOperatorResolver.test(userId, guildId);
        } catch (RuntimeException e) {
            logger.warn("Guild operator resolver failed for user {} in guild {}", userId, guildId, e);
            return false;
        }
    }


    // --- misc -----------------------------------------------------------------------------------

    /** Drops the cached overrides; needed only after the storage was modified behind the manager's back. */
    public void clearCaches() {
        userOverrides.clear();
        userGuildOverrides.clear();
        logger.debug("Cleared permission caches");
    }

    private UserStorage userStorage(String userId) {
        return dataStorageManager.getUserStorage(userId);
    }

    private UserGuildStorage userGuildStorage(String userId, String guildId) {
        return dataStorageManager.getUserGuildStorage(userId, guildId);
    }

}
