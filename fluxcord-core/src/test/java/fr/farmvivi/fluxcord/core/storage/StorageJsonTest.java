package fr.farmvivi.fluxcord.core.storage;

import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.storage.StorageKey;
import fr.farmvivi.fluxcord.core.storage.file.FileDataStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The shared storage JSON model (plan item S3): the value types the docs promise round-trip, and the FILE backend
 * uses the same model as the DB backend (see {@code DatabaseDataStorageTest} for the DB side).
 */
class StorageJsonTest {

    enum Mode { OFF, TRACK, QUEUE }

    record Settings(Mode mode, int volume, Instant since, LocalDate day, Duration timeout, List<String> tags) { }

    @Test
    void javaTimeAndEnumsAreStoredAsIsoStrings() {
        Settings settings = new Settings(Mode.QUEUE, 100, Instant.parse("2026-09-20T15:30:00Z"),
                LocalDate.of(2026, 9, 20), Duration.ofMinutes(5), List.of("a", "b"));

        String json = StorageJson.compact().toJson(settings);

        assertEquals("{\"mode\":\"QUEUE\",\"volume\":100,\"since\":\"2026-09-20T15:30:00Z\",\"day\":\"2026-09-20\","
                + "\"timeout\":\"PT5M\",\"tags\":[\"a\",\"b\"]}", json);
        assertEquals(settings, StorageJson.compact().fromJson(json, Settings.class));
        assertEquals(settings, StorageJson.pretty().fromJson(StorageJson.pretty().toJson(settings), Settings.class));
    }

    @Test
    void untypedReadsGiveLongsNotDoublesAndHtmlIsNotEscaped() {
        Map<?, ?> map = StorageJson.compact().fromJson("{\"n\":1,\"d\":1.5,\"s\":\"a=b<c>\"}", Map.class);
        assertEquals(1L, map.get("n"));
        assertEquals(1.5, map.get("d"));
        assertEquals("a=b<c>", StorageJson.compact().toJson("a=b<c>").replace("\"", ""));
    }

    @Test
    void convertRetypesLoadedMapsIntoRecords() {
        Map<String, Object> loaded = Map.of("mode", "TRACK", "volume", 42L, "tags", List.of("x"));
        Settings settings = StorageJson.convert(loaded, Settings.class);
        assertEquals(Mode.TRACK, settings.mode());
        assertEquals(42, settings.volume());
        assertNull(settings.since());
        assertSame(loaded, StorageJson.convert(loaded, Map.class), "already the right type: no copy");
    }

    @Test
    void fileBackendRoundTripsTheSameModelAcrossRestarts(@TempDir Path dir) throws Exception {
        EventManager events = mock(EventManager.class);
        when(events.hasListeners(any())).thenReturn(false);
        Settings settings = new Settings(Mode.TRACK, 80, Instant.parse("2026-09-20T15:30:00Z"),
                LocalDate.of(2026, 9, 20), Duration.ofSeconds(30), List.of());
        StorageKey key = StorageKey.guild("g1", "music.settings");

        FileDataStorage first = new FileDataStorage(dir.toFile(), events, 0);
        first.set(key, settings);
        first.set(StorageKey.guild("g1", "count"), 7);
        first.close();

        String onDisk = Files.readString(dir.resolve("guild").resolve("g1").resolve("data.json"));
        assertTrue(onDisk.contains("\"since\": \"2026-09-20T15:30:00Z\""), onDisk);
        assertTrue(onDisk.contains("\"mode\": \"TRACK\""), onDisk);

        FileDataStorage second = new FileDataStorage(dir.toFile(), events, 0);
        assertEquals(7L, second.getAll("guild:g1").get("count"), "untyped cold reads: Long, like the DB backend");
        assertEquals(settings, second.get(key, Settings.class).orElseThrow());
        assertEquals(7, second.get(StorageKey.guild("g1", "count"), Integer.class).orElseThrow());
        assertEquals(7, second.getAll("guild:g1").get("count"), "typed reads are cached as-is: untyped reads then see the Integer");
        second.close();
    }
}
