package fr.farmvivi.fluxcord.plugins.music.playlist;

import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.ScopedStorage;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages playlists for users and guilds.
 */
public class PlaylistManager {
    private static final Logger logger = LoggerFactory.getLogger(PlaylistManager.class);

    private final MusicPlugin plugin;
    private final Map<String, Playlist> userPlaylists = new ConcurrentHashMap<>();
    private final Map<String, Playlist> guildPlaylists = new ConcurrentHashMap<>();

    public PlaylistManager(MusicPlugin plugin) {
        this.plugin = plugin;
        loadPlaylists();
    }

    /**
     * Creates a new user playlist.
     */
    public Playlist createUserPlaylist(String userId, String name) {
        String key = userId + ":" + name.toLowerCase();
        if (userPlaylists.containsKey(key)) {
            return null;
        }

        Playlist playlist = new Playlist(name, userId, false);
        userPlaylists.put(key, playlist);
        savePlaylists();
        return playlist;
    }

    /**
     * Creates a new guild playlist.
     */
    public Playlist createGuildPlaylist(String guildId, String name) {
        String key = guildId + ":" + name.toLowerCase();
        if (guildPlaylists.containsKey(key)) {
            return null;
        }

        Playlist playlist = new Playlist(name, guildId, true);
        guildPlaylists.put(key, playlist);
        savePlaylists();
        return playlist;
    }

    /**
     * Gets a user playlist.
     */
    public Playlist getUserPlaylist(String userId, String name) {
        return userPlaylists.get(userId + ":" + name.toLowerCase());
    }

    /**
     * Gets a guild playlist.
     */
    public Playlist getGuildPlaylist(String guildId, String name) {
        return guildPlaylists.get(guildId + ":" + name.toLowerCase());
    }

    /**
     * Gets all playlists for a user.
     */
    public List<Playlist> getUserPlaylists(String userId) {
        List<Playlist> playlists = new ArrayList<>();
        for (Map.Entry<String, Playlist> entry : userPlaylists.entrySet()) {
            if (entry.getKey().startsWith(userId + ":")) {
                playlists.add(entry.getValue());
            }
        }
        return playlists;
    }

    /**
     * Gets all playlists for a guild.
     */
    public List<Playlist> getGuildPlaylists(String guildId) {
        List<Playlist> playlists = new ArrayList<>();
        for (Map.Entry<String, Playlist> entry : guildPlaylists.entrySet()) {
            if (entry.getKey().startsWith(guildId + ":")) {
                playlists.add(entry.getValue());
            }
        }
        return playlists;
    }

    /**
     * Deletes a user playlist.
     */
    public boolean deleteUserPlaylist(String userId, String name) {
        String key = userId + ":" + name.toLowerCase();
        if (userPlaylists.remove(key) != null) {
            savePlaylists();
            return true;
        }
        return false;
    }

    /**
     * Deletes a guild playlist.
     */
    public boolean deleteGuildPlaylist(String guildId, String name) {
        String key = guildId + ":" + name.toLowerCase();
        if (guildPlaylists.remove(key) != null) {
            savePlaylists();
            return true;
        }
        return false;
    }

    /**
     * Saves all playlists to storage.
     */
    public void saveAllPlaylists() {
        savePlaylists();
    }

    /**
     * Loads playlists from storage.
     */
    private void loadPlaylists() {
        PluginDataStorageAdapter adapter = plugin.getStorage();
        ScopedStorage storage = adapter.getGlobalStorage();

        // Load user playlists
        Map<String, Object> userData = storage.getAll().entrySet().stream()
                .filter(e -> e.getKey().startsWith("playlists.user."))
                .collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey().substring("playlists.user.".length()),
                        Map.Entry::getValue
                ));
        for (Map.Entry<String, Object> entry : userData.entrySet()) {
            try {
                Map<String, Object> playlistData = (Map<String, Object>) entry.getValue();
                Playlist playlist = Playlist.fromMap(playlistData);
                userPlaylists.put(entry.getKey(), playlist);
            } catch (Exception e) {
                logger.error("Failed to load user playlist: {}", entry.getKey(), e);
            }
        }

        // Load guild playlists
        Map<String, Object> guildData = storage.getAll().entrySet().stream()
                .filter(e -> e.getKey().startsWith("playlists.guild."))
                .collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey().substring("playlists.guild.".length()),
                        Map.Entry::getValue
                ));
        for (Map.Entry<String, Object> entry : guildData.entrySet()) {
            try {
                Map<String, Object> playlistData = (Map<String, Object>) entry.getValue();
                Playlist playlist = Playlist.fromMap(playlistData);
                guildPlaylists.put(entry.getKey(), playlist);
            } catch (Exception e) {
                logger.error("Failed to load guild playlist: {}", entry.getKey(), e);
            }
        }

        logger.info("Loaded {} user playlists and {} guild playlists",
                userPlaylists.size(), guildPlaylists.size());
    }

    /**
     * Saves playlists to storage.
     */
    private void savePlaylists() {
        PluginDataStorageAdapter adapter = plugin.getStorage();
        ScopedStorage storage = adapter.getGlobalStorage();

        // First clear previous entries for this namespace subset
        // Remove existing user playlists keys
        storage.getAll().keySet().stream()
                .filter(k -> k.startsWith("playlists.user."))
                .forEach(storage::remove);

        // Remove existing guild playlists keys
        storage.getAll().keySet().stream()
                .filter(k -> k.startsWith("playlists.guild."))
                .forEach(storage::remove);

        // Save user playlists
        for (Map.Entry<String, Playlist> entry : userPlaylists.entrySet()) {
            storage.set("playlists.user." + entry.getKey(), entry.getValue().toMap());
        }

        // Save guild playlists
        for (Map.Entry<String, Playlist> entry : guildPlaylists.entrySet()) {
            storage.set("playlists.guild." + entry.getKey(), entry.getValue().toMap());
        }

        adapter.saveAll();
    }
}