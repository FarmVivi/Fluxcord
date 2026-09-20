package fr.farmvivi.fluxcord.core.storage;

import fr.farmvivi.fluxcord.api.event.EventHandler;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.plugin.PluginLifecycle;
import fr.farmvivi.fluxcord.api.storage.StorageKey;
import fr.farmvivi.fluxcord.api.storage.events.StorageGetEvent;
import fr.farmvivi.fluxcord.api.storage.events.StorageRemoveEvent;
import fr.farmvivi.fluxcord.api.storage.events.StorageSetEvent;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cache and event semantics of the storage base class, against an in-memory backend that records
 * every call (what a DB backend would see).
 */
class AbstractDataStorageTest {

    /** Non-lazy backend: a plain map per scope, like DatabaseDataStorage (which never touches the cache). */
    static class FakeBackend extends AbstractDataStorage {
        final Map<String, Map<String, Object>> store = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        boolean failWrites;

        FakeBackend(SimpleEventManager events) {
            super("fake", events);
        }

        private Map<String, Object> scope(String scope) {
            return store.computeIfAbsent(scope, s -> new HashMap<>());
        }

        @Override protected <T> Optional<T> doGet(StorageKey key, Class<T> type) {
            calls.add("get " + key.getKey());
            Object v = scope(key.getScope()).get(key.getKey());
            if (v instanceof Number n && type == Long.class) { // a DB backend converts raw values
                return Optional.of(type.cast(n.longValue()));
            }
            return Optional.ofNullable(type.isInstance(v) ? type.cast(v) : null);
        }

        @Override protected <T> boolean doSet(StorageKey key, T value) {
            calls.add("set " + key.getKey());
            if (failWrites) return false;
            scope(key.getScope()).put(key.getKey(), value);
            return true;
        }

        @Override protected boolean doExists(StorageKey key) {
            calls.add("exists " + key.getKey());
            return scope(key.getScope()).containsKey(key.getKey());
        }

        @Override protected boolean doRemove(StorageKey key) {
            calls.add("remove " + key.getKey());
            return scope(key.getScope()).remove(key.getKey()) != null;
        }

        @Override protected Set<String> doGetKeys(String scope) { return new HashSet<>(scope(scope).keySet()); }
        @Override protected Map<String, Object> doGetAll(String scope) { return new HashMap<>(scope(scope)); }
        @Override protected boolean doClear(String scope) { store.remove(scope); return true; }
        @Override public boolean close() { return true; }
    }

    static class StubPlugin implements Plugin {
        private PluginLifecycle lifecycle = PluginLifecycle.LOADED;
        @Override public String getId() { return "test"; }
        @Override public String getName() { return "test"; }
        @Override public String getVersion() { return "1"; }
        @Override public void onLoad(PluginContext context) { }
        @Override public void onEnable() { }
        @Override public void onDisable() { }
        @Override public PluginLifecycle getLifecycle() { return lifecycle; }
        @Override public void setLifecycle(PluginLifecycle lifecycle) { this.lifecycle = lifecycle; }
    }

    private static final StorageKey KEY = StorageKey.user("7", "lang");

    private final SimpleEventManager events = new SimpleEventManager();
    private final FakeBackend storage = new FakeBackend(events);

    @AfterEach
    void shutdown() {
        events.shutdown();
    }

    @Test
    void readsAreServedFromCacheAfterTheFirstBackendHit() {
        storage.store.put("user:7", new HashMap<>(Map.of("lang", "fr")));

        assertEquals("fr", storage.get(KEY, String.class).orElseThrow());
        assertEquals("fr", storage.get(KEY, String.class).orElseThrow());
        assertTrue(storage.exists(KEY));

        assertEquals(List.of("get lang"), storage.calls, "one backend read, then cache");
    }

    @Test
    void missesAreNotCached() {
        assertTrue(storage.get(KEY, String.class).isEmpty());
        assertTrue(storage.get(KEY, String.class).isEmpty());
        assertFalse(storage.exists(KEY));

        assertEquals(List.of("get lang", "get lang", "exists lang"), storage.calls);
    }

    @Test
    void cachedValueOfAnotherTypeFallsThroughToTheBackend() {
        storage.store.put("user:7", new HashMap<>(Map.of("count", 3.0)));
        StorageKey count = StorageKey.user("7", "count");

        assertEquals(3.0, storage.get(count, Double.class).orElseThrow());
        assertEquals(3L, storage.get(count, Long.class).orElseThrow(), "backend converts, cache is replaced");
        assertEquals(3L, storage.get(count, Long.class).orElseThrow());

        assertEquals(List.of("get count", "get count"), storage.calls);
    }

    @Test
    void writesGoToTheBackendThenTheCache() {
        assertTrue(storage.set(KEY, "fr"));
        assertEquals("fr", storage.store.get("user:7").get("lang"));
        assertEquals("fr", storage.get(KEY, String.class).orElseThrow());
        assertEquals(List.of("set lang"), storage.calls, "read served from cache");
    }

    @Test
    void rejectedWritesAreNotCached() {
        storage.failWrites = true;

        assertFalse(storage.set(KEY, "fr"));
        assertTrue(storage.get(KEY, String.class).isEmpty());
        assertFalse(storage.exists(KEY));
    }

    @Test
    void removeAndClearEvictTheCache() {
        storage.set(KEY, "fr");
        storage.set(StorageKey.user("7", "other"), "x");

        assertTrue(storage.remove(KEY));
        assertFalse(storage.remove(KEY));
        assertFalse(storage.exists(KEY));
        assertEquals(Set.of("other"), storage.getKeys("user:7"));

        assertTrue(storage.clear("user:7"));
        assertTrue(storage.getKeys("user:7").isEmpty());
        assertTrue(storage.getAll("user:7").isEmpty());
    }

    @Test
    void keysAndGetAllMergeBackendAndCache() {
        storage.store.put("user:7", new HashMap<>(Map.of("lang", "fr")));
        storage.set(StorageKey.user("7", "theme"), "dark");

        assertEquals(Set.of("lang", "theme"), storage.getKeys("user:7"));
        assertEquals(Map.of("lang", "fr", "theme", "dark"), storage.getAll("user:7"));
    }

    @Test
    void nullValuesCannotBeStored() {
        assertThrows(NullPointerException.class, () -> storage.set(KEY, null));
    }

    // --- events ---------------------------------------------------------------------------------

    static class Interceptor {
        Object getOverride;
        boolean cancelGet, cancelSet, cancelRemove;
        Object setReplacement;
        final List<String> seen = new ArrayList<>();

        @EventHandler public void onGet(StorageGetEvent e) {
            seen.add("get:" + e.getValue());
            if (cancelGet) { e.setCancelled(true); e.setValue(getOverride); }
            else if (getOverride != null && e.getValue() != null) { e.setValue(getOverride); }
        }
        @EventHandler public void onSet(StorageSetEvent e) {
            seen.add("set:" + e.getValue());
            if (cancelSet) e.setCancelled(true);
            if (setReplacement != null) e.setValue(setReplacement);
        }
        @EventHandler public void onRemove(StorageRemoveEvent e) {
            seen.add("remove");
            if (cancelRemove) e.setCancelled(true);
        }
    }

    private Interceptor intercept() {
        Interceptor interceptor = new Interceptor();
        events.registerListener(interceptor, new StubPlugin());
        return interceptor;
    }

    @Test
    void getFiresPreAndPostEvents() {
        Interceptor i = intercept();
        storage.store.put("user:7", new HashMap<>(Map.of("lang", "fr")));

        storage.get(KEY, String.class);
        assertEquals(List.of("get:null", "get:fr"), i.seen, "pre (no value) then post (with value)");

        i.seen.clear();
        storage.get(KEY, String.class);
        assertEquals(List.of("get:null", "get:fr"), i.seen, "same from cache");
    }

    @Test
    void cancelledGetReturnsTheListenersValueWithoutTouchingTheBackend() {
        Interceptor i = intercept();
        i.cancelGet = true;
        i.getOverride = "en";

        assertEquals("en", storage.get(KEY, String.class).orElseThrow());
        assertTrue(storage.calls.isEmpty());
    }

    @Test
    void listenerCanRewriteAValueOnRead() {
        Interceptor i = intercept();
        i.getOverride = "en";
        storage.store.put("user:7", new HashMap<>(Map.of("lang", "fr")));

        assertEquals("en", storage.get(KEY, String.class).orElseThrow(), "cold read");
        assertEquals("en", storage.get(KEY, String.class).orElseThrow(), "cached read");
    }

    @Test
    void cancelledSetIsNotStoredAndReplacementValueIsUsed() {
        Interceptor i = intercept();
        i.cancelSet = true;
        assertFalse(storage.set(KEY, "fr"));
        assertTrue(storage.calls.isEmpty());

        i.cancelSet = false;
        i.setReplacement = "de";
        assertTrue(storage.set(KEY, "fr"));
        assertEquals("de", storage.store.get("user:7").get("lang"));
        assertEquals("de", storage.get(KEY, String.class).orElseThrow());
    }

    @Test
    void cancelledRemoveKeepsTheValue() {
        Interceptor i = intercept();
        storage.set(KEY, "fr");
        i.cancelRemove = true;

        assertFalse(storage.remove(KEY));
        assertEquals("fr", storage.get(KEY, String.class).orElseThrow());
    }
}
