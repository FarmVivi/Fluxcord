package fr.farmvivi.fluxcord.examples.audio;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * When the example joins a voice channel and when it leaves it — the part a plugin author reads to
 * understand how handlers are registered on {@code AudioService} and released afterwards.
 */
class AudioExamplePluginTest {

    private static final String GUILD_ID = "g1";

    @TempDir Path dataFolder;

    private AudioExamplePlugin plugin;
    private AudioService audioService;
    private Configuration configuration;
    private Guild guild;
    private AudioManager audioManager;

    @BeforeEach
    void setUp() {
        audioService = mock(AudioService.class);
        PluginContext context = mock(PluginContext.class);

        configuration = mock(Configuration.class);
        when(configuration.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(configuration.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(configuration.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));

        when(context.getPluginId()).thenReturn("plugin-example-audio");
        when(context.getPluginName()).thenReturn("Audio Example");
        when(context.getPluginVersion()).thenReturn("3.0.0-TEST");
        when(context.getLogger()).thenReturn(LoggerFactory.getLogger("audio-example-test"));
        when(context.getEventManager()).thenReturn(mock(EventManager.class));
        when(context.getDiscordAPI()).thenReturn(mock(DiscordAPI.class));
        when(context.getConfiguration()).thenReturn(configuration);
        when(context.getDataFolder()).thenReturn(dataFolder.toString());
        when(context.getAudioService()).thenReturn(audioService);
        when(context.getLanguage()).thenReturn(mock(PluginLanguageAdapter.class));

        audioManager = mock(AudioManager.class);
        guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        when(guild.getName()).thenReturn("guild");
        when(guild.getAudioManager()).thenReturn(audioManager);

        plugin = new AudioExamplePlugin();
        plugin.onLoad(context);
    }

    @AfterEach
    void tearDown() {
        // Closes the open recordings: Windows refuses to delete the @TempDir while a file is open.
        plugin.onDisable();
    }

    private GuildVoiceUpdateEvent join() {
        GuildVoiceUpdateEvent event = mock(GuildVoiceUpdateEvent.class);
        AudioChannelUnion channel = mock(AudioChannelUnion.class);
        when(channel.getName()).thenReturn("General");
        when(event.getGuild()).thenReturn(guild);
        when(event.getChannelJoined()).thenReturn(channel);
        return event;
    }

    private GuildVoiceUpdateEvent leave(int remainingMembers) {
        GuildVoiceUpdateEvent event = mock(GuildVoiceUpdateEvent.class);
        AudioChannelUnion channel = mock(AudioChannelUnion.class);
        when(channel.getMembers()).thenReturn(java.util.Collections.nCopies(remainingMembers, mock(Member.class)));
        when(event.getGuild()).thenReturn(guild);
        when(event.getChannelLeft()).thenReturn(channel);
        return event;
    }

    @Test
    void enablingCreatesTheSampleAndRecordingFolders() {
        plugin.onEnable();

        assertTrue(Files.isDirectory(dataFolder.resolve("samples")), "samples/");
        assertTrue(Files.isDirectory(dataFolder.resolve("recordings")), "recordings/");
    }

    @Test
    void someoneJoiningStartsPlaybackAndRecording() {
        plugin.onEnable();

        plugin.onSomeoneJoined(join());

        verify(audioManager).openAudioConnection(any());
        verify(audioService).registerSendHandler(same(guild), same(plugin), any(AudioSendHandler.class), eq(50), eq(60));
        verify(audioService).registerReceiveHandler(same(guild), same(plugin), any(AudioReceiveHandler.class));
        assertTrue(Files.exists(dataFolder.resolve("recordings").resolve("recording_" + GUILD_ID + ".wav")));
    }

    @Test
    void theVolumeAndPriorityComeFromTheConfiguration() {
        when(configuration.getInt("audio.default_volume", 50)).thenReturn(80);
        plugin.onEnable();

        plugin.onSomeoneJoined(join());

        verify(audioService).registerSendHandler(same(guild), same(plugin), any(), eq(80), eq(60));
    }

    @Test
    void autoJoinCanBeTurnedOff() {
        when(configuration.getBoolean("voice.auto_join", true)).thenReturn(false);
        plugin.onEnable();

        plugin.onSomeoneJoined(join());

        verifyNoInteractions(audioService);
        verify(audioManager, never()).openAudioConnection(any());
    }

    @Test
    void aSecondArrivalDoesNotRestartEverything() {
        plugin.onEnable();

        plugin.onSomeoneJoined(join());
        plugin.onSomeoneJoined(join());

        verify(audioService, times(1)).registerSendHandler(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void theBotStaysWhileSomeoneIsStillInTheChannel() {
        plugin.onEnable();
        plugin.onSomeoneJoined(join());

        plugin.onSomeoneLeft(leave(2)); // the bot plus one human

        verify(audioService, never()).deregisterSendHandler(any(), any());
        verify(audioManager, never()).closeAudioConnection();
    }

    @Test
    void theBotLeavesAndClosesTheRecordingOnceAloneInTheChannel() {
        plugin.onEnable();
        plugin.onSomeoneJoined(join());
        Path recording = dataFolder.resolve("recordings").resolve("recording_" + GUILD_ID + ".wav");

        plugin.onSomeoneLeft(leave(1)); // only the bot left

        verify(audioService).deregisterSendHandler(guild, plugin);
        verify(audioService).deregisterReceiveHandler(guild, plugin);
        verify(audioManager).closeAudioConnection();
        assertEquals(WavRecordingReceiveHandler.HEADER_SIZE, recording.toFile().length(),
                "the header was finalised, the file is playable");
    }

    @Test
    void autoLeaveCanBeTurnedOff() {
        when(configuration.getBoolean("voice.auto_leave", true)).thenReturn(false);
        plugin.onEnable();
        plugin.onSomeoneJoined(join());

        plugin.onSomeoneLeft(leave(1));

        verify(audioManager, never()).closeAudioConnection();
    }

    @Test
    void disablingClosesEveryOpenRecording() {
        plugin.onEnable();
        plugin.onSomeoneJoined(join());

        plugin.onDisable();

        Path recording = dataFolder.resolve("recordings").resolve("recording_" + GUILD_ID + ".wav");
        assertEquals(WavRecordingReceiveHandler.HEADER_SIZE, recording.toFile().length());
        assertDoesNotThrow(() -> plugin.onDisable());
    }

    @Test
    void leavingAGuildItNeverJoinedIsHarmless() {
        plugin.onEnable();

        assertDoesNotThrow(() -> plugin.onSomeoneLeft(leave(1)));
        verify(audioService, never()).deregisterSendHandler(any(), any());
    }

    @Test
    void anEventThatIsNeitherAJoinNorALeaveIsIgnored() {
        plugin.onEnable();
        GuildVoiceUpdateEvent event = mock(GuildVoiceUpdateEvent.class);

        plugin.onSomeoneJoined(event);
        plugin.onSomeoneLeft(event);

        verifyNoInteractions(audioService);
    }

    @Test
    void theMixedFrameEventIsSampledInsteadOfLoggedEveryTwentyMilliseconds() {
        plugin.onEnable();
        fr.farmvivi.fluxcord.api.audio.events.AudioFrameMixedEvent event =
                mock(fr.farmvivi.fluxcord.api.audio.events.AudioFrameMixedEvent.class);
        when(event.getGuild()).thenReturn(guild);

        for (int i = 0; i < 1000; i++) {
            plugin.onAudioFrameMixed(event);
        }

        // Only the thousandth frame reads the event details for the log line.
        verify(event, times(1)).getActiveSourceCount();
    }

    @Test
    void theRecordingExtensionFollowsTheConfiguration() {
        when(configuration.getString("audio.recording_format", "wav")).thenReturn("pcm");
        plugin.onEnable();

        plugin.onSomeoneJoined(join());

        assertTrue(Files.exists(dataFolder.resolve("recordings").resolve("recording_" + GUILD_ID + ".pcm")));
    }
}
