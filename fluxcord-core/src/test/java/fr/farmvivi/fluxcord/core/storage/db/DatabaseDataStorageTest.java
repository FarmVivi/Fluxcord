package fr.farmvivi.fluxcord.core.storage.db;

import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.storage.StorageKey;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DatabaseDataStorage} against an in-memory H2 in MySQL compatibility mode (plan item S4). H2 emulates
 * MySQL's {@code ON DUPLICATE KEY UPDATE} but not PostgreSQL's {@code ON CONFLICT ... DO UPDATE}, so the
 * PostgreSQL dialect is only pinned as text by {@link SqlDialectTest}. A second instance on the same database
 * gives cold (cache-less) reads.
 */
class DatabaseDataStorageTest {

    record Track(String title, long duration, Instant addedAt) { }

    private static final EventManager EVENTS = mock(EventManager.class);

    static {
        when(EVENTS.hasListeners(any())).thenReturn(false);
    }

    private final String url = "jdbc:h2:mem:t" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";

    private DatabaseDataStorage open(String prefix) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url);
        return new DatabaseDataStorage(ds, SqlDialect.MYSQL, prefix, EVENTS);
    }

    @Test
    void crudRoundTripThroughUpsert() {
        DatabaseDataStorage storage = open("");
        StorageKey key = StorageKey.guild("g1", "commands.prefix");

        assertFalse(storage.exists(key));
        assertTrue(storage.set(key, "!"));
        assertTrue(storage.set(key, "?"), "second set is an update (upsert), not a duplicate-key failure");
        assertEquals("?", open("").get(key, String.class).orElseThrow(), "cold read");
        assertTrue(storage.exists(key));

        assertTrue(storage.remove(key));
        assertFalse(storage.remove(key));
        assertTrue(open("").get(key, String.class).isEmpty());
        assertTrue(storage.close());
    }

    @Test
    void scopesAreIsolatedAndClearOnlyDropsOneScope() {
        DatabaseDataStorage storage = open("");
        storage.set(StorageKey.guild("g1", "a"), 1);
        storage.set(StorageKey.guild("g1", "b"), 2);
        storage.set(StorageKey.guild("g2", "a"), 3);
        storage.set(StorageKey.userGuild("u1", "g1", "a"), 4);

        DatabaseDataStorage cold = open("");
        assertEquals(Set.of("a", "b"), cold.getKeys("guild:g1"));
        assertEquals(Map.of("a", 1L, "b", 2L), cold.getAll("guild:g1"), "untyped cold reads give Long for integers");

        assertTrue(storage.clear("guild:g1"));
        assertTrue(open("").getKeys("guild:g1").isEmpty());
        assertEquals(3, open("").get(StorageKey.guild("g2", "a"), Integer.class).orElseThrow());
        assertEquals(4, open("").get(StorageKey.userGuild("u1", "g1", "a"), Integer.class).orElseThrow());
    }

    @Test
    void complexValuesSurviveAsJson() throws Exception {
        DatabaseDataStorage storage = open("");
        Track track = new Track("California Dreamin'", 162_000L, Instant.parse("2026-09-20T15:30:00Z"));
        storage.set(StorageKey.global("track"), track);
        storage.set(StorageKey.global("list"), List.of("a", "b"));
        storage.set(StorageKey.global("map"), Map.of("volume", 100, "loop", true));

        DatabaseDataStorage cold = open("");
        assertEquals(track, cold.get(StorageKey.global("track"), Track.class).orElseThrow());
        assertEquals(List.of("a", "b"), cold.get(StorageKey.global("list"), List.class).orElseThrow());
        assertEquals(Map.of("volume", 100L, "loop", true), cold.get(StorageKey.global("map"), Map.class).orElseThrow());
        assertEquals("California Dreamin'", open("").get(StorageKey.global("track"), Map.class).orElseThrow().get("title"));

        // what is physically stored: one JSON document per key, Instant as ISO-8601
        assertEquals("{\"title\":\"California Dreamin'\",\"duration\":162000,\"addedAt\":\"2026-09-20T15:30:00Z\"}",
                rawValue(storage, "global", "track", "storage_data"));
    }

    @Test
    void tablePrefixNamesTheTable() throws Exception {
        DatabaseDataStorage storage = open("bot1_");
        storage.set(StorageKey.global("k"), "v");
        assertEquals("\"v\"", rawValue(storage, "global", "k", "bot1_storage_data"));
        assertTrue(open("").get(StorageKey.global("k"), String.class).isEmpty(), "another prefix is another table");
    }

    @Test
    void invalidTablePrefixIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> open("bot1; DROP TABLE x; --"));
    }

    private static String rawValue(DatabaseDataStorage storage, String scope, String key, String table) throws Exception {
        try (Connection conn = storage.dataSource().getConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT value_data FROM " + table
                     + " WHERE scope = '" + scope + "' AND key_name = '" + key + "'")) {
            assertTrue(rs.next(), "row exists");
            return rs.getString(1);
        }
    }
}
