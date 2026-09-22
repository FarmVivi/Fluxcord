package fr.farmvivi.fluxcord.plugins.music.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializable snapshot of a guild's music playback state.
 *
 * <p>This captures everything needed to resume playback after a restart: the voice channel,
 * the text channel used for the player message, the current track and its position, the queue,
 * the playback modes and the volume. Tracks are stored as Base64 strings (see {@link TrackCodec}).
 *
 * <p>Instances are (de)serialized to plain JSON-friendly {@link Map}s via {@link #toMap()} and
 * {@link #fromMap(Map)} so they can be persisted through the generic data storage API.
 */
public class PlaybackState {
    /** Volume used when a stored state predates the volume field; kept in sync with {@code MusicPlayer.DEFAULT_VOLUME}. */
    private static final int DEFAULT_VOLUME = 100;

    private String voiceChannelId;
    private String textChannelId;
    private String currentTrack;      // Base64-encoded, may be null
    private long currentPosition;     // playback position in ms
    private List<String> queue = new ArrayList<>(); // Base64-encoded tracks
    private int volume;
    private boolean paused;
    private boolean loopMode;
    private boolean loopQueueMode;
    private boolean shuffleMode;
    private long savedAt;             // epoch millis when this snapshot was captured

    /**
     * Restores a state from a stored map. Unknown or missing fields fall back to safe defaults.
     *
     * @param map the stored map
     * @return the reconstructed state
     */
    @SuppressWarnings("unchecked")
    public static PlaybackState fromMap(Map<String, Object> map) {
        PlaybackState state = new PlaybackState();
        state.voiceChannelId = (String) map.get("voiceChannelId");
        state.textChannelId = (String) map.get("textChannelId");
        state.currentTrack = (String) map.get("currentTrack");
        state.currentPosition = map.get("currentPosition") instanceof Number n ? n.longValue() : 0L;
        Object queueObj = map.get("queue");
        if (queueObj instanceof List<?> list) {
            state.queue = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof String s) {
                    state.queue.add(s);
                }
            }
        }
        state.volume = map.get("volume") instanceof Number n ? n.intValue() : DEFAULT_VOLUME;
        state.paused = Boolean.TRUE.equals(map.get("paused"));
        state.loopMode = Boolean.TRUE.equals(map.get("loopMode"));
        state.loopQueueMode = Boolean.TRUE.equals(map.get("loopQueueMode"));
        state.shuffleMode = Boolean.TRUE.equals(map.get("shuffleMode"));
        state.savedAt = map.get("savedAt") instanceof Number n ? n.longValue() : 0L;
        return state;
    }

    /**
     * Converts this state to a JSON-friendly map for storage.
     *
     * @return the map representation
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("voiceChannelId", voiceChannelId);
        map.put("textChannelId", textChannelId);
        map.put("currentTrack", currentTrack);
        map.put("currentPosition", currentPosition);
        map.put("queue", queue);
        map.put("volume", volume);
        map.put("paused", paused);
        map.put("loopMode", loopMode);
        map.put("loopQueueMode", loopQueueMode);
        map.put("shuffleMode", shuffleMode);
        map.put("savedAt", savedAt);
        return map;
    }

    /**
     * Whether this snapshot is older than the given time-to-live.
     *
     * @param ttlMillis the maximum age in milliseconds; {@code <= 0} means "never expire"
     * @return true if the snapshot should be considered stale and discarded
     */
    public boolean isExpired(long ttlMillis) {
        if (ttlMillis <= 0 || savedAt <= 0) {
            return false;
        }
        return System.currentTimeMillis() - savedAt > ttlMillis;
    }

    /**
     * Whether this state has anything worth restoring (a current track or a non-empty queue).
     *
     * @return true if there is playback to resume
     */
    public boolean hasPlayback() {
        return currentTrack != null || !queue.isEmpty();
    }

    // Getters and setters

    public String getVoiceChannelId() {
        return voiceChannelId;
    }

    public void setVoiceChannelId(String voiceChannelId) {
        this.voiceChannelId = voiceChannelId;
    }

    public String getTextChannelId() {
        return textChannelId;
    }

    public void setTextChannelId(String textChannelId) {
        this.textChannelId = textChannelId;
    }

    public String getCurrentTrack() {
        return currentTrack;
    }

    public void setCurrentTrack(String currentTrack) {
        this.currentTrack = currentTrack;
    }

    public long getCurrentPosition() {
        return currentPosition;
    }

    public void setCurrentPosition(long currentPosition) {
        this.currentPosition = currentPosition;
    }

    public List<String> getQueue() {
        return queue;
    }

    public void setQueue(List<String> queue) {
        this.queue = queue;
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = volume;
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isLoopMode() {
        return loopMode;
    }

    public void setLoopMode(boolean loopMode) {
        this.loopMode = loopMode;
    }

    public boolean isLoopQueueMode() {
        return loopQueueMode;
    }

    public void setLoopQueueMode(boolean loopQueueMode) {
        this.loopQueueMode = loopQueueMode;
    }

    public boolean isShuffleMode() {
        return shuffleMode;
    }

    public void setShuffleMode(boolean shuffleMode) {
        this.shuffleMode = shuffleMode;
    }

    public long getSavedAt() {
        return savedAt;
    }

    public void setSavedAt(long savedAt) {
        this.savedAt = savedAt;
    }
}
