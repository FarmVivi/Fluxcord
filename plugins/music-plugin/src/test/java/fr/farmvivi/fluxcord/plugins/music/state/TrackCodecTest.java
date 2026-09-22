package fr.farmvivi.fluxcord.plugins.music.state;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import fr.farmvivi.fluxcord.plugins.music.testing.TestAudioSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TrackCodec}: what {@code PlaybackState} stores for every queued track. A failure here is
 * silent by design (the track is dropped, not the whole state), so the null paths matter.
 */
class TrackCodecTest {

    private final AudioPlayerManager manager = TestAudioSource.newPlayerManager();

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void aTrackSurvivesTheRoundTripWithItsMetadata() {
        AudioTrack track = TestAudioSource.trackOf(manager, "Never Gonna", 212_000);

        String encoded = TrackCodec.encode(manager, track);
        assertNotNull(encoded);
        assertDoesNotThrow(() -> Base64.getDecoder().decode(encoded), "the stored form is Base64");

        AudioTrack decoded = TrackCodec.decode(manager, encoded);
        assertNotNull(decoded);
        assertEquals("Never Gonna", decoded.getInfo().title);
        assertEquals("author", decoded.getInfo().author);
        assertEquals(212_000, decoded.getInfo().length);
        assertEquals("https://example.test/Never Gonna", decoded.getInfo().uri);
    }

    @Test
    void thePositionIsCarriedByTheEncoding() {
        AudioTrack track = TestAudioSource.trackOf(manager, "a", 100_000);
        track.setPosition(42_000);

        AudioTrack decoded = TrackCodec.decode(manager, TrackCodec.encode(manager, track));

        // LavaPlayer 2.x writes the position into the message; PlaybackState stores it again on its
        // own, and MusicPlayer.restoreFromState re-applies it, so the two always agree.
        assertEquals(42_000, decoded.getPosition());
    }

    @Test
    void nothingToEncodeOrDecodeGivesNull() {
        assertNull(TrackCodec.encode(manager, null));
        assertNull(TrackCodec.decode(manager, null));
        assertNull(TrackCodec.decode(manager, ""));
    }

    @Test
    void garbageIsReportedAsNullRatherThanThrowing() {
        assertNull(TrackCodec.decode(manager, "not base64 at all !!"));
        assertNull(TrackCodec.decode(manager, Base64.getEncoder().encodeToString(new byte[]{1, 2, 3})));
    }

    @Test
    void aTrackOfAnUnknownSourceCannotBeDecoded() {
        String encoded = TrackCodec.encode(manager, TestAudioSource.trackOf(manager, "a", 1000));

        AudioPlayerManager other = new com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager();
        try {
            assertNull(TrackCodec.decode(other, encoded), "no source manager to rebuild it");
        } finally {
            other.shutdown();
        }
    }
}
