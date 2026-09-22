package fr.farmvivi.fluxcord.plugins.music.playlist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A named playlist saved by a user or by a guild.
 *
 * <p>Tracks are stored as plain metadata (source URL, title, author, duration), not as encoded
 * LavaPlayer tracks: loading a playlist re-resolves each URL, so a playlist keeps working after a
 * restart and across source-manager updates.
 *
 * <p>Instances travel through the generic data storage as JSON-friendly {@link Map}s
 * ({@link #toMap()} / {@link #fromMap(Map)}).
 */
public class Playlist {
    private final String name;
    private final String ownerId;
    private final PlaylistScope scope;
    private final List<PlaylistTrack> tracks;
    private final long createdAt;
    private long updatedAt;

    public Playlist(String name, String ownerId, PlaylistScope scope) {
        this(name, ownerId, scope, System.currentTimeMillis(), System.currentTimeMillis());
    }

    public Playlist(String name, String ownerId, PlaylistScope scope, long createdAt, long updatedAt) {
        this.name = name;
        this.ownerId = ownerId;
        this.scope = scope;
        this.tracks = new ArrayList<>();
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Rebuilds a playlist from a stored map. Missing fields fall back to safe defaults so a
     * partially written entry never breaks the whole listing.
     *
     * @param map the stored map
     * @return the playlist
     */
    @SuppressWarnings("unchecked")
    public static Playlist fromMap(Map<String, Object> map) {
        String name = map.get("name") instanceof String s ? s : "";
        String ownerId = map.get("ownerId") instanceof String s ? s : null;
        PlaylistScope scope = Boolean.TRUE.equals(map.get("isGuildPlaylist")) ? PlaylistScope.GUILD : PlaylistScope.USER;
        long createdAt = map.get("createdAt") instanceof Number n ? n.longValue() : 0L;
        long updatedAt = map.get("updatedAt") instanceof Number n ? n.longValue() : createdAt;

        Playlist playlist = new Playlist(name, ownerId, scope, createdAt, updatedAt);

        if (map.get("tracks") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> trackMap) {
                    playlist.tracks.add(PlaylistTrack.fromMap((Map<String, Object>) trackMap));
                }
            }
        }
        return playlist;
    }

    /**
     * Converts this playlist to a JSON-friendly map for storage.
     *
     * @return the map representation
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("name", name);
        map.put("ownerId", ownerId);
        map.put("isGuildPlaylist", scope == PlaylistScope.GUILD);
        map.put("createdAt", createdAt);
        map.put("updatedAt", updatedAt);

        List<Map<String, Object>> trackList = new ArrayList<>();
        for (PlaylistTrack track : tracks) {
            trackList.add(track.toMap());
        }
        map.put("tracks", trackList);
        return map;
    }

    /** Appends a track and refreshes the update timestamp. */
    public void addTrack(PlaylistTrack track) {
        if (track == null) {
            return;
        }
        tracks.add(track);
        updatedAt = System.currentTimeMillis();
    }

    /** Replaces every track of this playlist. */
    public void setTracks(List<PlaylistTrack> newTracks) {
        tracks.clear();
        if (newTracks != null) {
            for (PlaylistTrack track : newTracks) {
                if (track != null) {
                    tracks.add(track);
                }
            }
        }
        updatedAt = System.currentTimeMillis();
    }

    /**
     * Removes the track at the given zero-based index.
     *
     * @param index the index
     * @return true when a track was removed
     */
    public boolean removeTrack(int index) {
        if (index < 0 || index >= tracks.size()) {
            return false;
        }
        tracks.remove(index);
        updatedAt = System.currentTimeMillis();
        return true;
    }

    /** Removes every track. */
    public void clear() {
        tracks.clear();
        updatedAt = System.currentTimeMillis();
    }

    /** @return the total duration of the playlist in milliseconds */
    public long getTotalDuration() {
        long total = 0;
        for (PlaylistTrack track : tracks) {
            total += Math.max(0, track.duration());
        }
        return total;
    }

    public String getName() {
        return name;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public PlaylistScope getScope() {
        return scope;
    }

    public boolean isGuildPlaylist() {
        return scope == PlaylistScope.GUILD;
    }

    public List<PlaylistTrack> getTracks() {
        return Collections.unmodifiableList(tracks);
    }

    public int getTrackCount() {
        return tracks.size();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    /**
     * One entry of a playlist: enough metadata to display it without resolving it, plus the URL
     * used to load it again.
     *
     * @param url      the source URL, used to re-resolve the track
     * @param title    the track title
     * @param author   the track author
     * @param duration the track duration in milliseconds
     */
    public record PlaylistTrack(String url, String title, String author, long duration) {

        public static PlaylistTrack fromMap(Map<String, Object> map) {
            return new PlaylistTrack(
                    map.get("url") instanceof String s ? s : null,
                    map.get("title") instanceof String s ? s : "?",
                    map.get("author") instanceof String s ? s : "?",
                    map.get("duration") instanceof Number n ? n.longValue() : 0L);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("url", url);
            map.put("title", title);
            map.put("author", author);
            map.put("duration", duration);
            return map;
        }
    }
}
