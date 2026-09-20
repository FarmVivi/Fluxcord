package fr.farmvivi.fluxcord.core.storage.file;

import fr.farmvivi.fluxcord.api.storage.StorageKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Persistence contract of the FILE backend: what is written, when, and what a fresh process reads back.
 * Debounce is 0 so every write is scheduled immediately; {@code close()} waits for it.
 */
class FileDataStorageTest {

    private static final StorageKey PREFIX = StorageKey.guild("1", "commands.prefix");
    private static final StorageKey STATE = StorageKey.guild("1", "music.state");

    private static File dataFile(File dir, String scope) {
        return new File(dir, scope.replace(':', '/') + "/data.json");
    }

    @Test
    void scopesMapToDirectoriesAndOneJsonFile(@TempDir File dir) {
        FileDataStorage storage = new FileDataStorage(dir, null, 0);
        storage.set(PREFIX, "!");
        storage.set(StorageKey.user("42", "lang"), "fr");
        storage.set(StorageKey.global("boot"), 3);
        assertTrue(storage.close());

        assertTrue(dataFile(dir, "guild:1").isFile());
        assertTrue(dataFile(dir, "user:42").isFile());
        assertTrue(dataFile(dir, "global").isFile());
    }

    @Test
    void setOnAColdScopeKeepsTheOtherKeysOfThatScope(@TempDir File dir) {
        FileDataStorage first = new FileDataStorage(dir, null, 0);
        first.set(PREFIX, "!");
        assertTrue(first.close());

        // New process: the first operation on the scope is a set of another key.
        FileDataStorage restarted = new FileDataStorage(dir, null, 0);
        restarted.set(STATE, "playing");
        assertEquals("!", restarted.get(PREFIX, String.class).orElse(null), "in memory");
        assertTrue(restarted.close());

        FileDataStorage third = new FileDataStorage(dir, null, 0);
        assertEquals("!", third.get(PREFIX, String.class).orElse(null), "on disk: the prefix must survive");
        assertEquals("playing", third.get(STATE, String.class).orElse(null));
    }

    @Test
    void removingTheLastKeyIsPersisted(@TempDir File dir) {
        FileDataStorage first = new FileDataStorage(dir, null, 0);
        first.set(PREFIX, "!");
        assertTrue(first.close());

        FileDataStorage second = new FileDataStorage(dir, null, 0);
        assertTrue(second.remove(PREFIX));
        assertFalse(second.exists(PREFIX));
        assertTrue(second.close());

        FileDataStorage third = new FileDataStorage(dir, null, 0);
        assertFalse(third.exists(PREFIX), "removed key must not come back after a restart");
        assertTrue(third.get(PREFIX, String.class).isEmpty());
    }

    @Test
    void removeOfAMissingKeyReturnsFalse(@TempDir File dir) {
        FileDataStorage storage = new FileDataStorage(dir, null, 0);
        assertFalse(storage.remove(PREFIX));
        assertFalse(storage.exists(PREFIX));
        assertTrue(storage.close());
    }

    @Test
    void keysAndGetAllMergeDiskAndMemory(@TempDir File dir) {
        FileDataStorage first = new FileDataStorage(dir, null, 0);
        first.set(PREFIX, "!");
        assertTrue(first.close());

        FileDataStorage second = new FileDataStorage(dir, null, 0);
        second.set(STATE, "x");
        assertEquals(Set.of("commands.prefix", "music.state"), second.getKeys("guild:1"));
        assertEquals(Map.of("commands.prefix", "!", "music.state", "x"), second.getAll("guild:1"));
        assertTrue(second.getKeys("guild:2").isEmpty());
        assertTrue(second.getAll("guild:2").isEmpty());
        assertTrue(second.close());
    }

    @Test
    void clearDeletesTheScopeOnDiskAndInMemory(@TempDir File dir) {
        FileDataStorage storage = new FileDataStorage(dir, null, 0);
        storage.set(PREFIX, "!");
        assertTrue(storage.save());
        assertTrue(dataFile(dir, "guild:1").isFile());

        assertTrue(storage.clear("guild:1"));

        assertFalse(dataFile(dir, "guild:1").exists());
        assertFalse(storage.exists(PREFIX));
        assertTrue(storage.getKeys("guild:1").isEmpty());
        assertTrue(storage.close());
        assertFalse(dataFile(dir, "guild:1").exists(), "close must not resurrect a cleared scope");
    }

    @Test
    void complexValuesRoundTripThroughJson(@TempDir File dir) {
        record Track(String title, long position, List<String> tags) { }
        StorageKey key = StorageKey.global("track");

        FileDataStorage first = new FileDataStorage(dir, null, 0);
        first.set(key, new Track("song", 17500L, List.of("a", "b")));
        assertEquals("song", first.get(key, Track.class).orElseThrow().title(), "same instance served from cache");
        assertTrue(first.close());

        FileDataStorage second = new FileDataStorage(dir, null, 0);
        Track restored = second.get(key, Track.class).orElseThrow();
        assertEquals(new Track("song", 17500L, List.of("a", "b")), restored);
        assertEquals(17500L, second.get(key, Map.class).orElseThrow().get("position"),
                "untyped cold reads see the shared JSON model (integral numbers are Long)");
    }

    @Test
    void valuesAreVisibleBeforeTheDebouncedWriteAndWrittenAfterwards(@TempDir File dir) throws Exception {
        FileDataStorage storage = new FileDataStorage(dir, null, 60_000);
        storage.set(PREFIX, "!");
        assertEquals("!", storage.get(PREFIX, String.class).orElse(null));
        assertFalse(dataFile(dir, "guild:1").exists(), "not flushed yet (60 s debounce)");

        assertTrue(storage.save(), "explicit save flushes immediately");
        String json = Files.readString(dataFile(dir, "guild:1").toPath(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"commands.prefix\": \"!\""), json);
        assertTrue(storage.close());
    }

    @Test
    void corruptJsonFileIsTreatedAsEmptyAndOverwrittenOnNextSave(@TempDir File dir) throws Exception {
        File file = dataFile(dir, "guild:1");
        assertTrue(file.getParentFile().mkdirs());
        Files.writeString(file.toPath(), "{ not json", StandardCharsets.UTF_8);

        FileDataStorage storage = new FileDataStorage(dir, null, 0);
        assertTrue(storage.get(PREFIX, String.class).isEmpty());
        storage.set(PREFIX, "!");
        assertTrue(storage.close());

        assertEquals("!", new FileDataStorage(dir, null, 0).get(PREFIX, String.class).orElse(null));
    }
}
