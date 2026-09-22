package fr.farmvivi.fluxcord.plugins.music.testing;

import fr.farmvivi.fluxcord.api.storage.DataStorage;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.api.storage.ScopedStorage;
import fr.farmvivi.fluxcord.api.storage.StorageKey;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory {@link DataStorage} and {@link DataStorageManager} for the plugin tests: the same
 * scope/key model as the real backends, without a file or a database.
 */
public class MemoryDataStorage implements DataStorage, DataStorageManager {

    private final Map<String, Map<String, Object>> scopes = new LinkedHashMap<>();
    private int saves;

    @Override
    public <T> Optional<T> get(StorageKey key, Class<T> type) {
        Object value = scopes.getOrDefault(key.scope(), Map.of()).get(key.key());
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    @Override
    public <T> boolean set(StorageKey key, T value) {
        scopes.computeIfAbsent(key.scope(), scope -> new LinkedHashMap<>()).put(key.key(), value);
        return true;
    }

    @Override
    public boolean exists(StorageKey key) {
        return scopes.getOrDefault(key.scope(), Map.of()).containsKey(key.key());
    }

    @Override
    public boolean remove(StorageKey key) {
        return scopes.getOrDefault(key.scope(), new LinkedHashMap<>()).remove(key.key()) != null;
    }

    @Override
    public Set<String> getKeys(String scope) {
        return Set.copyOf(scopes.getOrDefault(scope, Map.of()).keySet());
    }

    @Override
    public Map<String, Object> getAll(String scope) {
        return new HashMap<>(scopes.getOrDefault(scope, Map.of()));
    }

    @Override
    public boolean clear(String scope) {
        return scopes.remove(scope) != null;
    }

    @Override
    public boolean save() {
        saves++;
        return true;
    }

    @Override
    public boolean close() {
        return save();
    }

    // DataStorageManager

    @Override
    public ScopedStorage getGlobalStorage() {
        return new ScopedStorage(this, StorageKey.globalScope());
    }

    @Override
    public ScopedStorage getUserStorage(String userId) {
        return new ScopedStorage(this, StorageKey.userScope(userId));
    }

    @Override
    public ScopedStorage getGuildStorage(String guildId) {
        return new ScopedStorage(this, StorageKey.guildScope(guildId));
    }

    @Override
    public ScopedStorage getUserGuildStorage(String userId, String guildId) {
        return new ScopedStorage(this, StorageKey.userGuildScope(userId, guildId));
    }

    @Override
    public boolean saveAll() {
        return save();
    }

    /** @return how many times the storage was flushed, to prove a mutation was persisted */
    public int getSaveCount() {
        return saves;
    }

    /** @return the raw content of a scope, to assert on the storage layout */
    public Map<String, Object> scope(String scope) {
        return getAll(scope);
    }
}
