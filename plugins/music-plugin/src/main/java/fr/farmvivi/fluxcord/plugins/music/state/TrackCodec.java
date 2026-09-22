package fr.farmvivi.fluxcord.plugins.music.state;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageInput;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageOutput;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.DecodedTrackHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Serializes and deserializes {@link AudioTrack} instances to/from Base64 strings.
 *
 * <p>Uses LavaPlayer's native binary encoding, which stores the source manager name and
 * source-specific track data. Decoding rebuilds the track without re-querying the source,
 * so the exact same track (same identifier, metadata, source) is restored. LavaPlayer 2.x also
 * carries the playback position, but {@code PlaybackState} stores it separately and
 * {@code MusicPlayer.restoreFromState} re-applies it, so the resumed position never depends on it.
 */
public final class TrackCodec {
    private static final Logger logger = LoggerFactory.getLogger(TrackCodec.class);

    private TrackCodec() {
    }

    /**
     * Encodes an audio track to a Base64 string.
     *
     * @param playerManager the player manager (must have the track's source registered)
     * @param track         the track to encode
     * @return the Base64-encoded track, or {@code null} if encoding failed
     */
    public static String encode(AudioPlayerManager playerManager, AudioTrack track) {
        if (track == null) {
            return null;
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            playerManager.encodeTrack(new MessageOutput(baos), track);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            logger.warn("Failed to encode track '{}': {}",
                    track.getInfo() != null ? track.getInfo().title : "?", e.getMessage());
            return null;
        }
    }

    /**
     * Decodes a Base64 string back into an audio track.
     *
     * @param playerManager the player manager (must have the track's source registered)
     * @param encoded       the Base64-encoded track
     * @return the decoded track, or {@code null} if decoding failed
     */
    public static AudioTrack decode(AudioPlayerManager playerManager, String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            DecodedTrackHolder holder = playerManager.decodeTrack(
                    new MessageInput(new ByteArrayInputStream(bytes)));
            return holder != null ? holder.decodedTrack : null;
        } catch (Exception e) {
            logger.warn("Failed to decode a stored track: {}", e.getMessage());
            return null;
        }
    }
}