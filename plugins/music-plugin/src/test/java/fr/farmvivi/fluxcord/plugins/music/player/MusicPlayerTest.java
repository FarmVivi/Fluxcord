package fr.farmvivi.fluxcord.plugins.music.player;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.plugins.music.MusicManager;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.state.PlaybackState;
import fr.farmvivi.fluxcord.plugins.music.testing.MemoryDataStorage;
import fr.farmvivi.fluxcord.plugins.music.testing.ScriptedAudioPlayer;
import fr.farmvivi.fluxcord.plugins.music.testing.TestAudioSource;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * {@link MusicPlayer}: what the bot remembers of a guild's playback and how it reacts to the
 * player controls. The LavaPlayer {@link AudioPlayer} is a mock backed by a "currently playing"
 * slot; tracks come from a real source manager so they can actually be encoded into the state.
 */
class MusicPlayerTest {

    private static final String GUILD_ID = "g1";
    private static final String VOICE_ID = "vc1";

    private final MemoryDataStorage storage = new MemoryDataStorage();
    private final com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager playerManager =
            TestAudioSource.newPlayerManager();

    private ScheduledExecutorService scheduler;
    private MusicPlugin plugin;
    private AudioService audioService;
    private AudioPlayer audioPlayer;
    private Guild guild;
    private AudioManager audioManager;
    private MusicPlayer player;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();

        audioPlayer = ScriptedAudioPlayer.create();

        audioManager = mock(AudioManager.class);
        when(audioManager.isConnected()).thenReturn(true);
        // getConnectedChannel() answers a union type, getVoiceChannelById() the concrete one.
        AudioChannelUnion connectedChannel = mock(AudioChannelUnion.class);
        when(connectedChannel.getId()).thenReturn(VOICE_ID);
        when(audioManager.getConnectedChannel()).thenReturn(connectedChannel);
        VoiceChannel voiceChannel = mock(VoiceChannel.class);
        when(voiceChannel.getId()).thenReturn(VOICE_ID);

        guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        when(guild.getName()).thenReturn("guild");
        when(guild.getAudioManager()).thenReturn(audioManager);
        when(guild.getVoiceChannelById(VOICE_ID)).thenReturn(voiceChannel);

        audioService = mock(AudioService.class);
        PluginContext context = mock(PluginContext.class);
        when(context.getAudioService()).thenReturn(audioService);

        MusicManager musicManager = mock(MusicManager.class);
        when(musicManager.getPlayerManager()).thenReturn(playerManager);

        plugin = mock(MusicPlugin.class);
        when(plugin.getLanguage()).thenReturn(mock(PluginLanguageAdapter.class));
        when(plugin.getStorage()).thenReturn(new PluginDataStorageAdapter("music-plugin", storage));
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getContext()).thenReturn(context);
        when(plugin.getMusicManager()).thenReturn(musicManager);
        when(plugin.isPersistenceEnabled()).thenReturn(true);
        when(plugin.getAutoLeaveTimeoutMs()).thenReturn(60_000L);

        player = new MusicPlayer(plugin, guild, audioPlayer);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        playerManager.shutdown();
    }

    private AudioTrack track(String title) {
        return TestAudioSource.trackOf(playerManager, title, 200_000);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> savedState() {
        return (Map<String, Object>) storage.scope("guild:" + GUILD_ID).get("music-plugin." + MusicPlayer.STATE_KEY);
    }

    @Test
    void theFirstTrackRegistersTheSendHandlerOnce() {
        player.playTrack(track("a"));
        player.playTrack(track("b"));

        verify(audioService, times(1)).registerSendHandler(same(guild), same(plugin), any(), eq(100), eq(50));
        assertEquals(1, player.getTrackScheduler().getQueueSize(), "the second one waits its turn");
    }

    @Test
    void playingSavesTheStateWithTheEncodedQueue() {
        player.playTrack(track("current"));
        player.playTrack(track("queued"));

        PlaybackState state = PlaybackState.fromMap(savedState());
        assertEquals(VOICE_ID, state.getVoiceChannelId());
        assertEquals(100, state.getVolume());
        assertNotNull(state.getCurrentTrack());
        assertEquals(1, state.getQueue().size());
        assertTrue(state.hasPlayback());
    }

    @Test
    void nothingIsPersistedWhenThereIsNothingToResume() {
        player.playTrack(track("a"));
        assertNotNull(savedState());

        audioPlayer.stopTrack();
        player.saveState();

        assertNull(savedState(), "an idle player must not resurrect a stale queue on the next boot");
    }

    @Test
    void persistenceCanBeTurnedOff() {
        when(plugin.isPersistenceEnabled()).thenReturn(false);

        player.playTrack(track("a"));

        assertNull(savedState());
    }

    @Test
    void volumeIsClampedAndFollowedByLavaplayer() {
        player.setVolume(150);
        assertEquals(100, player.getVolume());
        player.setVolume(-10);
        assertEquals(0, player.getVolume());

        player.changeVolume(30);
        assertEquals(30, player.getVolume());

        player.toggleMute();
        assertEquals(0, player.getVolume(), "mute");
        player.toggleMute();
        assertEquals(MusicPlayer.DEFAULT_VOLUME, player.getVolume(), "unmute goes back to the default");

        verify(audioPlayer, atLeastOnce()).setVolume(100);
        verify(audioPlayer).setVolume(30);
    }

    @Test
    void theLoopModesAreMutuallyExclusive() {
        TrackScheduler trackScheduler = player.getTrackScheduler();

        player.toggleLoop();
        assertTrue(trackScheduler.isLoopMode());
        assertFalse(trackScheduler.isLoopQueueMode());

        player.toggleLoopQueue();
        assertTrue(trackScheduler.isLoopQueueMode());
        assertFalse(trackScheduler.isLoopMode(), "enabling one disables the other");

        player.toggleLoopQueue();
        assertFalse(trackScheduler.isLoopQueueMode());

        player.toggleShuffle();
        assertTrue(trackScheduler.isShuffleMode());
    }

    @Test
    void stopClearsTheQueueButKeepsTheConnection() {
        player.playTrack(track("a"));
        player.playTrack(track("b"));

        player.stop();

        assertNull(audioPlayer.getPlayingTrack());
        assertEquals(0, player.getTrackScheduler().getQueueSize());
        verify(audioManager, never()).closeAudioConnection();
        verify(audioService, never()).deregisterSendHandler(any(), any());
    }

    @Test
    void leavingReleasesEverythingAndForgetsTheState() {
        player.playTrack(track("a"));

        player.stopAndLeave();

        verify(audioManager).closeAudioConnection();
        verify(audioService).deregisterSendHandler(guild, plugin);
        assertNull(savedState(), "no stale state to restore after an explicit leave");
    }

    @Test
    void aStateIsRestoredIntoTheQueueAndResumedAtItsPosition() {
        PlaybackState state = new PlaybackState();
        state.setVoiceChannelId(VOICE_ID);
        state.setVolume(42);
        state.setLoopMode(true);
        state.setShuffleMode(true);
        state.setCurrentPosition(30_000);
        state.setCurrentTrack(encode("current"));
        state.setQueue(List.of(encode("next"), encode("later")));

        player.restoreFromState(state);

        verify(audioManager).openAudioConnection(any());
        verify(audioService).registerSendHandler(same(guild), same(plugin), any(), eq(42), eq(50));
        assertEquals(42, player.getVolume());
        assertTrue(player.getTrackScheduler().isLoopMode());
        assertTrue(player.getTrackScheduler().isShuffleMode());
        assertNotNull(audioPlayer.getPlayingTrack());
        assertEquals("current", audioPlayer.getPlayingTrack().getInfo().title);
        assertEquals(30_000, audioPlayer.getPlayingTrack().getPosition());
        assertEquals(2, player.getTrackScheduler().getQueueSize());
    }

    @Test
    void aStateWithoutACurrentTrackStartsTheQueue() {
        PlaybackState state = new PlaybackState();
        state.setVoiceChannelId(VOICE_ID);
        state.setQueue(List.of(encode("only")));

        player.restoreFromState(state);

        assertNotNull(audioPlayer.getPlayingTrack());
        assertEquals("only", audioPlayer.getPlayingTrack().getInfo().title);
    }

    @Test
    void aVanishedVoiceChannelAbortsTheRestoreAndClearsTheState() {
        storage.set(fr.farmvivi.fluxcord.api.storage.StorageKey.guild(GUILD_ID, "music-plugin." + MusicPlayer.STATE_KEY),
                Map.of("stale", true));
        PlaybackState state = new PlaybackState();
        state.setVoiceChannelId("gone");
        state.setCurrentTrack(encode("a"));

        player.restoreFromState(state);

        verify(audioManager, never()).openAudioConnection(any());
        assertNull(audioPlayer.getPlayingTrack());
        assertNull(savedState());
    }

    @Test
    void anUndecodableQueueEntryIsSkippedRatherThanLosingTheRest() {
        PlaybackState state = new PlaybackState();
        state.setVoiceChannelId(VOICE_ID);
        state.setQueue(List.of("!!! not a track !!!", encode("good")));

        player.restoreFromState(state);

        assertEquals("good", audioPlayer.getPlayingTrack().getInfo().title);
    }

    @Test
    void theAutoLeaveTimerFiresOnceTheQueueRanDry() throws Exception {
        when(plugin.getAutoLeaveTimeoutMs()).thenReturn(30L);
        player.playTrack(track("a"));

        player.stop(); // schedules the quit task

        verify(audioManager, timeout(2000)).closeAudioConnection();
        verify(audioService, timeout(2000)).deregisterSendHandler(guild, plugin);
    }

    @Test
    void aTransientDisconnectDoesNotTearThePlayerDown() {
        when(plugin.getAutoLeaveTimeoutMs()).thenReturn(60_000L);
        player.playTrack(track("a"));
        when(audioManager.isConnected()).thenReturn(true);

        player.scheduleDisconnectCheck();
        player.cancelPendingDisconnect();

        verify(audioService, after(200).never()).deregisterSendHandler(any(), any());
    }

    @Test
    void destroyReleasesEverythingAndReleaseKeepsTheState() {
        player.playTrack(track("a"));
        player.release();

        verify(audioPlayer).destroy();
        assertNotNull(savedState(), "release() is the graceful shutdown path: the state must survive");

        MusicPlayer other = new MusicPlayer(plugin, guild, audioPlayer);
        other.playTrack(track("b"));
        other.destroy();

        assertNull(savedState(), "destroy() forgets the guild");
    }

    private String encode(String title) {
        return fr.farmvivi.fluxcord.plugins.music.state.TrackCodec.encode(playerManager, track(title));
    }
}
