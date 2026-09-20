package fr.farmvivi.fluxcord.api.storage;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A view of a {@link DataStorage} restricted to one scope (global, a user, a guild or a user inside a guild)
 * and, optionally, to keys carrying a namespace prefix.
 * <p>
 * Without a namespace the view addresses the scope's keys verbatim. With a namespace {@code n}, a key {@code k}
 * is stored as {@code n.k}; {@link #getKeys()} and {@link #getAll()} only return the namespaced keys, with the
 * prefix stripped, and {@link #clear()} only removes them. This is how plugins share one scope without
 * colliding: {@code AbstractPlugin.getPluginDataStorage()} hands out views namespaced by the plugin id, while the
 * core uses un-namespaced views (e.g. {@code commands.prefix} in a guild scope).
 */
public final class ScopedStorage {
    private final DataStorage storage;
    private final String scope;
    private final String prefix;

    /**
     * Creates an un-namespaced view of a scope.
     *
     * @param storage the underlying storage
     * @param scope   the scope string (see {@link StorageKey#userScope(String)} and friends)
     */
    public ScopedStorage(DataStorage storage, String scope) {
        this(storage, scope, "");
    }

    private ScopedStorage(DataStorage storage, String scope, String prefix) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.prefix = prefix;
    }

    /**
     * Returns a view of the same scope whose keys are prefixed with {@code namespace + "."}. Namespaces
     * compose: {@code namespaced("a").namespaced("b")} stores {@code a.b.key}.
     *
     * @param namespace the namespace, typically a plugin id
     * @return the namespaced view
     */
    public ScopedStorage namespaced(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        return new ScopedStorage(storage, scope, prefix + namespace + ".");
    }

    /** The scope string this view addresses. */
    public String getScope() {
        return scope;
    }

    /** The key prefix applied by this view ({@code ""} when un-namespaced, {@code "<namespace>."} otherwise). */
    public String getPrefix() {
        return prefix;
    }

    /**
     * Gets a value.
     *
     * @param key  the key (without prefix)
     * @param type the expected type
     * @param <T>  the value type
     * @return the value, empty when absent or of another type
     */
    public <T> Optional<T> get(String key, Class<T> type) {
        return storage.get(key(key), type);
    }

    /**
     * Stores a value.
     *
     * @param key   the key (without prefix)
     * @param value the value; {@code null} is not storable, use {@link #remove(String)}
     * @param <T>   the value type
     * @return true when the write succeeded
     */
    public <T> boolean set(String key, T value) {
        return storage.set(key(key), value);
    }

    /** @return true when the key exists in this view */
    public boolean exists(String key) {
        return storage.exists(key(key));
    }

    /** @return true when the key was removed */
    public boolean remove(String key) {
        return storage.remove(key(key));
    }

    /** @return the keys visible through this view, without prefix */
    public Set<String> getKeys() {
        Set<String> keys = storage.getKeys(scope);
        if (prefix.isEmpty()) {
            return keys;
        }
        return keys.stream()
                .filter(key -> key.startsWith(prefix))
                .map(key -> key.substring(prefix.length()))
                .collect(Collectors.toSet());
    }

    /** @return the entries visible through this view, keyed without prefix */
    public Map<String, Object> getAll() {
        Map<String, Object> all = storage.getAll(scope);
        if (prefix.isEmpty()) {
            return all;
        }
        return all.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .collect(Collectors.toMap(entry -> entry.getKey().substring(prefix.length()), Map.Entry::getValue));
    }

    /**
     * Removes every key visible through this view: the whole scope when un-namespaced, only the namespaced keys
     * otherwise.
     *
     * @return true when everything was removed
     */
    public boolean clear() {
        if (prefix.isEmpty()) {
            return storage.clear(scope);
        }
        boolean success = true;
        for (String key : getKeys()) {
            success &= remove(key);
        }
        return success;
    }

    private StorageKey key(String key) {
        return new StorageKey(scope, prefix + key);
    }
}
