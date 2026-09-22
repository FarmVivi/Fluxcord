package fr.farmvivi.fluxcord.plugins.music.state;

import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PlaybackState}: the snapshot written every few seconds and read back at boot to resume
 * playback. It travels through the generic data storage as a plain map, so the map round-trip and
 * the defaults applied to an incomplete map are the contract.
 */
class PlaybackStateTest {

    private PlaybackState fullState() {
        PlaybackState state = new PlaybackState();
        state.setVoiceChannelId("vc");
        state.setTextChannelId("tc");
        state.setCurrentTrack("dHJhY2s=");
        state.setCurrentPosition(42_000);
        state.setQueue(List.of("a", "b"));
        state.setVolume(73);
        state.setPaused(true);
        state.setLoopMode(true);
        state.setLoopQueueMode(true);
        state.setShuffleMode(true);
        state.setSavedAt(1_700_000_000_000L);
        return state;
    }

    @Test
    void mapRoundTripKeepsEveryField() {
        PlaybackState restored = PlaybackState.fromMap(fullState().toMap());

        assertEquals("vc", restored.getVoiceChannelId());
        assertEquals("tc", restored.getTextChannelId());
        assertEquals("dHJhY2s=", restored.getCurrentTrack());
        assertEquals(42_000, restored.getCurrentPosition());
        assertEquals(List.of("a", "b"), restored.getQueue());
        assertEquals(73, restored.getVolume());
        assertTrue(restored.isPaused());
        assertTrue(restored.isLoopMode());
        assertTrue(restored.isLoopQueueMode());
        assertTrue(restored.isShuffleMode());
        assertEquals(1_700_000_000_000L, restored.getSavedAt());
    }

    @Test
    void anEmptyMapFallsBackToSafeDefaults() {
        PlaybackState restored = PlaybackState.fromMap(Map.<String, Object>of());

        assertNull(restored.getVoiceChannelId());
        assertNull(restored.getCurrentTrack());
        assertEquals(0, restored.getCurrentPosition());
        assertTrue(restored.getQueue().isEmpty());
        assertEquals(MusicPlayer.DEFAULT_VOLUME, restored.getVolume(), "same default as a fresh player");
        assertFalse(restored.isPaused());
        assertFalse(restored.hasPlayback());
    }

    @Test
    void numbersComeBackFromJsonAsDoubles() {
        // Gson deserializes untyped numbers as Double; the state must survive that.
        PlaybackState restored = PlaybackState.fromMap(Map.<String, Object>of(
                "currentPosition", 12_345.0,
                "volume", 80.0,
                "savedAt", 1.7e12));

        assertEquals(12_345, restored.getCurrentPosition());
        assertEquals(80, restored.getVolume());
        assertEquals(1_700_000_000_000L, restored.getSavedAt());
    }

    @Test
    void aQueueWithNonStringEntriesIsFiltered() {
        PlaybackState restored = PlaybackState.fromMap(Map.<String, Object>of("queue", List.of("ok", 5, true)));
        assertEquals(List.of("ok"), restored.getQueue());
    }

    @Test
    void hasPlaybackCoversBothTheCurrentTrackAndTheQueue() {
        PlaybackState state = new PlaybackState();
        assertFalse(state.hasPlayback());
        state.setQueue(List.of("a"));
        assertTrue(state.hasPlayback());
        state.setQueue(List.of());
        state.setCurrentTrack("a");
        assertTrue(state.hasPlayback());
    }

    @Test
    void expiryNeedsBothATtlAndATimestamp() {
        PlaybackState state = new PlaybackState();
        state.setSavedAt(System.currentTimeMillis() - 60_000);

        assertTrue(state.isExpired(30_000));
        assertFalse(state.isExpired(120_000));
        assertFalse(state.isExpired(0), "a ttl of 0 means never expire");
        assertFalse(state.isExpired(-1));

        state.setSavedAt(0);
        assertFalse(state.isExpired(1), "a state without a timestamp is never discarded");
    }
}
