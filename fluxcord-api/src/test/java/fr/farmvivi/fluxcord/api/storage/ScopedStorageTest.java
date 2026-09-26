package fr.farmvivi.fluxcord.api.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The view every plugin sees of the storage, and the prefixing that keeps plugins out of each other's
 * keys.
 *
 * <p>Worth pinning precisely, because a prefix bug is silent in both directions: writing under the wrong
 * prefix loses the data, and reading under the wrong one just returns "absent". The backend here records
 * the exact {@link StorageKey} it was handed, so the assertions are about what really reaches the disk.
 */
class ScopedStorageTest {

    /** Records every key it is given, so the test can assert on the storage layout itself. */
    private static final class RecordingStorage implements DataStorage {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final java.util.List<StorageKey> keysSeen = new java.util.ArrayList<>();
        private boolean cleared;

        @Override
        public <T> Optional<T> get(StorageKey key, Class<T> type) {
            keysSeen.add(key);
            Object value = values.get(key.toString());
            return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
        }

        @Override
        public <T> boolean set(StorageKey key, T value) {
            keysSeen.add(key);
            values.put(key.toString(), value);
            return true;
        }

        @Override
        public boolean exists(StorageKey key) {
            keysSeen.add(key);
            return values.containsKey(key.toString());
        }

        @Override
        public boolean remove(StorageKey key) {
            keysSeen.add(key);
            return values.remove(key.toString()) != null;
        }

        @Override
        public Set<String> getKeys(String scope) {
            Set<String> keys = new java.util.LinkedHashSet<>();
            values.keySet().forEach(full -> {
                if (full.startsWith(scope + ":")) {
                    keys.add(full.substring(scope.length() + 1));
                }
            });
            return keys;
        }

        @Override
        public Map<String, Object> getAll(String scope) {
            Map<String, Object> all = new LinkedHashMap<>();
            getKeys(scope).forEach(key -> all.put(key, values.get(scope + ":" + key)));
            return all;
        }

        @Override
        public boolean clear(String scope) {
            cleared = true;
            values.keySet().removeIf(full -> full.startsWith(scope + ":"));
            return true;
        }

        @Override
        public boolean save() {
            return true;
        }

        @Override
        public boolean close() {
            return true;
        }

        private StorageKey lastKey() {
            return keysSeen.get(keysSeen.size() - 1);
        }
    }

    private RecordingStorage backend;
    private ScopedStorage guild;

    @BeforeEach
    void setUp() {
        backend = new RecordingStorage();
        guild = new ScopedStorage(backend, StorageKey.guildScope("g1"));
    }

    @Test
    void anUnnamespacedViewPassesKeysThrough() {
        guild.set("commands.prefix", "!");

        assertEquals(StorageKey.guild("g1", "commands.prefix"), backend.lastKey());
        assertEquals("guild:g1", guild.getScope());
        assertEquals("", guild.getPrefix());
        assertEquals(Optional.of("!"), guild.get("commands.prefix", String.class));
    }

    @Test
    void aNamespacedViewPrefixesTheKeyAndNotTheScope() {
        // The plugin namespace is a key prefix, never a separate scope: the data still belongs to the
        // guild, so the guild's directory or row set stays whole.
        ScopedStorage plugin = guild.namespaced("music-plugin");

        plugin.set("volume", 80);

        assertEquals("guild:g1", plugin.getScope(), "same scope");
        assertEquals("music-plugin.", plugin.getPrefix());
        assertEquals(StorageKey.guild("g1", "music-plugin.volume"), backend.lastKey());
    }

    @Test
    void namespacesNestAndKeepTheirOrder() {
        ScopedStorage nested = guild.namespaced("music-plugin").namespaced("playlists");

        nested.set("chill", "x");

        assertEquals("music-plugin.playlists.", nested.getPrefix());
        assertEquals(StorageKey.guild("g1", "music-plugin.playlists.chill"), backend.lastKey());
    }

    @Test
    void everyOperationGoesThroughTheSamePrefix() {
        ScopedStorage plugin = guild.namespaced("p");
        plugin.set("k", "v");

        assertTrue(plugin.exists("k"));
        assertEquals(Optional.of("v"), plugin.get("k", String.class));
        assertTrue(plugin.remove("k"));
        assertFalse(plugin.exists("k"));
        backend.keysSeen.forEach(key -> assertEquals("p.k", key.key()));
    }

    @Test
    void readingAValueOfAnotherTypeIsAMissRatherThanAFailure() {
        guild.set("volume", 80);

        assertEquals(Optional.empty(), guild.get("volume", String.class));
        assertEquals(Optional.of(80), guild.get("volume", Integer.class));
    }

    @Test
    void listingAViewShowsItsOwnKeysWithoutThePrefix() {
        // A plugin must see "volume", not "music-plugin.volume": the prefix is the view's business.
        ScopedStorage music = guild.namespaced("music-plugin");
        ScopedStorage other = guild.namespaced("other-plugin");
        music.set("volume", 80);
        music.set("loop", true);
        other.set("volume", 10);
        guild.set("commands.prefix", "!");

        assertEquals(Set.of("volume", "loop"), music.getKeys());
        assertEquals(Set.of("volume"), other.getKeys(), "one plugin never sees another's keys");
        assertEquals(Map.of("volume", 80, "loop", true), music.getAll());
    }

    @Test
    void listingAnUnnamespacedViewShowsEverythingInTheScope() {
        guild.namespaced("p").set("k", 1);
        guild.set("commands.prefix", "!");

        assertEquals(Set.of("p.k", "commands.prefix"), guild.getKeys(),
                "the core sees the whole guild, prefixes included");
    }

    @Test
    void clearingANamespacedViewOnlyRemovesItsOwnKeys() {
        // The dangerous case: clearing must not wipe the guild's core keys or another plugin's data.
        ScopedStorage music = guild.namespaced("music-plugin");
        music.set("volume", 80);
        guild.namespaced("other").set("volume", 10);
        guild.set("commands.prefix", "!");

        assertTrue(music.clear());

        assertTrue(music.getKeys().isEmpty());
        assertEquals(Set.of("volume"), guild.namespaced("other").getKeys());
        assertEquals(Optional.of("!"), guild.get("commands.prefix", String.class));
        assertFalse(backend.cleared, "a namespaced clear removes keys one by one, it never clears the scope");
    }

    @Test
    void clearingAnUnnamespacedViewClearsTheWholeScope() {
        guild.set("commands.prefix", "!");

        assertTrue(guild.clear());

        assertTrue(backend.cleared);
        assertTrue(guild.getKeys().isEmpty());
    }

    @Test
    void theStorageAndScopeAreRequired() {
        assertThrows(NullPointerException.class, () -> new ScopedStorage(null, "global"));
        assertThrows(NullPointerException.class, () -> new ScopedStorage(backend, null));
        assertThrows(NullPointerException.class, () -> guild.namespaced(null));
    }
}
