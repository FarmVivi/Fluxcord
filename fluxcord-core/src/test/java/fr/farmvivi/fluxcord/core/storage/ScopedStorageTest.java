package fr.farmvivi.fluxcord.core.storage;

import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.storage.DataStorage;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.ScopedStorage;
import fr.farmvivi.fluxcord.api.storage.StorageKey;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorage;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageKey;
import fr.farmvivi.fluxcord.api.storage.binary.PluginBinaryStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.binary.ScopedBinaryStorage;
import fr.farmvivi.fluxcord.core.storage.binary.SimpleBinaryStorageManager;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Characterization of the scoped views (plan items S1/S2): the storage layout on disk/DB must not change —
 * scopes are {@code global}, {@code user:<id>}, {@code guild:<id>}, {@code user:<id>:guild:<id>}; plugin keys are
 * {@code <pluginId>.<key>}; plugin binary paths are {@code <pluginId>/<path>}.
 */
class ScopedStorageTest {

    static class MemoryStorage implements DataStorage {
        final Map<String, Map<String, Object>> data = new HashMap<>();

        private Map<String, Object> scope(String scope) { return data.computeIfAbsent(scope, s -> new HashMap<>()); }

        @Override public <T> Optional<T> get(StorageKey key, Class<T> type) {
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

    private final MemoryStorage backend = new MemoryStorage();
    private final SimpleDataStorageManager manager = new SimpleDataStorageManager(backend);
    private final Plugin plugin = mock(Plugin.class);

    @Test
    void scopesKeepTheirHistoricalFormat() {
        manager.getGlobalStorage().set("a", 1);
        manager.getUserStorage("u1").set("a", 2);
        manager.getGuildStorage("g1").set("a", 3);
        manager.getUserGuildStorage("u1", "g1").set("a", 4);

        assertEquals(Set.of("global", "user:u1", "guild:g1", "user:u1:guild:g1"), backend.data.keySet());
        assertEquals(4, backend.get(StorageKey.userGuild("u1", "g1", "a"), Integer.class).orElseThrow());
    }

    @Test
    void unnamespacedViewAddressesTheWholeScope() {
        ScopedStorage guild = manager.getGuildStorage("g1");
        assertEquals("guild:g1", guild.getScope());
        assertEquals("", guild.getPrefix());

        assertTrue(guild.set("commands.prefix", "!"));
        assertTrue(guild.exists("commands.prefix"));
        assertEquals("!", guild.get("commands.prefix", String.class).orElseThrow());
        assertTrue(guild.get("commands.prefix", Integer.class).isEmpty(), "type mismatch is empty");
        assertEquals(Set.of("commands.prefix"), guild.getKeys());
        assertEquals(Map.of("commands.prefix", "!"), guild.getAll());
        assertTrue(guild.remove("commands.prefix"));
        assertFalse(guild.remove("commands.prefix"));

        guild.set("x", 1);
        assertTrue(guild.clear());
        assertFalse(backend.data.containsKey("guild:g1"), "clear() of a plain view drops the scope");
    }

    @Test
    void pluginViewPrefixesKeysWithThePluginIdAndHidesOtherKeys() {
        when(plugin.getId()).thenReturn("music-plugin");
        PluginDataStorageAdapter adapter = new PluginDataStorageAdapter(plugin, manager);
        ScopedStorage guild = adapter.getGuildStorage("g1");
        assertEquals("music-plugin.", guild.getPrefix());

        guild.set("volume", 50);
        manager.getGuildStorage("g1").set("commands.prefix", "!");          // core key, same scope
        manager.getGuildStorage("g1").set("other-plugin.volume", 10);      // another plugin

        assertEquals(50, backend.get(StorageKey.guild("g1", "music-plugin.volume"), Integer.class).orElseThrow());
        assertEquals(50, guild.get("volume", Integer.class).orElseThrow());
        assertTrue(guild.exists("volume"));
        assertFalse(guild.exists("commands.prefix"), "un-namespaced keys are invisible");
        assertEquals(Set.of("volume"), guild.getKeys());
        assertEquals(Map.of("volume", 50), guild.getAll());

        assertTrue(guild.clear());
        assertEquals(Set.of("commands.prefix", "other-plugin.volume"), backend.getKeys("guild:g1"),
                "clear() of a plugin view only removes the plugin's keys");
    }

    @Test
    void namespacesCompose() {
        ScopedStorage nested = manager.getGlobalStorage().namespaced("a").namespaced("b");
        nested.set("k", "v");
        assertEquals("v", backend.get(StorageKey.global("a.b.k"), String.class).orElseThrow());
        assertEquals(Set.of("k"), nested.getKeys());
        assertEquals(Set.of("b.k"), manager.getGlobalStorage().namespaced("a").getKeys());
    }

    @Test
    void pluginAdapterCoversEveryScopeAndSavesTheWholeStorage() {
        when(plugin.getId()).thenReturn("p");
        DataStorage spied = spy(backend);
        PluginDataStorageAdapter adapter = new PluginDataStorageAdapter(plugin, new SimpleDataStorageManager(spied));

        adapter.getGlobalStorage().set("k", 1);
        adapter.getUserStorage("u").set("k", 1);
        adapter.getGuildStorage("g").set("k", 1);
        adapter.getUserGuildStorage("u", "g").set("k", 1);
        assertTrue(adapter.saveAll());

        verify(spied).set(eq(StorageKey.global("p.k")), any());
        verify(spied).set(eq(StorageKey.user("u", "p.k")), any());
        verify(spied).set(eq(StorageKey.guild("g", "p.k")), any());
        verify(spied).set(eq(StorageKey.userGuild("u", "g", "p.k")), any());
        verify(spied).save();
    }

    @Test
    void binaryViewsPlacePluginFilesUnderThePluginIdAndNormalisePaths() {
        when(plugin.getId()).thenReturn("music-plugin");
        BinaryStorage binary = mock(BinaryStorage.class);
        SimpleBinaryStorageManager binaryManager = new SimpleBinaryStorageManager(binary);
        PluginBinaryStorageAdapter adapter = new PluginBinaryStorageAdapter(plugin, binaryManager);

        ScopedBinaryStorage user = adapter.getUserStorage("u1");
        assertEquals("user:u1", user.getScope());
        assertEquals("music-plugin/", user.getPrefix());
        user.fileExists("avatars\\a.png");
        verify(binary).fileExists(new BinaryStorageKey("user:u1", "music-plugin/avatars/a.png"));

        binaryManager.getGlobalStorage().fileExists("/shared/x");
        verify(binary).fileExists(new BinaryStorageKey("global", "shared/x"));

        when(binary.listFiles(new BinaryStorageKey("guild:g1", "music-plugin/covers")))
                .thenReturn(List.of("music-plugin/covers/a.jpg", "unrelated"));
        assertEquals(List.of("covers/a.jpg", "unrelated"), adapter.getGuildStorage("g1").listFiles("covers"));

        binaryManager.getUserGuildStorage("u1", "g1").deleteFile("f");
        verify(binary).deleteFile(new BinaryStorageKey("user:u1:guild:g1", "f"));
        when(binary.close()).thenReturn(true);
        assertTrue(binaryManager.close());
    }
}
