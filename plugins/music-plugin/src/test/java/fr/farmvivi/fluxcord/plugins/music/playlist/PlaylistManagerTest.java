package fr.farmvivi.fluxcord.plugins.music.playlist;

import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.StorageKey;
import fr.farmvivi.fluxcord.plugins.music.playlist.Playlist.PlaylistTrack;
import fr.farmvivi.fluxcord.plugins.music.playlist.PlaylistManager.SaveResult;
import fr.farmvivi.fluxcord.plugins.music.testing.MemoryDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PlaylistManager}: where a playlist is stored, the limits from {@code config.yml} and the
 * outcome reported to {@code /playlist save}.
 */
class PlaylistManagerTest {

    private static final String PLUGIN_ID = "music-plugin";

    private MemoryDataStorage storage;
    private PlaylistManager playlists;

    @BeforeEach
    void setUp() {
        storage = new MemoryDataStorage();
        playlists = new PlaylistManager(new PluginDataStorageAdapter(PLUGIN_ID, storage), 2, 3, 4);
    }

    private List<PlaylistTrack> tracks(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new PlaylistTrack("https://example.test/" + i, "Track " + i, "Author", 1000L * (i + 1)))
                .toList();
    }

    @Test
    void aPersonalPlaylistIsStoredInTheOwnerUserScope() {
        assertEquals(SaveResult.CREATED, playlists.save(PlaylistScope.USER, "u1", "Road Trip", tracks(2)));

        assertEquals(java.util.Set.of(PLUGIN_ID + ".playlists.road trip"),
                storage.scope(StorageKey.userScope("u1")).keySet());
        assertTrue(storage.scope(StorageKey.guildScope("u1")).isEmpty());
        assertTrue(storage.getSaveCount() > 0, "the write is flushed");
    }

    @Test
    void aServerPlaylistIsStoredInTheGuildScope() {
        playlists.save(PlaylistScope.GUILD, "g1", "Party", tracks(1));

        assertEquals(1, storage.scope(StorageKey.guildScope("g1")).size());
        assertTrue(playlists.find(PlaylistScope.GUILD, "g1", "party").isPresent());
        assertTrue(playlists.find(PlaylistScope.USER, "g1", "party").isEmpty(), "scopes are independent");
    }

    @Test
    void namesAreCaseAndSpaceInsensitiveButKeepTheirDisplayForm() {
        playlists.save(PlaylistScope.USER, "u1", "  Chill Vibes ", tracks(1));

        Optional<Playlist> found = playlists.find(PlaylistScope.USER, "u1", "chill vibes");
        assertTrue(found.isPresent());
        assertEquals("Chill Vibes", found.get().getName(), "the name is displayed as it was typed");
    }

    @Test
    void savingTwiceReplacesTheTracksAndKeepsTheCreationDate() throws Exception {
        playlists.save(PlaylistScope.USER, "u1", "mix", tracks(1));
        long createdAt = playlists.find(PlaylistScope.USER, "u1", "mix").orElseThrow().getCreatedAt();
        Thread.sleep(2);

        assertEquals(SaveResult.REPLACED, playlists.save(PlaylistScope.USER, "u1", "mix", tracks(3)));

        Playlist playlist = playlists.find(PlaylistScope.USER, "u1", "mix").orElseThrow();
        assertEquals(3, playlist.getTrackCount());
        assertEquals(createdAt, playlist.getCreatedAt());
        assertTrue(playlist.getUpdatedAt() >= createdAt);
        assertEquals(1, playlists.list(PlaylistScope.USER, "u1").size(), "no duplicate entry");
    }

    @Test
    void theTracksSurviveTheStorageRoundTrip() {
        playlists.save(PlaylistScope.USER, "u1", "mix", tracks(2));

        List<PlaylistTrack> restored = playlists.find(PlaylistScope.USER, "u1", "mix").orElseThrow().getTracks();

        assertEquals(tracks(2), restored);
        assertEquals(3000L, playlists.find(PlaylistScope.USER, "u1", "mix").orElseThrow().getTotalDuration());
    }

    @Test
    void anInvalidNameIsRejectedBeforeAnythingIsWritten() {
        for (String name : List.of("", "   ", "a".repeat(PlaylistManager.MAX_NAME_LENGTH + 1), "bad/name", "why?")) {
            assertEquals(SaveResult.INVALID_NAME, playlists.save(PlaylistScope.USER, "u1", name, tracks(1)), name);
        }
        assertEquals(SaveResult.INVALID_NAME, playlists.save(PlaylistScope.USER, "u1", null, tracks(1)));
        assertTrue(storage.scope(StorageKey.userScope("u1")).isEmpty());
    }

    @Test
    void anEmptyQueueIsNotSaved() {
        assertEquals(SaveResult.NO_TRACKS, playlists.save(PlaylistScope.USER, "u1", "mix", List.of()));
        assertEquals(SaveResult.NO_TRACKS, playlists.save(PlaylistScope.USER, "u1", "mix", null));
    }

    @Test
    void theConfiguredLimitsAreEnforced() {
        assertEquals(2, playlists.getMaxPlaylists(PlaylistScope.USER));
        assertEquals(3, playlists.getMaxPlaylists(PlaylistScope.GUILD));
        assertEquals(4, playlists.getMaxTracks());

        assertEquals(SaveResult.TOO_MANY_TRACKS, playlists.save(PlaylistScope.USER, "u1", "long", tracks(5)));

        playlists.save(PlaylistScope.USER, "u1", "one", tracks(1));
        playlists.save(PlaylistScope.USER, "u1", "two", tracks(1));
        assertEquals(SaveResult.TOO_MANY_PLAYLISTS, playlists.save(PlaylistScope.USER, "u1", "three", tracks(1)));
        assertEquals(SaveResult.REPLACED, playlists.save(PlaylistScope.USER, "u1", "two", tracks(2)),
                "overwriting an existing one is always allowed");
        assertEquals(SaveResult.CREATED, playlists.save(PlaylistScope.USER, "u2", "three", tracks(1)),
                "the limit is per owner");
    }

    @Test
    void listingIsSortedByNameAndScopedToTheOwner() {
        playlists.save(PlaylistScope.USER, "u1", "Zulu", tracks(1));
        playlists.save(PlaylistScope.USER, "u1", "alpha", tracks(1));
        playlists.save(PlaylistScope.USER, "u2", "other", tracks(1));

        assertEquals(List.of("alpha", "Zulu"), playlists.list(PlaylistScope.USER, "u1").stream()
                .map(Playlist::getName).toList());
        assertEquals(List.of("other"), playlists.list(PlaylistScope.USER, "u2").stream()
                .map(Playlist::getName).toList());
        assertTrue(playlists.list(PlaylistScope.USER, "nobody").isEmpty());
    }

    @Test
    void deletingRemovesOnlyThatPlaylist() {
        playlists.save(PlaylistScope.USER, "u1", "one", tracks(1));
        playlists.save(PlaylistScope.USER, "u1", "two", tracks(1));

        assertTrue(playlists.delete(PlaylistScope.USER, "u1", "ONE"), "case-insensitive");
        assertFalse(playlists.delete(PlaylistScope.USER, "u1", "one"), "already gone");
        assertFalse(playlists.delete(PlaylistScope.USER, "u1", "bad/name"));
        assertEquals(List.of("two"), playlists.list(PlaylistScope.USER, "u1").stream()
                .map(Playlist::getName).toList());
    }

    @Test
    void anotherPluginKeyInTheSameScopeIsIgnored() {
        storage.set(StorageKey.user("u1", PLUGIN_ID + ".playback_state"), "not a playlist");
        playlists.save(PlaylistScope.USER, "u1", "mix", tracks(1));

        assertEquals(List.of("mix"), playlists.list(PlaylistScope.USER, "u1").stream()
                .map(Playlist::getName).toList());
    }
}
