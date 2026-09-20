package fr.farmvivi.fluxcord.core.permissions;

import fr.farmvivi.fluxcord.api.event.EventHandler;
import fr.farmvivi.fluxcord.api.permissions.Permission;
import fr.farmvivi.fluxcord.api.permissions.PermissionDefault;
import fr.farmvivi.fluxcord.api.permissions.events.PermissionChangeEvent;
import fr.farmvivi.fluxcord.api.permissions.events.PermissionCheckEvent;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.plugin.PluginLifecycle;
import fr.farmvivi.fluxcord.api.storage.DataStorage;
import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.core.storage.SimpleDataStorageManager;
import fr.farmvivi.fluxcord.api.storage.StorageKey;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Permission resolution: explicit user-guild override → user override → registered default
 * (TRUE/FALSE/OP/NOT_OP) → false, with the per-user caches and the check/change events.
 */
class SimplePermissionManagerTest {

    /** Minimal in-memory DataStorage that also counts reads, to observe the cache. */
    static class MemoryStorage implements DataStorage {
        final Map<String, Map<String, Object>> data = new HashMap<>();
        int reads;

        private Map<String, Object> scope(String scope) { return data.computeIfAbsent(scope, s -> new HashMap<>()); }

        @Override public <T> Optional<T> get(StorageKey key, Class<T> type) {
            reads++;
            Object v = scope(key.getScope()).get(key.getKey());
            return Optional.ofNullable(type.isInstance(v) ? type.cast(v) : null);
        }
        @Override public <T> boolean set(StorageKey key, T value) { scope(key.getScope()).put(key.getKey(), value); return true; }
        @Override public boolean exists(StorageKey key) { return scope(key.getScope()).containsKey(key.getKey()); }
        @Override public boolean remove(StorageKey key) { return scope(key.getScope()).remove(key.getKey()) != null; }
        @Override public Set<String> getKeys(String scope) { return new HashSet<>(scope(scope).keySet()); }
        @Override public Map<String, Object> getAll(String scope) { return new HashMap<>(scope(scope)); }
        @Override public boolean clear(String scope) { data.remove(scope); return true; }
        @Override public boolean save() { return true; }
        @Override public boolean close() { return true; }
    }

    static class StubPlugin implements Plugin {
        private final String id;
        private PluginLifecycle lifecycle = PluginLifecycle.LOADED;
        StubPlugin(String id) { this.id = id; }
        @Override public String getId() { return id; }
        @Override public String getName() { return id; }
        @Override public String getVersion() { return "1"; }
        @Override public void onLoad(PluginContext context) { }
        @Override public void onEnable() { }
        @Override public void onDisable() { }
        @Override public PluginLifecycle getLifecycle() { return lifecycle; }
        @Override public void setLifecycle(PluginLifecycle lifecycle) { this.lifecycle = lifecycle; }
    }

    record Perm(String getName, String getDescription, PermissionDefault getDefault) implements Permission { }

    private final SimpleEventManager events = new SimpleEventManager();
    private final MemoryStorage storage = new MemoryStorage();
    private final SimplePermissionManager manager =
            new SimplePermissionManager(events, new SimpleDataStorageManager(storage));
    private final StubPlugin plugin = new StubPlugin("music");

    @AfterEach
    void shutdown() {
        events.shutdown();
    }

    private void register(String name, PermissionDefault def) {
        manager.registerPermission(new Perm(name, "", def), plugin);
    }

    // --- registry -------------------------------------------------------------------------------

    @Test
    void registersOncePerNameAndUnregistersPerPlugin() {
        register("music.play", PermissionDefault.TRUE);
        manager.registerPermission(new Perm("music.play", "dup", PermissionDefault.FALSE), new StubPlugin("other"));

        assertEquals(PermissionDefault.TRUE, manager.getPermission("music.play").getDefault(), "first wins");
        assertEquals(1, manager.getRegisteredPermissions().size());
        assertEquals(1, manager.getPermissions(plugin).size());
        assertTrue(manager.getPermissions(new StubPlugin("other")).isEmpty());

        assertEquals(1, manager.unregisterPermissions(plugin));
        assertEquals(0, manager.unregisterPermissions(plugin));
        assertNull(manager.getPermission("music.play"));
        assertTrue(manager.getRegisteredPermissions().isEmpty());
    }

    @Test
    void nullArgumentsAreRejectedOrIgnored() {
        assertThrows(IllegalArgumentException.class, () -> manager.registerPermission(null, plugin));
        assertThrows(IllegalArgumentException.class, () -> manager.registerPermission(new Perm("x", "", PermissionDefault.TRUE), null));
        assertFalse(manager.hasPermission(null, "x"));
        assertFalse(manager.hasPermission("1", null));
        assertFalse(manager.hasPermission("1", null, "x"));
        assertTrue(manager.getUserPermissions(null).isEmpty());
        assertTrue(manager.getPermissions(null).isEmpty());
        assertEquals(0, manager.unregisterPermissions(null));
    }

    // --- defaults -------------------------------------------------------------------------------

    @Test
    void defaultsApplyWhenNothingIsStored() {
        register("p.true", PermissionDefault.TRUE);
        register("p.false", PermissionDefault.FALSE);
        register("p.op", PermissionDefault.OP);
        register("p.notop", PermissionDefault.NOT_OP);

        assertTrue(manager.hasPermission("1", "p.true"));
        assertFalse(manager.hasPermission("1", "p.false"));
        assertFalse(manager.hasPermission("1", "p.op"), "nobody is an operator by default (plan item B1)");
        assertTrue(manager.hasPermission("1", "p.notop"));
        assertFalse(manager.hasPermission("1", "p.unregistered"));

        assertTrue(manager.hasPermission("1", "g", "p.true"), "guild check falls back to the global default");
        assertFalse(manager.hasPermission("1", "g", "p.op"));
    }

    @Test
    void configuredOperatorsUnlockOpDefaults() {
        SimplePermissionManager withOps = new SimplePermissionManager(events, new SimpleDataStorageManager(storage), List.of("1"));
        withOps.registerPermission(new Perm("p.op", "", PermissionDefault.OP), plugin);
        withOps.registerPermission(new Perm("p.notop", "", PermissionDefault.NOT_OP), plugin);

        assertTrue(withOps.isOperator("1"));
        assertTrue(withOps.hasPermission("1", "p.op"));
        assertTrue(withOps.hasPermission("1", "g", "p.op"));
        assertFalse(withOps.hasPermission("1", "p.notop"));
        assertFalse(withOps.hasPermission("2", "p.op"));
        assertTrue(withOps.isOperator("1", "any-guild"));
    }

    @Test
    void unsetRemovesOneOverrideAndRestoresTheDefault() {
        register("p", PermissionDefault.TRUE);
        manager.setPermission("1", "p", false);
        manager.setPermission("1", "g", "p", false);
        manager.setPermission("1", "q", false);

        assertTrue(manager.unsetPermission("1", "g", "p"));
        assertFalse(manager.unsetPermission("1", "g", "p"), "nothing left to remove");
        assertFalse(manager.hasPermission("1", "g", "p"), "user-level override still applies");

        assertTrue(manager.unsetPermission("1", "p"));
        assertTrue(manager.hasPermission("1", "p"), "back to the default");
        assertTrue(manager.hasPermission("1", "g", "p"));
        assertEquals(Map.of("q", false), manager.getUserPermissions("1"), "other overrides untouched");
        assertFalse(manager.unsetPermission(null, "p"));
    }

    @Test
    void guildOperatorResolverGrantsOpDefaultsInThatGuildOnly() {
        register("p.op", PermissionDefault.OP);
        manager.setGuildOperatorResolver((userId, guildId) -> userId.equals("1") && guildId.equals("g"));

        assertTrue(manager.isOperator("1", "g"));
        assertTrue(manager.hasPermission("1", "g", "p.op"));
        assertFalse(manager.hasPermission("1", "other", "p.op"));
        assertFalse(manager.hasPermission("1", "p.op"), "not a global operator");
        assertFalse(manager.hasPermission("2", "g", "p.op"));

        manager.setGuildOperatorResolver((userId, guildId) -> { throw new IllegalStateException("jda down"); });
        assertFalse(manager.hasPermission("1", "g", "p.op"), "resolver failure denies instead of throwing");
    }

    @Test
    void explicitOverrideBeatsOperatorStatus() {
        SimplePermissionManager withOps = new SimplePermissionManager(events, new SimpleDataStorageManager(storage), List.of("1"));
        withOps.registerPermission(new Perm("p.op", "", PermissionDefault.OP), plugin);
        withOps.setPermission("1", "p.op", false);
        assertFalse(withOps.hasPermission("1", "p.op"));
    }

    // --- overrides ------------------------------------------------------------------------------

    @Test
    void userOverrideBeatsTheDefaultAndGuildOverrideBeatsUser() {
        register("p", PermissionDefault.FALSE);

        manager.setPermission("1", "p", true);
        assertTrue(manager.hasPermission("1", "p"));
        assertTrue(manager.hasPermission("1", "g", "p"), "guild check inherits the user override");
        assertEquals(true, storage.get(StorageKey.user("1", "permission.p"), Boolean.class).orElseThrow());

        manager.setPermission("1", "g", "p", false);
        assertFalse(manager.hasPermission("1", "g", "p"));
        assertTrue(manager.hasPermission("1", "p"), "global unchanged");
        assertTrue(manager.hasPermission("1", "other-guild", "p"));
        assertEquals(Map.of("p", true), manager.getUserPermissions("1"));
        assertEquals(Map.of("p", false), manager.getUserGuildPermissions("1", "g"));
    }

    @Test
    void overridesWorkForUnregisteredPermissions() {
        manager.setPermission("1", "custom", true);
        assertTrue(manager.hasPermission("1", "custom"));
    }

    @Test
    void clearPermissionsRemovesOverridesAndRestoresDefaults() {
        register("p", PermissionDefault.TRUE);
        manager.setPermission("1", "p", false);
        manager.setPermission("1", "g", "p", false);
        storage.set(StorageKey.user("1", "lang"), "fr"); // unrelated key must survive

        manager.clearPermissions("1", "g");
        assertFalse(manager.hasPermission("1", "g", "p"), "user override still applies");
        assertTrue(manager.getUserGuildPermissions("1", "g").isEmpty());

        manager.clearPermissions("1");
        assertTrue(manager.hasPermission("1", "p"), "back to the default");
        assertTrue(manager.hasPermission("1", "g", "p"));
        assertTrue(manager.getUserPermissions("1").isEmpty());
        assertEquals("fr", storage.get(StorageKey.user("1", "lang"), String.class).orElseThrow());
    }

    // --- cache ----------------------------------------------------------------------------------

    @Test
    void resultsAreCachedPerUserUntilCleared() {
        register("p", PermissionDefault.TRUE);

        manager.hasPermission("1", "p");
        int afterFirst = storage.reads;
        manager.hasPermission("1", "p");
        manager.hasPermission("1", "p");
        assertEquals(afterFirst, storage.reads, "served from cache");

        storage.set(StorageKey.user("1", "permission.p"), false); // external change: invisible until cleared
        assertTrue(manager.hasPermission("1", "p"));
        manager.clearCaches();
        assertFalse(manager.hasPermission("1", "p"));
    }

    @Test
    void reRegisteringWithAnotherDefaultIsVisibleImmediately() {
        register("p", PermissionDefault.TRUE);
        assertTrue(manager.hasPermission("1", "p"));

        manager.unregisterPermissions(plugin);
        assertFalse(manager.hasPermission("1", "p"), "unregistered: no default any more");

        manager.registerPermission(new Perm("p", "", PermissionDefault.FALSE), plugin);
        assertFalse(manager.hasPermission("1", "p"));
    }

    // --- events ---------------------------------------------------------------------------------

    static class Listener {
        final List<PermissionCheckEvent> checks = new ArrayList<>();
        final List<PermissionChangeEvent> changes = new ArrayList<>();
        Boolean forced;

        @EventHandler public void on(PermissionCheckEvent e) {
            checks.add(e);
            if (forced != null) { e.setResult(forced); e.setCancelled(true); }
        }
        @EventHandler public void on(PermissionChangeEvent e) { changes.add(e); }
    }

    @Test
    void checkEventCanForceTheResultWithoutStorage() {
        Listener listener = new Listener();
        events.registerListener(listener, plugin);
        listener.forced = true;

        assertTrue(manager.hasPermission("1", "g", "anything"));
        assertEquals(1, listener.checks.size());
        assertEquals("g", listener.checks.get(0).getGuildId());
        assertEquals(0, storage.reads);
    }

    @Test
    void changeEventCarriesOldAndNewValue() {
        Listener listener = new Listener();
        events.registerListener(listener, plugin);
        register("p", PermissionDefault.TRUE);

        manager.setPermission("1", "p", false);
        manager.setPermission("1", "g", "p", true);

        assertEquals(2, listener.changes.size());
        assertTrue(listener.changes.get(0).getOldValue());
        assertFalse(listener.changes.get(0).getNewValue());
        assertNull(listener.changes.get(0).getGuildId());
        assertFalse(listener.changes.get(1).getOldValue(), "old value seen through the user override");
        assertTrue(listener.changes.get(1).getNewValue());
        assertEquals("g", listener.changes.get(1).getGuildId());
    }
}
