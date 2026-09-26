package fr.farmvivi.fluxcord.api.storage.binary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The binary counterpart of {@code ScopedStorage}: same idea, except the namespace is a <em>directory</em>
 * and not a dotted key prefix, which is why the two cannot share an implementation.
 *
 * <p>{@link ScopedBinaryStorage#listFiles} is the part worth pinning: it has to strip the prefix back off
 * on the way out. Without that, a plugin listing its own folder would receive paths it cannot pass back to
 * any other method of the same view — they would be prefixed twice.
 */
class ScopedBinaryStorageTest {

    private BinaryStorage backend;
    private ScopedBinaryStorage guild;

    @BeforeEach
    void setUp() {
        backend = mock(BinaryStorage.class);
        guild = new ScopedBinaryStorage(backend, "guild:g1");
    }

    /** The key the backend was handed by the last call. */
    private BinaryStorageKey capturedKey() {
        ArgumentCaptor<BinaryStorageKey> captor = ArgumentCaptor.forClass(BinaryStorageKey.class);
        verify(backend, atLeastOnce()).fileExists(captor.capture());
        return captor.getValue();
    }

    @Test
    void anUnnamespacedViewPassesPathsThrough() {
        guild.fileExists("covers/a.png");

        assertEquals("guild:g1", guild.getScope());
        assertEquals("", guild.getPrefix());
        assertEquals("covers/a.png", capturedKey().path());
    }

    @Test
    void aNamespacedViewPrefixesWithADirectoryAndNotADot() {
        ScopedBinaryStorage plugin = guild.namespaced("music-plugin");

        plugin.fileExists("covers/a.png");

        assertEquals("music-plugin/", plugin.getPrefix());
        assertEquals("guild:g1", plugin.getScope(), "the scope is untouched");
        assertEquals("music-plugin/covers/a.png", capturedKey().path());
    }

    @Test
    void namespacesNest() {
        ScopedBinaryStorage nested = guild.namespaced("music-plugin").namespaced("covers");

        nested.fileExists("a.png");

        assertEquals("music-plugin/covers/", nested.getPrefix());
        assertEquals("music-plugin/covers/a.png", capturedKey().path());
    }

    @Test
    void listingStripsThePrefixSoTheResultCanBeUsedAgain() {
        // The returned names must be usable with this same view; leaving the prefix on would make every
        // follow-up call resolve to music-plugin/music-plugin/...
        ScopedBinaryStorage plugin = guild.namespaced("music-plugin");
        when(backend.listFiles(any())).thenReturn(List.of(
                "music-plugin/covers/a.png", "music-plugin/covers/b.png"));

        assertEquals(List.of("covers/a.png", "covers/b.png"), plugin.listFiles("covers"));
    }

    @Test
    void listingLeavesAnUnexpectedPathAloneRatherThanTruncatingIt() {
        // A backend answering something outside the namespace must not be silently mangled.
        ScopedBinaryStorage plugin = guild.namespaced("music-plugin");
        when(backend.listFiles(any())).thenReturn(List.of("elsewhere/a.png"));

        assertEquals(List.of("elsewhere/a.png"), plugin.listFiles(""));
    }

    @Test
    void listingAnUnnamespacedViewReturnsWhatTheBackendSaid() {
        when(backend.listFiles(any())).thenReturn(List.of("covers/a.png"));

        assertEquals(List.of("covers/a.png"), guild.listFiles("covers"));
    }

    @Test
    void everyOperationGoesThroughThePrefix() {
        ScopedBinaryStorage plugin = guild.namespaced("p");
        File file = new File("a.png");
        when(backend.getInputStream(any())).thenReturn(Optional.empty());
        when(backend.getPublicUrl(any(), anyInt())).thenReturn(Optional.empty());

        plugin.saveFile("a.png", file, true);
        plugin.saveFile("a.png", new ByteArrayInputStream(new byte[0]), false);
        plugin.getOutputStream("a.png", true);
        plugin.getInputStream("a.png");
        plugin.downloadFile("a.png", file);
        plugin.deleteFile("a.png");
        plugin.getFileSize("a.png");
        plugin.getLastModifiedTime("a.png");
        plugin.getContentType("a.png");
        plugin.createDirectory("covers");
        plugin.isDirectory("covers");
        plugin.getPublicUrl("a.png", 60);

        ArgumentCaptor<BinaryStorageKey> keys = ArgumentCaptor.forClass(BinaryStorageKey.class);
        verify(backend).saveFile(keys.capture(), any(File.class), eq(true));
        assertEquals("p/a.png", keys.getValue().path());
        verify(backend).deleteFile(argThat(key -> key.path().equals("p/a.png")));
        verify(backend).createDirectory(argThat(key -> key.path().equals("p/covers")));
        verify(backend).getPublicUrl(argThat(key -> key.path().equals("p/a.png")), eq(60));
    }

    @Test
    void theBackendAndScopeAreRequired() {
        assertThrows(NullPointerException.class, () -> new ScopedBinaryStorage(null, "global"));
        assertThrows(NullPointerException.class, () -> new ScopedBinaryStorage(backend, null));
        assertThrows(NullPointerException.class, () -> guild.namespaced(null));
    }
}
