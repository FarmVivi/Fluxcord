package fr.farmvivi.fluxcord.plugins.music.playlist;

import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.ScopedStorage;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.playlist.Playlist.PlaylistTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Stores and retrieves the named playlists of {@code /playlist}.
 *
 * <p>A personal playlist lives in the user scope of the plugin storage, a server playlist in the
 * guild scope, both under the key {@code playlists.<normalized name>}. Nothing is kept in memory:
 * each operation reads the owner's scope, so several shards or a restart never work on a stale copy.
 */
public class PlaylistManager {
    /** Defaults mirroring {@code config.yml} ({@code playlists.*}). */
    public static final int DEFAULT_MAX_PER_USER = 10;
    public static final int DEFAULT_MAX_PER_GUILD = 25;
    public static final int DEFAULT_MAX_TRACKS = 100;
    public static final int MAX_NAME_LENGTH = 32;

    private static final Logger logger = LoggerFactory.getLogger(PlaylistManager.class);
    private static final String KEY_PREFIX = "playlists.";
    private static final Pattern VALID_NAME = Pattern.compile("[\\w -]{1," + MAX_NAME_LENGTH + "}");

    private final PluginDataStorageAdapter storage;
    private final int maxPerUser;
    private final int maxPerGuild;
    private final int maxTracks;

    public PlaylistManager(MusicPlugin plugin) {
        this(plugin.getStorage(),
                plugin.getConfiguration().getInt("playlists.max_per_user", DEFAULT_MAX_PER_USER),
                plugin.getConfiguration().getInt("playlists.max_per_guild", DEFAULT_MAX_PER_GUILD),
                plugin.getConfiguration().getInt("playlists.max_tracks", DEFAULT_MAX_TRACKS));
    }

    public PlaylistManager(PluginDataStorageAdapter storage, int maxPerUser, int maxPerGuild, int maxTracks) {
        this.storage = storage;
        this.maxPerUser = Math.max(1, maxPerUser);
        this.maxPerGuild = Math.max(1, maxPerGuild);
        this.maxTracks = Math.max(1, maxTracks);
    }

    /** Why a {@link #save} call did or did not go through. */
    public enum SaveResult {
        /** The playlist was created. */
        CREATED,
        /** An existing playlist with the same name was overwritten. */
        REPLACED,
        /** The name is empty, too long, or contains unsupported characters. */
        INVALID_NAME,
        /** There was nothing to save. */
        NO_TRACKS,
        /** The owner already has the maximum number of playlists. */
        TOO_MANY_PLAYLISTS,
        /** The playlist would exceed the configured track limit. */
        TOO_MANY_TRACKS;

        public boolean isSuccess() {
            return this == CREATED || this == REPLACED;
        }
    }

    /**
     * Saves (or overwrites) a playlist.
     *
     * @param scope   personal or server
     * @param ownerId the user id or the guild id
     * @param name    the playlist name as typed by the user
     * @param tracks  the tracks to store
     * @return what happened
     */
    public SaveResult save(PlaylistScope scope, String ownerId, String name, List<PlaylistTrack> tracks) {
        String key = normalize(name);
        if (key == null) {
            return SaveResult.INVALID_NAME;
        }
        if (tracks == null || tracks.isEmpty()) {
            return SaveResult.NO_TRACKS;
        }
        if (tracks.size() > maxTracks) {
            return SaveResult.TOO_MANY_TRACKS;
        }

        ScopedStorage scoped = storageFor(scope, ownerId);
        Optional<Playlist> existing = read(scoped, key);
        if (existing.isEmpty() && countPlaylists(scoped) >= getMaxPlaylists(scope)) {
            return SaveResult.TOO_MANY_PLAYLISTS;
        }

        Playlist playlist = existing
                .map(previous -> new Playlist(name.trim(), ownerId, scope, previous.getCreatedAt(), System.currentTimeMillis()))
                .orElseGet(() -> new Playlist(name.trim(), ownerId, scope));
        playlist.setTracks(tracks);

        scoped.set(KEY_PREFIX + key, playlist.toMap());
        storage.saveAll();
        logger.debug("Saved {} playlist '{}' ({} tracks) for {}", scope, key, tracks.size(), ownerId);
        return existing.isPresent() ? SaveResult.REPLACED : SaveResult.CREATED;
    }

    /**
     * Finds a playlist by name (case-insensitive).
     *
     * @param scope   personal or server
     * @param ownerId the user id or the guild id
     * @param name    the playlist name
     * @return the playlist, empty when there is none
     */
    public Optional<Playlist> find(PlaylistScope scope, String ownerId, String name) {
        String key = normalize(name);
        if (key == null) {
            return Optional.empty();
        }
        return read(storageFor(scope, ownerId), key);
    }

    /**
     * Lists the playlists of an owner, by name.
     *
     * @param scope   personal or server
     * @param ownerId the user id or the guild id
     * @return the playlists, never null
     */
    public List<Playlist> list(PlaylistScope scope, String ownerId) {
        ScopedStorage scoped = storageFor(scope, ownerId);
        List<Playlist> playlists = new ArrayList<>();
        for (String key : playlistKeys(scoped)) {
            read(scoped, key).ifPresent(playlists::add);
        }
        playlists.sort(Comparator.comparing(playlist -> playlist.getName().toLowerCase(Locale.ROOT)));
        return playlists;
    }

    /**
     * Deletes a playlist.
     *
     * @param scope   personal or server
     * @param ownerId the user id or the guild id
     * @param name    the playlist name
     * @return true when a playlist was deleted
     */
    public boolean delete(PlaylistScope scope, String ownerId, String name) {
        String key = normalize(name);
        if (key == null) {
            return false;
        }
        ScopedStorage scoped = storageFor(scope, ownerId);
        if (read(scoped, key).isEmpty()) {
            return false;
        }
        scoped.remove(KEY_PREFIX + key);
        storage.saveAll();
        logger.debug("Deleted {} playlist '{}' of {}", scope, key, ownerId);
        return true;
    }

    /** @return the maximum number of playlists allowed in that scope */
    public int getMaxPlaylists(PlaylistScope scope) {
        return scope == PlaylistScope.GUILD ? maxPerGuild : maxPerUser;
    }

    /** @return the maximum number of tracks a playlist may hold */
    public int getMaxTracks() {
        return maxTracks;
    }

    /**
     * Normalizes a playlist name into a storage key.
     *
     * @param name the name as typed
     * @return the key, or {@code null} when the name is not usable
     */
    public static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || !VALID_NAME.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private ScopedStorage storageFor(PlaylistScope scope, String ownerId) {
        return scope == PlaylistScope.GUILD ? storage.getGuildStorage(ownerId) : storage.getUserStorage(ownerId);
    }

    @SuppressWarnings("unchecked")
    private Optional<Playlist> read(ScopedStorage scoped, String key) {
        try {
            return scoped.get(KEY_PREFIX + key, Map.class)
                    .map(map -> Playlist.fromMap((Map<String, Object>) map));
        } catch (RuntimeException e) {
            logger.warn("Ignoring unreadable playlist '{}': {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private List<String> playlistKeys(ScopedStorage scoped) {
        List<String> keys = new ArrayList<>();
        for (String key : scoped.getKeys()) {
            if (key.startsWith(KEY_PREFIX)) {
                keys.add(key.substring(KEY_PREFIX.length()));
            }
        }
        return keys;
    }

    private int countPlaylists(ScopedStorage scoped) {
        return playlistKeys(scoped).size();
    }
}
