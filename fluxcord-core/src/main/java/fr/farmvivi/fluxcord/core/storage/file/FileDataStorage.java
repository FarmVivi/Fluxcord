package fr.farmvivi.fluxcord.core.storage.file;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.storage.StorageKey;
import fr.farmvivi.fluxcord.core.storage.AbstractDataStorage;
import fr.farmvivi.fluxcord.core.storage.StorageJson;
import fr.farmvivi.fluxcord.core.util.Debouncer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-based implementation of DataStorage.
 * Stores data in JSON files organized by scope.
 */
public class FileDataStorage extends AbstractDataStorage {
    private static final Logger logger = LoggerFactory.getLogger(FileDataStorage.class);
    private static final String DATA_FILENAME = "data.json";
    private static final Gson gson = StorageJson.pretty();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private final File baseDirectory;
    private final Map<String, Debouncer> saveThrottlers = new ConcurrentHashMap<>();
    private final long saveDebounceMs;

    /**
     * Creates a new file data storage.
     *
     * @param baseDirectory  the base directory for storing data files
     * @param eventManager   the event manager
     * @param saveDebounceMs debounce time for saving in milliseconds (to avoid too frequent writes)
     */
    public FileDataStorage(File baseDirectory, EventManager eventManager, long saveDebounceMs) {
        super("file", eventManager);
        this.baseDirectory = baseDirectory;
        this.saveDebounceMs = saveDebounceMs;

        if (!baseDirectory.exists()) {
            if (!baseDirectory.mkdirs()) {
                logger.error("Failed to create base directory: {}", baseDirectory.getAbsolutePath());
            }
        }
    }

    @Override
    protected <T> Optional<T> doGet(StorageKey key, Class<T> type) {
        String scope = key.getScope();
        String keyName = key.getKey();

        // Get the scope data
        Map<String, Object> scopeData = loadScopeData(scope);
        if (scopeData.containsKey(keyName)) {
            try {
                // Re-type the cached/loaded object (a Map/Long/Double after a JSON load) into the requested type
                return Optional.ofNullable(StorageJson.convert(scopeData.get(keyName), type));
            } catch (Exception e) {
                logger.error("[{}] Error converting data for key {} in scope {}: {}",
                        storageType, keyName, scope, e.getMessage());
            }
        }

        return Optional.empty();
    }

    @Override
    protected <T> boolean doSet(StorageKey key, T value) {
        String scope = key.getScope();
        String keyName = key.getKey();

        // Get or load the scope data
        Map<String, Object> scopeData = loadScopeData(scope);

        // Set the value
        scopeData.put(keyName, value);

        // Schedule a save
        scheduleSave(scope);

        return true;
    }

    @Override
    protected boolean doExists(StorageKey key) {
        String scope = key.getScope();
        String keyName = key.getKey();

        // Check if the key exists in the scope data
        Map<String, Object> scopeData = loadScopeData(scope);
        return scopeData.containsKey(keyName);
    }

    @Override
    protected boolean doRemove(StorageKey key) {
        String scope = key.getScope();
        String keyName = key.getKey();

        // Get the scope data
        Map<String, Object> scopeData = loadScopeData(scope);
        if (scopeData.containsKey(keyName)) {
            // Remove the key
            scopeData.remove(keyName);

            // Schedule a save
            scheduleSave(scope);

            return true;
        }

        return false;
    }

    @Override
    protected Set<String> doGetKeys(String scope) {
        // Get the scope data
        Map<String, Object> scopeData = loadScopeData(scope);
        return new HashSet<>(scopeData.keySet());
    }

    @Override
    protected Map<String, Object> doGetAll(String scope) {
        // Get the scope data
        return new HashMap<>(loadScopeData(scope));
    }

    @Override
    protected boolean doClear(String scope) {
        // A debounced write for this scope may still be pending or in flight: drop it, otherwise it would either
        // hold the file open while we delete it (Windows: delete fails) or resurrect the scope right after
        Debouncer pending = saveThrottlers.remove(scope);
        if (pending != null) {
            pending.shutdown();
        }

        // Clear the scope data
        File scopeDir = getScopeDirectory(scope);
        File dataFile = new File(scopeDir, DATA_FILENAME);

        if (dataFile.exists()) {
            if (!dataFile.delete()) {
                logger.error("[{}] Failed to delete data file for scope: {}", storageType, scope);
                return false;
            }
        }

        // If scope directory is empty, delete it too
        if (scopeDir.exists() && scopeDir.list() != null && scopeDir.list().length == 0) {
            if (!scopeDir.delete()) {
                logger.warn("[{}] Failed to delete empty scope directory: {}", storageType, scope);
            }
        }

        return true;
    }

    @Override
    public boolean save() {
        boolean success = true;

        // Save all scopes
        for (String scope : new HashSet<>(cache.keySet())) {
            if (!saveScopeData(scope)) {
                success = false;
            }
        }

        return success;
    }

    @Override
    public boolean close() {
        // Stop the debounced writers first: cancel pending saves and wait for in-flight ones, so the
        // synchronous save below cannot race with a background write on the same data file.
        for (Debouncer debouncer : saveThrottlers.values()) {
            debouncer.shutdown();
        }
        saveThrottlers.clear();

        // Then flush everything that is still in memory
        return save();
    }

    /**
     * Gets the directory for a specific scope.
     *
     * @param scope the scope
     * @return the scope directory
     */
    private File getScopeDirectory(String scope) {
        // Convert scope to directory path
        String dirPath = scope.replace(':', '/');
        File scopeDir = new File(baseDirectory, dirPath);

        // Create directory if it doesn't exist. mkdirs() returns false when another thread created it
        // first (e.g. a debounced save racing with close()): only report a real failure.
        if (!scopeDir.exists()) {
            if (!scopeDir.mkdirs() && !scopeDir.isDirectory()) {
                logger.error("[{}] Failed to create scope directory: {}", storageType, scope);
            }
        }

        return scopeDir;
    }

    /**
     * Loads data for a specific scope.
     *
     * @param scope the scope
     * @return the scope data
     */
    private Map<String, Object> loadScopeData(String scope) {
        // Check cache first
        Map<String, Object> cachedData = cache.get(scope);
        if (cachedData != null) {
            return cachedData;
        }

        // Load from file
        File scopeDir = getScopeDirectory(scope);
        File dataFile = new File(scopeDir, DATA_FILENAME);

        // Concurrent: this map is mutated by callers while the debounced save serializes it.
        // Null values are not storable (ConcurrentHashMap) — use remove() instead of set(null).
        Map<String, Object> data = new ConcurrentHashMap<>();

        if (dataFile.exists()) {
            try (FileReader reader = new FileReader(dataFile)) {
                Map<String, Object> loadedData = gson.fromJson(reader, MAP_TYPE);
                if (loadedData != null) {
                    loadedData.forEach((k, v) -> {
                        if (v != null) {
                            data.put(k, v);
                        }
                    });
                }
            } catch (IOException | RuntimeException e) {
                // Unreadable or malformed JSON (JsonParseException is unchecked): keep a copy aside
                // and start the scope empty rather than crashing every access to it.
                File backup = new File(scopeDir, DATA_FILENAME + ".corrupt");
                boolean kept = dataFile.renameTo(backup);
                logger.error("[{}] Error loading data for scope {} ({}); {}", storageType, scope, e.getMessage(),
                        kept ? "file moved to " + backup.getName() : "file will be overwritten on next save");
            }
        }

        // Cache the loaded data
        cache.put(scope, data);

        return data;
    }

    /**
     * Saves data for a specific scope.
     *
     * @param scope the scope
     * @return true if the save was successful
     */
    private boolean saveScopeData(String scope) {
        Map<String, Object> scopeData = cache.get(scope);
        if (scopeData == null) {
            return true; // Scope never loaded (or cleared): nothing to save
        }
        File scopeDir = getScopeDirectory(scope);
        File dataFile = new File(scopeDir, DATA_FILENAME);
        // An empty map is still written when a file exists: it may be the result of removing the
        // last key, and the previous content must not come back on the next start. Scopes that were
        // only read (never had a file) are not materialised.
        if (scopeData.isEmpty() && !dataFile.exists()) {
            return true;
        }

        try (FileWriter writer = new FileWriter(dataFile)) {
            gson.toJson(scopeData, writer);
            return true;
        } catch (IOException e) {
            logger.error("[{}] Error saving data for scope {}: {}",
                    storageType, scope, e.getMessage());
            return false;
        }
    }

    /**
     * Schedules a save operation for a specific scope with debouncing.
     *
     * @param scope the scope
     */
    private void scheduleSave(String scope) {
        // Get or create a debouncer for this scope
        Debouncer debouncer = saveThrottlers.computeIfAbsent(scope,
                s -> new Debouncer(saveDebounceMs, () -> saveScopeData(s)));

        // Trigger the debouncer
        debouncer.debounce();
    }
}