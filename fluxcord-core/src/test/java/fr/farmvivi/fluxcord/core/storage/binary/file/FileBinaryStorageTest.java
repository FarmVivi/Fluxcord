package fr.farmvivi.fluxcord.core.storage.binary.file;

import fr.farmvivi.fluxcord.api.event.EventHandler;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.plugin.PluginLifecycle;
import fr.farmvivi.fluxcord.api.storage.binary.BinaryStorageKey;
import fr.farmvivi.fluxcord.api.storage.binary.events.FileDeleteEvent;
import fr.farmvivi.fluxcord.api.storage.binary.events.FileDownloadEvent;
import fr.farmvivi.fluxcord.api.storage.binary.events.FileUploadEvent;
import fr.farmvivi.fluxcord.core.event.SimpleEventManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FileBinaryStorage} on a real temporary directory, and through it the {@code AbstractBinaryStorage}
 * contract (events, download, delete, content types) that every backend shares.
 */
class FileBinaryStorageTest {

    @TempDir Path dir;
    private final SimpleEventManager events = new SimpleEventManager();

    @AfterEach
    void tearDown() {
        events.shutdown();
    }

    private FileBinaryStorage storage() {
        return new FileBinaryStorage("file", dir.resolve("binary").toFile(), events);
    }

    @Test
    void layoutIsScopeDirectoryThenPath() throws Exception {
        FileBinaryStorage storage = storage();
        BinaryStorageKey key = BinaryStorageKey.user("u1", "avatars/a.png");

        assertTrue(storage.saveFile(key, new ByteArrayInputStream(new byte[]{1, 2}), false));

        Path file = dir.resolve("binary").resolve("user").resolve("u1").resolve("avatars").resolve("a.png");
        assertTrue(Files.isRegularFile(file), "scope 'user:u1' becomes user/u1 (':' is not a valid file name character everywhere)");
        assertTrue(storage.fileExists(key));
        assertEquals(2, storage.getFileSize(key));
        assertTrue(storage.getLastModifiedTime(key) > 0);
        assertEquals("image/png", storage.getContentType(key));
        try (InputStream in = storage.getInputStream(key).orElseThrow()) {
            assertArrayEquals(new byte[]{1, 2}, in.readAllBytes());
        }
    }

    @Test
    void overwriteFlagIsHonoured() throws Exception {
        FileBinaryStorage storage = storage();
        BinaryStorageKey key = BinaryStorageKey.global("a.txt");
        assertTrue(storage.saveFile(key, new ByteArrayInputStream("one".getBytes(StandardCharsets.UTF_8)), false));
        assertFalse(storage.saveFile(key, new ByteArrayInputStream("two".getBytes(StandardCharsets.UTF_8)), false));
        assertTrue(storage.saveFile(key, new ByteArrayInputStream("two".getBytes(StandardCharsets.UTF_8)), true));
        try (InputStream in = storage.getInputStream(key).orElseThrow()) {
            assertEquals("two", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (OutputStream out = storage.getOutputStream(key, true)) {
            out.write("three".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(5, storage.getFileSize(key));
    }

    @Test
    void saveFromAFileDownloadAndDelete() throws Exception {
        FileBinaryStorage storage = storage();
        Path source = dir.resolve("source.pdf");
        Files.writeString(source, "%PDF");
        BinaryStorageKey key = BinaryStorageKey.guild("g1", "docs/rules.pdf");

        assertTrue(storage.saveFile(key, source.toFile(), false));
        assertFalse(storage.saveFile(key, dir.resolve("missing.pdf").toFile(), true), "non-existent source");
        assertEquals("application/pdf", storage.getContentType(key));

        File dest = dir.resolve("out").resolve("rules.pdf").toFile();
        assertTrue(storage.downloadFile(key, dest));
        assertEquals("%PDF", Files.readString(dest.toPath()));
        assertFalse(storage.downloadFile(BinaryStorageKey.guild("g1", "docs/none.pdf"), dest), "absent");

        assertTrue(storage.deleteFile(key));
        assertFalse(storage.fileExists(key));
        assertFalse(storage.deleteFile(key), "already gone");
        assertFalse(dir.resolve("binary").resolve("guild").resolve("g1").resolve("docs").toFile().exists(),
                "empty parent directories are pruned up to the base directory");
        assertTrue(dir.resolve("binary").toFile().isDirectory());
    }

    @Test
    void listingAndDirectories() throws Exception {
        FileBinaryStorage storage = storage();
        storage.saveFile(BinaryStorageKey.global("covers/a.jpg"), new ByteArrayInputStream(new byte[1]), true);
        storage.saveFile(BinaryStorageKey.global("covers/b.jpg"), new ByteArrayInputStream(new byte[1]), true);
        storage.saveFile(BinaryStorageKey.global("covers/sub/c.jpg"), new ByteArrayInputStream(new byte[1]), true);

        List<String> files = storage.listFiles(BinaryStorageKey.global("covers"));
        assertEquals(Set.of("covers/a.jpg", "covers/b.jpg"), Set.copyOf(files), "direct children only, relative to the scope");
        assertTrue(storage.listFiles(BinaryStorageKey.global("nope")).isEmpty());
        assertTrue(storage.isDirectory(BinaryStorageKey.global("covers")));
        assertFalse(storage.isDirectory(BinaryStorageKey.global("covers/a.jpg")));
        assertTrue(storage.createDirectory(BinaryStorageKey.global("empty/dir")));
        assertTrue(storage.createDirectory(BinaryStorageKey.global("empty/dir")), "already there");
        assertFalse(storage.createDirectory(BinaryStorageKey.global("covers/a.jpg")), "a file is not a directory");
        assertFalse(storage.deleteFile(BinaryStorageKey.global("covers")), "directories are not deleted through deleteFile");
        assertTrue(storage.close());
    }

    @Test
    void contentTypesAreGuessedFromTheExtension() {
        FileBinaryStorage storage = storage();
        assertEquals("image/jpeg", storage.getContentType(BinaryStorageKey.global("A.JPEG")));
        assertEquals("audio/mpeg", storage.getContentType(BinaryStorageKey.global("x.mp3")));
        assertEquals("video/mp4", storage.getContentType(BinaryStorageKey.global("x.mp4")));
        assertEquals("application/json", storage.getContentType(BinaryStorageKey.global("x.json")));
        assertEquals("application/zip", storage.getContentType(BinaryStorageKey.global("x.zip")));
        assertEquals("application/octet-stream", storage.getContentType(BinaryStorageKey.global("x.unknown")));
        assertEquals("application/octet-stream", storage.getContentType(BinaryStorageKey.global("noext")));
        assertEquals("application/octet-stream", storage.getContentType(BinaryStorageKey.global("dir.v2/noext")),
                "a dot in a directory name is not an extension");
        assertEquals("text/plain", storage.getContentType(BinaryStorageKey.global("dir.v2/readme.txt")));
        assertEquals("application/gzip", storage.getContentType(BinaryStorageKey.global("dump.tar.gz")));
    }

    // ---- events ---------------------------------------------------------------------------------------------------

    static class StubPlugin implements Plugin {
        private PluginLifecycle lifecycle = PluginLifecycle.LOADED;
        @Override public String getId() { return "t"; }
        @Override public String getName() { return "t"; }
        @Override public String getVersion() { return "1"; }
        @Override public void onLoad(PluginContext context) { }
        @Override public void onEnable() { }
        @Override public void onDisable() { }
        @Override public PluginLifecycle getLifecycle() { return lifecycle; }
        @Override public void setLifecycle(PluginLifecycle lifecycle) { this.lifecycle = lifecycle; }
    }

    static class Veto {
        boolean uploads, downloads, deletes;
        int seen;
        @EventHandler public void on(FileUploadEvent e) { seen++; if (uploads) e.setCancelled(true); }
        @EventHandler public void on(FileDownloadEvent e) { seen++; if (downloads) e.setCancelled(true); }
        @EventHandler public void on(FileDeleteEvent e) { seen++; if (deletes) e.setCancelled(true); }
    }

    @Test
    void listenersCanVetoUploadsDownloadsAndDeletions() throws Exception {
        Veto veto = new Veto();
        events.registerListener(veto, new StubPlugin());
        FileBinaryStorage storage = storage();
        BinaryStorageKey key = BinaryStorageKey.global("a.txt");
        Path source = dir.resolve("s.txt");
        Files.writeString(source, "x");

        veto.uploads = true;
        assertFalse(storage.saveFile(key, source.toFile(), true));
        assertFalse(storage.saveFile(key, new ByteArrayInputStream(new byte[1]), true));
        assertFalse(storage.fileExists(key));
        veto.uploads = false;
        assertTrue(storage.saveFile(key, source.toFile(), true));

        veto.downloads = true;
        assertFalse(storage.downloadFile(key, dir.resolve("d.txt").toFile()));
        veto.deletes = true;
        assertFalse(storage.deleteFile(key));
        assertTrue(storage.fileExists(key));
        assertEquals(6, veto.seen, "2 vetoed uploads, 1 accepted file upload (file event + stream event), download, delete");
    }
}
