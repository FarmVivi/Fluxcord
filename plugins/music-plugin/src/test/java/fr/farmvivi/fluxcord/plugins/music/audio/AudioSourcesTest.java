package fr.farmvivi.fluxcord.plugins.music.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import fr.farmvivi.fluxcord.plugins.music.source.SearchSourceManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.DataInput;
import java.io.DataOutput;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The two small audio pieces the plugin owns: the JDA send handler fed by LavaPlayer, and the
 * source manager that turns a bare query into a {@code ytsearch:} lookup.
 */
class AudioSourcesTest {

    @Test
    void theSendHandlerOnlyProvidesWhenLavaplayerFilledAFrame() {
        AudioPlayer audioPlayer = mock(AudioPlayer.class);
        when(audioPlayer.provide(any(MutableAudioFrame.class))).thenReturn(false, true);
        AudioPlayerSendHandler handler = new AudioPlayerSendHandler(audioPlayer);

        assertFalse(handler.canProvide(), "nothing to play");
        assertTrue(handler.canProvide());
        assertTrue(handler.isOpus(), "lavaplayer hands Discord Opus, the core relays it untouched");

        ByteBuffer provided = handler.provide20MsAudio();
        assertEquals(0, provided.position(), "the buffer is flipped for reading");
    }

    @Test
    void aBareQueryBecomesASearch() {
        AudioSourceManager youtube = mock(AudioSourceManager.class);
        when(youtube.getSourceName()).thenReturn("youtube");
        AudioItem item = mock(AudioItem.class);
        when(youtube.loadItem(any(), any())).thenReturn(item);
        SearchSourceManager search = new SearchSourceManager(youtube, "ytsearch:");
        AudioPlayerManager manager = mock(AudioPlayerManager.class);

        AudioItem loaded = search.loadItem(manager, new AudioReference("never gonna give you up", null));

        assertSame(item, loaded);
        ArgumentCaptor<AudioReference> reference = ArgumentCaptor.forClass(AudioReference.class);
        verify(youtube).loadItem(same(manager), reference.capture());
        assertEquals("ytsearch:never gonna give you up", reference.getValue().identifier);
        assertEquals("search:youtube", search.getSourceName());
    }

    @Test
    void aUrlIsLeftToTheOtherSourceManagers() {
        AudioSourceManager youtube = mock(AudioSourceManager.class);
        SearchSourceManager search = new SearchSourceManager(youtube, "ytsearch:");

        assertNull(search.loadItem(mock(AudioPlayerManager.class),
                new AudioReference("https://youtube.com/watch?v=x", null)));
        verify(youtube, never()).loadItem(any(), any());
    }

    @Test
    void encodingIsDelegatedToTheWrappedSource() throws Exception {
        AudioSourceManager youtube = mock(AudioSourceManager.class);
        AudioTrack track = mock(AudioTrack.class);
        AudioTrackInfo info = new AudioTrackInfo("t", "a", 1, "id", false, "uri");
        DataOutput output = mock(DataOutput.class);
        DataInput input = mock(DataInput.class);
        when(youtube.isTrackEncodable(track)).thenReturn(true);
        when(youtube.decodeTrack(info, input)).thenReturn(track);
        SearchSourceManager search = new SearchSourceManager(youtube, "ytsearch:");

        assertTrue(search.isTrackEncodable(track));
        search.encodeTrack(track, output);
        assertSame(track, search.decodeTrack(info, input));
        assertDoesNotThrow(search::shutdown);

        verify(youtube).encodeTrack(track, output);
    }
}
