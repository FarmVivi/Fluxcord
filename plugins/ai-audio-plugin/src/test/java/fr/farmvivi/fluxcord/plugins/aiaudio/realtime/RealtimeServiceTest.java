package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import com.google.gson.JsonParser;
import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import fr.farmvivi.fluxcord.plugins.aiaudio.AiSettings;
import fr.farmvivi.fluxcord.plugins.aiaudio.TextToSpeechService;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationMemory;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaStore;
import fr.farmvivi.fluxcord.plugins.aiaudio.testing.MemoryDataStorage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

/**
 * The wiring around a realtime conversation: opening one, handing it the audio pipeline, taking it away again.
 *
 * <p>The socket is replaced through the service's link seam, which is the whole reason that seam exists — what
 * can be checked without a hosted service is everything this class actually does, and it is all side effects on
 * other objects: a receive handler registered and deregistered, a session frame sent, a failure reported where
 * a human will see it.
 */
class RealtimeServiceTest {

    private static final String GUILD_ID = "g1";
    private static final long NOW = 2_000_000L;

    private AIAudioPlugin plugin;
    private AudioService audioService;
    private Guild guild;
    private AudioManager audioManager;
    private ConversationMemory memory;
    private MessageChannel output;

    /** Every frame the service's conversation sent, and whether the link was closed. */
    private final List<String> sent = new ArrayList<>();
    private boolean closed;
    private int opened;

    private RealtimeService service;
    /** The sink the service handed to the link factory, so a test can push events through it. */
    private Consumer<RealtimeProtocol.Event> sink;

    private RealtimeLink recordingLink() {
        opened++;
        return new RealtimeLink() {
            @Override
            public void send(String frame) {
                sent.add(frame);
            }

            @Override
            public void close() {
                closed = true;
            }

            @Override
            public boolean isOpen() {
                return !closed;
            }
        };
    }

    @BeforeEach
    void setUp() {
        audioService = mock(AudioService.class);
        DiscordAPI discordAPI = mock(DiscordAPI.class);
        JDA jda = mock(JDA.class, RETURNS_DEEP_STUBS);
        when(jda.getSelfUser().getId()).thenReturn("bot-1");
        when(discordAPI.getJDA()).thenReturn(jda);

        PluginContext context = mock(PluginContext.class);
        when(context.getAudioService()).thenReturn(audioService);
        when(context.getDiscordAPI()).thenReturn(discordAPI);

        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(anyString(), any())).thenReturn("the live conversation failed");

        plugin = mock(AIAudioPlugin.class);
        when(plugin.getLogger()).thenReturn(LoggerFactory.getLogger("realtime-service-test"));
        when(plugin.getContext()).thenReturn(context);
        when(plugin.getLanguage()).thenReturn(language);
        when(plugin.getTextToSpeech()).thenReturn(mock(TextToSpeechService.class));
        when(plugin.getSettings()).thenReturn(AiSettings.defaults().withRealtime(
                new AiSettings.RealtimeSettings(true, "wss://example.test/realtime", "sk-x", "marin")));

        MemoryDataStorage backend = new MemoryDataStorage();
        memory = new ConversationMemory(new PluginDataStorageAdapter("ai-audio-plugin", backend), 10, 10, 10);
        when(plugin.getPersonaStore()).thenReturn(new PersonaStore(
                new PluginDataStorageAdapter("ai-audio-plugin", backend),
                AiSettings.defaults().persona().persona()));

        AudioChannelUnion channel = mock(AudioChannelUnion.class);
        when(channel.getId()).thenReturn("c1");
        when(channel.getName()).thenReturn("General");
        when(channel.getMembers()).thenReturn(List.of());
        audioManager = mock(AudioManager.class);
        when(audioManager.getConnectedChannel()).thenReturn(channel);

        guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        when(guild.getName()).thenReturn("My Server");
        when(guild.getAudioManager()).thenReturn(audioManager);
        when(channel.getGuild()).thenReturn(guild);

        output = mock(MessageChannel.class, RETURNS_DEEP_STUBS);

        service = new RealtimeService(plugin, memory, List.of(), () -> NOW, (settings, events) -> {
            sink = events;
            return recordingLink();
        });
    }

    @Test
    void startingOpensTheConnectionAndPutsTheBotOnTheReceivingEnd() {
        assertTrue(service.start(guild, output));

        assertEquals(1, opened);
        assertTrue(service.isActive(guild));
        verify(audioService).registerReceiveHandler(same(guild), same(plugin), any(RealtimeReceiver.class));
    }

    @Test
    void theFirstFrameTellsTheServiceWhoTheBotIsAndWhichVoiceToUse() {
        service.start(guild, output);

        var frame = JsonParser.parseString(sent.get(0)).getAsJsonObject();
        assertEquals("session.update", frame.get("type").getAsString());
        var session = frame.getAsJsonObject("session");
        assertTrue(session.get("instructions").getAsString().contains("Fluxcord"),
                "the persona, built by the same code the turn-based path uses");
        assertEquals("marin",
                session.getAsJsonObject("audio").getAsJsonObject("output").get("voice").getAsString());
    }

    @Test
    void aSecondStartInTheSameGuildChangesNothing() {
        service.start(guild, output);

        assertFalse(service.start(guild, output));
        assertEquals(1, opened, "and no second connection was opened");
    }

    @Test
    void thereIsNothingToStartWhenTheBotIsNotInAVoiceChannel() {
        when(audioManager.getConnectedChannel()).thenReturn(null);

        assertFalse(service.start(guild, output));
        assertEquals(0, opened);
        assertFalse(service.isActive(guild));
    }

    @Test
    void stoppingReleasesTheHandlerAndTheConnection() {
        service.start(guild, output);

        assertTrue(service.stop(guild));

        assertTrue(closed);
        assertFalse(service.isActive(guild));
        verify(audioService).deregisterReceiveHandler(guild, plugin);
    }

    @Test
    void stoppingSomethingThatWasNeverStartedIsHarmless() {
        assertFalse(service.stop(guild));
        verify(audioService, never()).deregisterReceiveHandler(any(), any());
    }

    @Test
    void aFailureIsReportedWhereAHumanWillSeeIt() {
        // The alternative is a bot that simply stops answering, with the reason in a log nobody is reading.
        service.start(guild, output);

        lastSink().accept(new RealtimeProtocol.Event.Failure("quota gone"));

        verify(output).sendMessage("the live conversation failed");
    }

    @Test
    void aFailureWithNowhereToReportItDoesNotThrow() {
        service.start(guild, null);

        assertDoesNotThrow(() -> lastSink().accept(new RealtimeProtocol.Event.Failure("quota gone")));
    }

    @Test
    void shuttingDownClosesEverythingAndIsSafeTwice() {
        service.start(guild, output);

        service.shutdown();

        assertTrue(closed);
        assertFalse(service.isActive(guild));
        assertDoesNotThrow(service::shutdown);
    }

    @Test
    void theSpeakerReachesTheServiceUnderTheNameTheServerShowsForThem() {
        Member member = mock(Member.class);
        when(member.getEffectiveName()).thenReturn("Victor");
        when(guild.getMemberById("u1")).thenReturn(member);
        service.start(guild, output);
        sent.clear();

        registeredReceiver().handleUserAudio(packetFrom("u1"));

        assertEquals("\"Victor\" is speaking now.", spokenNameIn(sent.get(0)));
    }

    @Test
    void somebodyTheServerCannotNameKeepsTheirIdRatherThanBlockingTheAudioThread() {
        // Resolving a member over REST from the audio thread would blow the 20 ms frame budget.
        when(guild.getMemberById("u2")).thenReturn(null);
        service.start(guild, output);
        sent.clear();

        registeredReceiver().handleUserAudio(packetFrom("u2"));

        assertEquals("\"u2\" is speaking now.", spokenNameIn(sent.get(0)));
    }

    /** The text of the item that names the speaker, decoded - the raw frame has it JSON-escaped. */
    private static String spokenNameIn(String frame) {
        return JsonParser.parseString(frame).getAsJsonObject().getAsJsonObject("item")
                .getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    /** One 20 ms packet from a speaker, as JDA delivers it: big-endian, 48 kHz stereo. */
    private static UserAudio packetFrom(String userId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        UserAudio packet = mock(UserAudio.class);
        when(packet.getUser()).thenReturn(user);
        when(packet.getAudioData(1.0)).thenReturn(new byte[PcmAudio.DISCORD_FRAME_SIZE]);
        return packet;
    }

    private Consumer<RealtimeProtocol.Event> lastSink() {
        assertNotNull(sink, "the service opened a link and gave it somewhere to send events");
        return sink;
    }

    private RealtimeReceiver registeredReceiver() {
        var captor = org.mockito.ArgumentCaptor.forClass(RealtimeReceiver.class);
        verify(audioService).registerReceiveHandler(same(guild), same(plugin), captor.capture());
        return captor.getValue();
    }

    @Test
    void theDialectComesFromTheConfigurationAndNotFromTheUrl() {
        // A proxy in front of either service would make the URL say nothing about the protocol behind it.
        assertInstanceOf(OpenAiRealtime.class, RealtimeService.dialect(
                new AiSettings.RealtimeSettings(true, AiSettings.RealtimeApi.OPENAI,
                        "wss://example.test/realtime", "sk-x", "marin", "")));
        assertInstanceOf(GeminiRealtime.class, RealtimeService.dialect(
                new AiSettings.RealtimeSettings(true, AiSettings.RealtimeApi.GEMINI,
                        "wss://example.test/live", "AIza-x", "Puck", "gemini-live-2.5-flash-preview")));
    }

    @Test
    void settingsWrittenBeforeThereWasAChoiceStillMeanOpenAi() {
        AiSettings.RealtimeSettings settings =
                new AiSettings.RealtimeSettings(true, "wss://example.test/realtime", "sk-x", "marin");

        assertEquals(AiSettings.RealtimeApi.OPENAI, settings.api());
        assertInstanceOf(OpenAiRealtime.class, RealtimeService.dialect(settings));
    }

}
