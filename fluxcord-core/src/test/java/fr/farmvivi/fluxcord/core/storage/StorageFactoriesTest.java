package fr.farmvivi.fluxcord.core.storage;

import fr.farmvivi.fluxcord.api.storage.DataStorageManager;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageManager;
import fr.farmvivi.fluxcord.core.config.CoreSettings;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import fr.farmvivi.fluxcord.core.storage.binary.BinaryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link StorageFactory} / {@link BinaryStorageFactory}: backend selection from the settings and the
 * {@code fallback} switch when the DB/S3 backend cannot be initialised at boot.
 */
class StorageFactoriesTest {

    @TempDir Path dir;
    private final SimpleEventManager events = new SimpleEventManager();
    private DataStorageManager data;
    private BinaryStorageManager binary;

    @AfterEach
    void tearDown() {
        if (data != null) data.close();
        if (binary != null) binary.close();
        events.shutdown();
    }

    private CoreSettings.DataStorage dataSettings(CoreSettings.DataBackend type, boolean fallback, String url) {
        return new CoreSettings.DataStorage(type, fallback, dir.resolve("data").toFile(), 0,
                url == null ? null : new CoreSettings.Database(url, "sa", "", "t_", 2, true));
    }

    private CoreSettings.BinaryStorage binarySettings(CoreSettings.BinaryBackend type, boolean fallback, String endpoint) {
        return new CoreSettings.BinaryStorage(type, fallback, dir.resolve("bin").toFile(),
                endpoint == null ? null : new CoreSettings.S3("bucket", "eu-west-3", "k", "s", endpoint, "p", true));
    }

    @Test
    void fileBackendWritesUnderTheConfiguredFolder() {
        data = StorageFactory.createStorageManager(dataSettings(CoreSettings.DataBackend.FILE, false, null), events);
        data.getGlobalStorage().set("k", "v");
        assertTrue(data.saveAll());
        assertTrue(Files.exists(dir.resolve("data").resolve("storage").resolve("global").resolve("data.json")));
    }

    @Test
    void databaseBackendIsUsedWhenTheUrlWorks() {
        data = StorageFactory.createStorageManager(
                dataSettings(CoreSettings.DataBackend.DB, false, "jdbc:h2:mem:factory;MODE=MySQL;DB_CLOSE_DELAY=-1"), events);
        data.getGlobalStorage().set("k", "v");
        assertEquals("v", data.getGlobalStorage().get("k", String.class).orElseThrow());
        assertFalse(Files.exists(dir.resolve("data")), "no file storage created");
    }

    @Test
    void databaseFailureFallsBackToFilesOnlyWhenAllowed() {
        String unreachable = "jdbc:h2:tcp://127.0.0.1:1/nope";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> StorageFactory.createStorageManager(dataSettings(CoreSettings.DataBackend.DB, false, unreachable), events));
        assertTrue(e.getMessage().contains("fallback is disabled"), e.getMessage());

        data = StorageFactory.createStorageManager(dataSettings(CoreSettings.DataBackend.DB, true, unreachable), events);
        data.getGlobalStorage().set("k", "v");
        assertTrue(data.saveAll());
        assertTrue(Files.exists(dir.resolve("data").resolve("storage").resolve("global").resolve("data.json")), "file fallback");
    }

    @Test
    void fileBinaryBackendWritesUnderTheConfiguredFolder() {
        binary = BinaryStorageFactory.createBinaryStorageManager(binarySettings(CoreSettings.BinaryBackend.FILE, false, null), events);
        assertTrue(binary.getGlobalStorage().saveFile("a.txt", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), false));
        assertTrue(Files.exists(dir.resolve("bin").resolve("global").resolve("a.txt")));
    }

    @Test
    void s3FailureFallsBackToFilesOnlyWhenAllowed() {
        String badEndpoint = "::not a uri::";
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BinaryStorageFactory.createBinaryStorageManager(binarySettings(CoreSettings.BinaryBackend.S3, false, badEndpoint), events));
        assertTrue(e.getMessage().contains("fallback is disabled"), e.getMessage());

        binary = BinaryStorageFactory.createBinaryStorageManager(binarySettings(CoreSettings.BinaryBackend.S3, true, badEndpoint), events);
        assertTrue(binary.getGlobalStorage().saveFile("a.txt", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), false));
        assertTrue(Files.exists(dir.resolve("bin").resolve("global").resolve("a.txt")), "file fallback");
    }
}
