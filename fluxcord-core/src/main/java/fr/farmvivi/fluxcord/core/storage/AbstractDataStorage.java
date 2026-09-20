package fr.farmvivi.fluxcord.core.storage;

import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.storage.DataStorage;
import fr.farmvivi.fluxcord.api.storage.StorageKey;
import fr.farmvivi.fluxcord.api.storage.events.StorageGetEvent;
import fr.farmvivi.fluxcord.api.storage.events.StorageRemoveEvent;
import fr.farmvivi.fluxcord.api.storage.events.StorageSetEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract base implementation of DataStorage.
 * Provides common functionality and event firing for all storage implementations.
 */
public abstract class AbstractDataStorage implements DataStorage {
    protected static final Logger logger = LoggerFactory.getLogger(AbstractDataStorage.class);
    protected final String storageType;
    protected final EventManager eventManager;
    protected final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    /**
     * Creates a new abstract data storage.
     *
     * @param storageType  the type of storage (for logging)
     * @param eventManager the event manager, or null if events should not be fired
     */
    protected AbstractDataStorage(String storageType, EventManager eventManager) {
        this.storageType = storageType;
        this.eventManager = eventManager;
    }

    @Override
    public <T> Optional<T> get(StorageKey key, Class<T> type) {
        // Fire pre-get event
        if (eventManager != null) {
            StorageGetEvent event = new StorageGetEvent(key, type, null);
            eventManager.fireEvent(event);

            if (event.isCancelled()) {
                return Optional.ofNullable(type.cast(event.getValue()));
            }
        }

        // Check cache first.
        // IMPORTANT: do not use computeIfAbsent here. Eagerly inserting an empty scope map would
        // shadow lazy-loading backends (e.g. FileDataStorage loads the scope from disk only when
        // its cache entry is still absent), causing cold-start reads of previously persisted data
        // to wrongly return empty.
        String scope = key.getScope();
        String keyName = key.getKey();
        Map<String, Object> scopeCache = cache.get(scope);

        if (scopeCache != null && scopeCache.containsKey(keyName)) {
            Object cached = scopeCache.get(keyName);
            // Only serve from cache when the cached object already matches the requested type.
            // A lazy backend may have cached raw JSON values (e.g. numbers as Double); in that case
            // we fall through to doGet so the backend can convert the value to the requested type.
            if (cached == null || type.isInstance(cached)) {
                T value = type.cast(cached);

                // Fire post-get event
                if (eventManager != null) {
                    StorageGetEvent event = new StorageGetEvent(key, type, value);
                    eventManager.fireEvent(event);

                    if (event.getValue() != value) {
                        return Optional.ofNullable(type.cast(event.getValue()));
                    }
                }

                return Optional.ofNullable(value);
            }
        }

        // Get from backend (this lets lazy backends load and cache the scope from persistent storage)
        Optional<T> result = doGet(key, type);

        // Update cache if found
        result.ifPresent(value ->
                cache.computeIfAbsent(scope, k -> new ConcurrentHashMap<>()).put(keyName, value));

        // Fire post-get event
        if (eventManager != null && result.isPresent()) {
            StorageGetEvent event = new StorageGetEvent(key, type, result.get());
            eventManager.fireEvent(event);

            if (event.getValue() != result.get()) {
                T newValue = type.cast(event.getValue());
                if (newValue != null) {
                    cache.computeIfAbsent(scope, k -> new ConcurrentHashMap<>()).put(keyName, newValue);
                }
                return Optional.ofNullable(newValue);
            }
        }

        return result;
    }

    @Override
    public <T> boolean set(StorageKey key, T value) {
        // Fire pre-set event
        if (eventManager != null) {
            StorageSetEvent event = new StorageSetEvent(key, value);
            eventManager.fireEvent(event);

            if (event.isCancelled()) {
                return false;
            }

            // Use potentially modified value
            value = (T) event.getValue();
        }

        // Backend first: a lazy backend (FileDataStorage) populates the scope cache from disk in doSet,
        // and creating an empty scope entry here beforehand would make it skip that load and later
        // persist only this key, wiping the rest of the scope. Cache only what the backend accepted.
        if (!doSet(key, value)) {
            return false;
        }
        cache.computeIfAbsent(key.getScope(), k -> new ConcurrentHashMap<>()).put(key.getKey(), value);
        return true;
    }

    @Override
    public boolean exists(StorageKey key) {
        // Check cache first
        String scope = key.getScope();
        String keyName = key.getKey();
        Map<String, Object> scopeCache = cache.get(scope);

        if (scopeCache != null && scopeCache.containsKey(keyName)) {
            return true;
        }

        // Check backend
        return doExists(key);
    }

    @Override
    public boolean remove(StorageKey key) {
        // Fire pre-remove event
        if (eventManager != null) {
            StorageRemoveEvent event = new StorageRemoveEvent(key);
            eventManager.fireEvent(event);

            if (event.isCancelled()) {
                return false;
            }
        }

        // Remove from cache
        String scope = key.getScope();
        String keyName = key.getKey();
        Map<String, Object> scopeCache = cache.get(scope);

        if (scopeCache != null) {
            scopeCache.remove(keyName);
        }

        // Remove from backend
        return doRemove(key);
    }

    @Override
    public Set<String> getKeys(String scope) {
        // Get from backend first
        Set<String> keys = doGetKeys(scope);

        // Add keys from cache
        Map<String, Object> scopeCache = cache.get(scope);
        if (scopeCache != null) {
            keys.addAll(scopeCache.keySet());
        }

        return keys;
    }

    @Override
    public Map<String, Object> getAll(String scope) {
        // Get from backend first
        Map<String, Object> result = doGetAll(scope);

        // Add/override with cache
        Map<String, Object> scopeCache = cache.get(scope);
        if (scopeCache != null) {
            result.putAll(scopeCache);
        }

        return result;
    }

    @Override
    public boolean clear(String scope) {
        // Remove from cache
        cache.remove(scope);

        // Clear from backend
        return doClear(scope);
    }

    @Override
    public boolean save() {
        // Implement in subclasses if needed
        return true;
    }

    /**
     * Gets a value from the backend storage.
     *
     * @param key  the storage key
     * @param type the class representing the return type
     * @param <T>  the return type
     * @return the value wrapped in an Optional
     */
    protected abstract <T> Optional<T> doGet(StorageKey key, Class<T> type);

    /**
     * Sets a value in the backend storage.
     *
     * @param key   the storage key
     * @param value the value to store
     * @param <T>   the value type
     * @return true if the operation was successful
     */
    protected abstract <T> boolean doSet(StorageKey key, T value);

    /**
     * Checks if a key exists in the backend storage.
     *
     * @param key the storage key
     * @return true if the key exists
     */
    protected abstract boolean doExists(StorageKey key);

    /**
     * Removes a key from the backend storage.
     *
     * @param key the storage key
     * @return true if the key was removed
     */
    protected abstract boolean doRemove(StorageKey key);

    /**
     * Gets all keys with a specific scope from the backend storage.
     *
     * @param scope the scope prefix
     * @return a set of keys
     */
    protected abstract Set<String> doGetKeys(String scope);

    /**
     * Gets all key-value pairs with a specific scope from the backend storage.
     *
     * @param scope the scope prefix
     * @return a map of keys to values
     */
    protected abstract Map<String, Object> doGetAll(String scope);

    /**
     * Clears all data with a specific scope from the backend storage.
     *
     * @param scope the scope prefix
     * @return true if the operation was successful
     */
    protected abstract boolean doClear(String scope);
}