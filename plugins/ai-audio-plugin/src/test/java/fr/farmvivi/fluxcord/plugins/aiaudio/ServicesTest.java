package fr.farmvivi.fluxcord.plugins.aiaudio;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.AiEndpoint;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.AiRequestException;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.SpeechToText;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.TextToSpeech;
import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationMemory;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import fr.farmvivi.fluxcord.plugins.aiaudio.testing.MemoryDataStorage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The two services, with fake providers and a clock under the test's control.
 *
 * <p>The transcription path is the one worth pinning: audio arrives on JDA's thread, silence is noticed by
 * a scheduler, the request runs on a worker, and only then is a name resolved and a message posted. These
 * tests walk that chain without real time and without a network.
 */
class ServicesTest {

    private static final String GUILD_ID = "g1";
    private static final String USER_ID = "u1";

    private AIAudioPlugin plugin;
    private AudioService audioService;
    private Guild guild;
    private AudioManager audioManager;
    private MemoryDataStorage backend;
    private ConversationMemory memory;
    private final AtomicLong now = new AtomicLong(10_000);

    private TextToSpeechService tts;
    private SpeechRecognitionService stt;

    @BeforeEach
    void setUp() {
        audioService = mock(AudioService.class);
        DiscordAPI discordAPI = mock(DiscordAPI.class);
        when(discordAPI.getJDA()).thenReturn(mock(JDA.class));

        PluginContext context = mock(PluginContext.class);
        when(context.getAudioService()).thenReturn(audioService);
        when(context.getDiscordAPI()).thenReturn(discordAPI);

        plugin = mock(AIAudioPlugin.class);
        when(plugin.getLogger()).thenReturn(LoggerFactory.getLogger("ai-audio-services-test"));
        when(plugin.getContext()).thenReturn(context);
        when(plugin.getSettings()).thenReturn(settings());

        audioManager = mock(AudioManager.class);
        AudioChannelUnion channel = mock(AudioChannelUnion.class);
        when(channel.getId()).thenReturn("c1");
        when(channel.getName()).thenReturn("General");
        when(audioManager.getConnectedChannel()).thenReturn(channel);

        guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        when(guild.getName()).thenReturn("My Server");
        when(guild.getAudioManager()).thenReturn(audioManager);

        backend = new MemoryDataStorage();
        memory = new ConversationMemory(new PluginDataStorageAdapter("ai-audio-plugin", backend), 10, 10, 10);
    }

    @AfterEach
    void tearDown() {
        if (tts != null) {
            tts.shutdown();
        }
        if (stt != null) {
            stt.shutdown();
        }
    }

    private AiSettings settings() {
        AiEndpoint local = new AiEndpoint("http://localhost:8000/v1", "", "m", Duration.ofSeconds(5));
        return new AiSettings(local, AiSettings.SpeechApi.OPENAI, "fr-FR", local, "alloy", 60, 85, 1000,
                Duration.ofSeconds(1), Duration.ofSeconds(20), Duration.ofMillis(400), 10, 10, 10,
                AiSettings.PersonaSettings.defaults(), AiSettings.ChatSettings.defaults());
    }

    /** One second of 24 kHz mono, as a TTS provider would answer. */
    private static PcmAudio synthesised() {
        return new PcmAudio(new byte[24_000 * 2], 24_000, 1);
    }

    // Text to speech

    @Test
    void speakingRegistersTheSendHandlerWithTheConfiguredVolumeAndPriority() throws Exception {
        tts = new TextToSpeechService(plugin, (text, voice) -> synthesised());

        tts.speak(guild, "bonjour", null).get(5, TimeUnit.SECONDS);

        ArgumentCaptor<AudioSendHandler> handler = ArgumentCaptor.forClass(AudioSendHandler.class);
        verify(audioService).registerSendHandler(same(guild), same(plugin), handler.capture(), eq(60), eq(85));
        assertFalse(handler.getValue().isOpus(), "PCM, so the core can duck the music plugin");
        assertTrue(tts.isSpeaking(guild));
    }

    @Test
    void theHandlerIsRegisteredOnceAndReusedForEverySentence() throws Exception {
        // Deregistering between two sentences would make the core drop the voice connection.
        tts = new TextToSpeechService(plugin, (text, voice) -> synthesised());

        tts.speak(guild, "un", null).get(5, TimeUnit.SECONDS);
        tts.speak(guild, "deux", null).get(5, TimeUnit.SECONDS);

        verify(audioService, times(1)).registerSendHandler(any(), any(), any(), anyInt(), anyInt());
        verify(audioService, never()).deregisterSendHandler(any(), any());
    }

    @Test
    void theConfiguredVoiceIsUsedUnlessTheCallerNamesAnother() throws Exception {
        List<String> voices = new java.util.ArrayList<>();
        tts = new TextToSpeechService(plugin, (text, voice) -> {
            voices.add(voice);
            return synthesised();
        });

        tts.speak(guild, "un", null).get(5, TimeUnit.SECONDS);
        tts.speak(guild, "deux", "  nova  ").get(5, TimeUnit.SECONDS);
        tts.speak(guild, "trois", "   ").get(5, TimeUnit.SECONDS);

        assertEquals(List.of("alloy", "nova", "alloy"), voices, "blank means 'the configured one'");
    }

    @Test
    void aProviderFailureFailsTheFutureAndRegistersNothing() {
        tts = new TextToSpeechService(plugin, (text, voice) -> {
            throw new AiRequestException("model not found");
        });

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> tts.speak(guild, "bonjour", null).get(5, TimeUnit.SECONDS));

        assertInstanceOf(AiRequestException.class, failure.getCause());
        verifyNoInteractions(audioService);
        assertFalse(tts.isSpeaking(guild));
    }

    @Test
    void stoppingReleasesTheHandlerAndReportsWhetherThereWasOne() throws Exception {
        tts = new TextToSpeechService(plugin, (text, voice) -> synthesised());
        tts.speak(guild, "bonjour", null).get(5, TimeUnit.SECONDS);

        assertTrue(tts.stop(guild));

        verify(audioService).deregisterSendHandler(guild, plugin);
        assertFalse(tts.isSpeaking(guild));
        assertFalse(tts.stop(guild), "nothing left to stop");
    }

    @Test
    void shuttingDownTwiceIsHarmless() {
        tts = new TextToSpeechService(plugin, (text, voice) -> synthesised());

        tts.shutdown();

        assertDoesNotThrow(tts::shutdown);
    }

    // Speech to text

    /** A receive handler registered on the audio service, as the core would install it. */
    private AudioReceiveHandler startTranscribing(SpeechToText provider, MessageChannel output) {
        stt = new SpeechRecognitionService(plugin, provider, memory, now::get);
        assertTrue(stt.start(guild, output));

        ArgumentCaptor<AudioReceiveHandler> handler = ArgumentCaptor.forClass(AudioReceiveHandler.class);
        verify(audioService).registerReceiveHandler(same(guild), same(plugin), handler.capture());
        return handler.getValue();
    }

    /** Feeds {@code millis} of speech from {@code userId} into the handler, as JDA would. */
    private void speaks(AudioReceiveHandler handler, String userId, int millis) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        UserAudio audio = mock(UserAudio.class);
        when(audio.getUser()).thenReturn(user);
        when(audio.getAudioData(1.0)).thenReturn(new byte[48 * millis * 4]);
        handler.handleUserAudio(audio);
    }

    private MessageChannel recordingChannel(List<String> posted) {
        MessageChannel channel = mock(MessageChannel.class);
        when(channel.sendMessage(anyString())).thenAnswer(invocation -> {
            posted.add(invocation.getArgument(0));
            return mock(MessageCreateAction.class);
        });
        return channel;
    }

    @Test
    void theHandlerAsksForPerUserAudioBecauseAttributionDependsOnIt() {
        AudioReceiveHandler handler = startTranscribing((audio, language) -> "", mock(MessageChannel.class));

        assertTrue(handler.canReceiveUser());
        assertFalse(handler.canReceiveCombined(), "a mixed stream cannot be attributed to anyone");
    }

    @Test
    void aTranscribedSentenceIsPostedWithTheSpeakersServerNickname() throws Exception {
        Member member = mock(Member.class);
        when(member.getEffectiveName()).thenReturn("Victor");
        when(guild.getMemberById(USER_ID)).thenReturn(member);
        List<String> posted = new java.util.ArrayList<>();

        AudioReceiveHandler handler = startTranscribing((audio, language) -> "  bonjour tout le monde  ",
                recordingChannel(posted));
        speaks(handler, USER_ID, 600);
        now.addAndGet(2000); // the speaker falls silent

        waitUntil(() -> !posted.isEmpty());

        assertEquals(List.of("**Victor** bonjour tout le monde"), posted);
        List<Turn> remembered = memory.channelHistory(GUILD_ID, "c1", 10);
        assertEquals(1, remembered.size());
        Turn turn = remembered.get(0);
        assertEquals("Victor", turn.speaker());
        assertEquals(USER_ID, turn.userId(), "the id is the stable key, the name is for reading");
        assertEquals("My Server", turn.guildName());
        assertEquals("General", turn.channelName());
        assertEquals("bonjour tout le monde", turn.text());
        assertEquals(1, memory.personHistory(USER_ID, 10).size(), "and in that person's own history");
    }

    @Test
    void anUnknownMemberFallsBackToTheirIdRatherThanBlockingOnRest() throws Exception {
        when(guild.getMemberById(USER_ID)).thenReturn(null);
        List<String> posted = new java.util.ArrayList<>();

        AudioReceiveHandler handler = startTranscribing((audio, language) -> "salut", recordingChannel(posted));
        speaks(handler, USER_ID, 600);
        now.addAndGet(2000);

        waitUntil(() -> !posted.isEmpty());

        assertEquals(List.of("**u1** salut"), posted);
    }

    @Test
    void anEmptyTranscriptionIsNeitherPostedNorRemembered() throws Exception {
        List<String> posted = new java.util.ArrayList<>();

        AudioReceiveHandler handler = startTranscribing((audio, language) -> "   ", recordingChannel(posted));
        speaks(handler, USER_ID, 600);
        now.addAndGet(2000);

        Thread.sleep(300); // nothing to wait for; make sure nothing happens either
        assertTrue(posted.isEmpty());
        assertTrue(memory.channelHistory(GUILD_ID, "c1", 10).isEmpty());
    }

    @Test
    void aFailingProviderIsLoggedAndTheSessionKeepsRunning() throws Exception {
        // A scheduled task that throws is never run again, so a bad segment must not kill the poller.
        List<String> posted = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AudioReceiveHandler handler = startTranscribing((audio, language) -> {
            if (calls.incrementAndGet() == 1) {
                throw new AiRequestException("rate limited");
            }
            return "second try";
        }, recordingChannel(posted));

        speaks(handler, USER_ID, 600);
        now.addAndGet(2000);
        waitUntil(() -> calls.get() >= 1);

        speaks(handler, USER_ID, 600);
        now.addAndGet(2000);
        waitUntil(() -> !posted.isEmpty());

        assertEquals(List.of("**u1** second try"), posted);
    }

    @Test
    void startingTwiceIsRefusedAndRegistersOneHandlerOnly() {
        startTranscribing((audio, language) -> "", mock(MessageChannel.class));

        assertFalse(stt.start(guild, mock(MessageChannel.class)));

        verify(audioService, times(1)).registerReceiveHandler(any(), any(), any());
        assertTrue(stt.isActive(guild));
    }

    @Test
    void stoppingTranscribesWhatWasStillBufferedAndReleasesTheHandler() throws Exception {
        List<String> posted = new java.util.ArrayList<>();
        AudioReceiveHandler handler = startTranscribing((audio, language) -> "la derniere phrase",
                recordingChannel(posted));
        speaks(handler, USER_ID, 600); // never followed by silence

        assertTrue(stt.stop(guild));

        verify(audioService).deregisterReceiveHandler(guild, plugin);
        assertFalse(stt.isActive(guild));
        waitUntil(() -> !posted.isEmpty());
        assertEquals(List.of("**u1** la derniere phrase"), posted);
        assertFalse(stt.stop(guild), "nothing left to stop");
    }

    @Test
    void shuttingDownDropsWhatWasBufferedWithoutTranscribingIt() throws Exception {
        List<String> posted = new java.util.ArrayList<>();
        AudioReceiveHandler handler = startTranscribing((audio, language) -> "never sent", recordingChannel(posted));
        speaks(handler, USER_ID, 600);

        stt.shutdown();

        Thread.sleep(300);
        assertTrue(posted.isEmpty(), "a shutdown is not a stop: nothing is flushed");
        assertDoesNotThrow(stt::shutdown);
    }

    /** Waits for an asynchronous step, since transcription happens on a worker thread. */
    private void waitUntil(java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(done.getAsBoolean(), "the asynchronous step never happened");
    }
}
