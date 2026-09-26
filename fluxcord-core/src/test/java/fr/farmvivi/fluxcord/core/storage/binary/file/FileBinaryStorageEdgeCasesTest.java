package fr.farmvivi.fluxcord.core.storage.binary.file;

import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.mock;

/**
 * What the file binary storage does when the filesystem does not cooperate, and the one-off migration of the
 * folder layout.
 *
 * <p>The happy paths are covered by {@code FileBinaryStorageTest}; these are the branches that only run when
 * something is missing, is the wrong kind of thing, or predates the current layout — which is precisely
 * where a silent {@code null} or a lost folder hides.
 */
class FileBinaryStorageEdgeCasesTest {

    @TempDir Path base;

    private FileBinaryStorage storage;

    @BeforeEach
    void setUp() {
        storage = new FileBinaryStorage("test", base.toFile(), mock(EventManager.class));
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void readingAFileThatIsNotThereIsAnEmptyAnswerRatherThanAFailure() {
        assertTrue(storage.getInputStream(BinaryStorageKey.global("absent.png")).isEmpty());
        assertFalse(storage.fileExists(BinaryStorageKey.global("absent.png")));
    }

    @Test
    void aDirectoryIsNotReadableAsAFile() {
        // Asking for "covers" when covers/ is a folder must not hand back a stream over a directory.
        assertTrue(storage.createDirectory(BinaryStorageKey.global("covers")));

        assertTrue(storage.getInputStream(BinaryStorageKey.global("covers")).isEmpty());
        assertTrue(storage.isDirectory(BinaryStorageKey.global("covers")));
        assertFalse(storage.isDirectory(BinaryStorageKey.global("covers/a.png")));
    }

    @Test
    void aDirectoryIsNotDeletableAsAFile() {
        assertTrue(storage.createDirectory(BinaryStorageKey.global("covers")));

        assertFalse(storage.deleteFile(BinaryStorageKey.global("covers")),
                "deleting a folder through the file API would take its contents with it");
        assertTrue(storage.isDirectory(BinaryStorageKey.global("covers")));
    }

    @Test
    void deletingSomethingThatIsNotThereReportsFalse() {
        assertFalse(storage.deleteFile(BinaryStorageKey.global("absent.png")));
    }

    @Test
    void anUncreatableParentDirectoryYieldsNoStreamInsteadOfThrowing() throws Exception {
        // A file where a directory has to go: mkdirs fails, and the caller must get null rather than an
        // exception from inside a write.
        Files.createDirectories(base.resolve("global"));
        Files.writeString(base.resolve("global").resolve("covers"), "not a directory");

        assertNull(storage.getOutputStream(BinaryStorageKey.global("covers/a.png"), true));
    }

    @Test
    void sizeAndModificationTimeOfAMissingFileAreMinusOne() {
        BinaryStorageKey absent = BinaryStorageKey.global("absent.png");

        assertEquals(-1, storage.getFileSize(absent));
        assertEquals(-1, storage.getLastModifiedTime(absent));
    }

    @Test
    void sizeOfADirectoryIsMinusOneToo() {
        assertTrue(storage.createDirectory(BinaryStorageKey.global("covers")));

        assertEquals(-1, storage.getFileSize(BinaryStorageKey.global("covers")),
                "a directory has no size worth reporting");
    }

    @Test
    void sizeAndTimeOfARealFileAreReported() throws Exception {
        try (OutputStream out = storage.getOutputStream(BinaryStorageKey.global("a.png"), true)) {
            out.write(new byte[]{1, 2, 3});
        }

        assertEquals(3, storage.getFileSize(BinaryStorageKey.global("a.png")));
        assertTrue(storage.getLastModifiedTime(BinaryStorageKey.global("a.png")) > 0);
    }

    @Test
    void listingSomethingThatIsNotADirectoryIsEmpty() {
        assertEquals(List.of(), storage.listFiles(BinaryStorageKey.global("absent")));
    }

    @Test
    void deletingTheLastFileTakesItsEmptyFoldersWithIt() throws Exception {
        try (OutputStream out = storage.getOutputStream(BinaryStorageKey.global("covers/deep/a.png"), true)) {
            out.write(new byte[]{1});
        }
        assertTrue(new File(base.toFile(), "global/covers/deep").isDirectory());

        assertTrue(storage.deleteFile(BinaryStorageKey.global("covers/deep/a.png")));

        assertFalse(new File(base.toFile(), "global/covers/deep").exists(), "empty folders are cleaned up");
        assertFalse(new File(base.toFile(), "global/covers").exists());
        assertTrue(base.toFile().isDirectory(), "but never the base directory itself");
    }

    @Test
    void theBaseDirectoryIsCreatedWhenItDoesNotExistYet() {
        File fresh = new File(base.toFile(), "created/on/demand");

        FileBinaryStorage created = new FileBinaryStorage("fresh", fresh, mock(EventManager.class));

        assertTrue(fresh.isDirectory());
        created.close();
    }

    @Test
    void aFolderFromTheOldLayoutIsMovedOnceSoItsFilesStayReachable() throws Exception {
        // The previous layout used the scope string as a folder name ("user:1"), which only a filesystem
        // allowing a colon can hold - so this can only be checked off Windows.
        assumeFalse(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"),
                "a colon cannot appear in a Windows path");
        Path legacy = base.resolve("user:1");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("a.png"), "kept");

        // The migration runs in the constructor.
        FileBinaryStorage migrated = new FileBinaryStorage("test", base.toFile(), mock(EventManager.class));

        assertFalse(Files.exists(legacy), "the old folder is gone");
        assertEquals("kept", Files.readString(base.resolve("user").resolve("1").resolve("a.png")));
        assertTrue(migrated.fileExists(BinaryStorageKey.user("1", "a.png")),
                "and the file is reachable under its key again");
        migrated.close();
    }

    @Test
    void anAlreadyMigratedFolderIsLeftWhereItIs() throws Exception {
        assumeFalse(System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win"),
                "a colon cannot appear in a Windows path");
        Files.createDirectories(base.resolve("user:1"));
        Files.createDirectories(base.resolve("user").resolve("1"));
        Files.writeString(base.resolve("user").resolve("1").resolve("a.png"), "current");

        FileBinaryStorage migrated = new FileBinaryStorage("test", base.toFile(), mock(EventManager.class));

        // The migration refuses to overwrite: the current folder wins and the stale one is reported.
        assertEquals("current", Files.readString(base.resolve("user").resolve("1").resolve("a.png")));
        migrated.close();
    }
}
