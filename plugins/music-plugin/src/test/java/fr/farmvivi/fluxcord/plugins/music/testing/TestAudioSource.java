package fr.farmvivi.fluxcord.plugins.music.testing;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

import java.io.DataInput;
import java.io.DataOutput;

/**
 * A LavaPlayer source whose tracks can be encoded and decoded but never actually play.
 *
 * <p>Track persistence ({@code TrackCodec}, {@code PlaybackState}) goes through the real LavaPlayer
 * binary format, which needs a registered source manager that owns the track — a Mockito mock
 * cannot be encoded. Tracks produced here survive an encode/decode round-trip with their metadata.
 */
public class TestAudioSource implements AudioSourceManager {

    /** @return a player manager with only this source registered */
    public static AudioPlayerManager newPlayerManager() {
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        manager.registerSourceManager(new TestAudioSource());
        return manager;
    }

    /** @return a playable-looking track owned by this source */
    public AudioTrack track(String title, long duration) {
        return new TestTrack(new AudioTrackInfo(title, "author", duration, title, false,
                "https://example.test/" + title), this);
    }

    /** @return a track from the source registered on that manager, so it can be encoded */
    public static AudioTrack trackOf(AudioPlayerManager manager, String title, long duration) {
        return ((TestAudioSource) manager.source(TestAudioSource.class)).track(title, duration);
    }

    @Override
    public String getSourceName() {
        return "test";
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
        return track(reference.identifier, 60_000);
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) {
        // The manager writes the track info itself; this source carries no extra data.
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) {
        return new TestTrack(trackInfo, this);
    }

    @Override
    public void shutdown() {
        // nothing to release
    }

    private static class TestTrack extends BaseAudioTrack {
        private final TestAudioSource source;

        TestTrack(AudioTrackInfo trackInfo, TestAudioSource source) {
            super(trackInfo);
            this.source = source;
        }

        @Override
        public void process(LocalAudioTrackExecutor executor) {
            throw new UnsupportedOperationException("test tracks are never played");
        }

        @Override
        public AudioTrack makeShallowClone() {
            return new TestTrack(trackInfo, source);
        }

        @Override
        public AudioSourceManager getSourceManager() {
            return source;
        }

        @Override
        public boolean isSeekable() {
            return true;
        }
    }
}
