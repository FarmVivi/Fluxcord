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
        // Faithful to the API: Guild.getSelfMember() is @Nonnull and JDA always has it cached, so a
        // double that answers null would be testing a state that cannot happen.
        net.dv8tion.jda.api.entities.SelfMember selfMember =
                mock(net.dv8tion.jda.api.entities.SelfMember.class);
        when(selfMember.getEffectiveName()).thenReturn("Fluxcord");
        when(guild.getSelfMember()).thenReturn(selfMember);

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
        return AiSettings.defaults()
                .withTranscription(new AiSettings.TranscriptionSettings(local,
                        AiSettings.SpeechApi.OPENAI, "fr-FR",
                        Duration.ofSeconds(1), Duration.ofSeconds(20), Duration.ofMillis(400)))
                .withSpeech(new AiSettings.SpeechSettings(local, "alloy", 60, 85, 1000))
                .withMemory(new AiSettings.MemorySettings(10, 10, 10));
    }

    /** One second of 24 kHz mono, as a TTS provider would answer. */
    private static PcmAudio synthesised() {
        return new PcmAudio(new byte[24_000 * 2], 24_000, 1);
    }

    // Text to speech

    @Test
    void audioThatIsAlreadySpokenIsQueuedWithoutSynthesisingAnything() {
        // The speech-to-speech path: the chat model answered with a voice, so there is nothing to synthesise -
        // but playback is identical, and must share the handler rather than open a second one.
        tts = new TextToSpeechService(plugin, (text, voice) -> {
            throw new AssertionError("nothing should have been synthesised");
        });

        PcmAudio queued = tts.play(guild, synthesised());

        assertEquals(PcmAudio.DISCORD_SAMPLE_RATE, queued.sampleRate(), "converted for Discord");
        assertEquals(PcmAudio.DISCORD_CHANNELS, queued.channels());
        verify(audioService).registerSendHandler(same(guild), same(plugin), any(), anyInt(), anyInt());
        assertTrue(tts.isSpeaking(guild));
    }

    @Test
    void interruptingDropsWhatIsQueuedButKeepsTheVoice() {
        // Different from stop(): that one deregisters, and the core then closes the voice connection once the
        // guild's pipeline empties - so the bot could not speak again a moment later.
        tts = new TextToSpeechService(plugin, (text, voice) -> synthesised());
        tts.play(guild, synthesised());

        assertTrue(tts.interrupt(guild), "something was playing");

        assertFalse(tts.isSpeaking(guild));
        verify(audioService, never()).deregisterSendHandler(any(), any());
        assertFalse(tts.interrupt(guild), "nothing left to drop");
    }

    @Test
    void interruptingAGuildTheBotNeverSpokeInIsHarmless() {
        tts = new TextToSpeechService(plugin, (text, voice) -> synthesised());

        assertFalse(tts.interrupt(guild));
        verify(audioService, never()).registerSendHandler(any(), any(), any(), anyInt(), anyInt());
    }


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

    /**
     * Feeds {@code millis} of speech from {@code userId} into the handler, as JDA would.
     *
     * <p>Audible speech, and not the buffer of zeros this used to pass. Since 2026-11-07 a segment quieter
     * than {@code transcription.min_loudness_dbfs} is dropped before the request, because a sub-second clip
     * of microphone noise was transcribed as the vocabulary hint itself and woke a paid session - so a
     * helper called {@code speaks} that fed digital silence no longer tested transcription at all. It was
     * worth four failures to find out that this helper had been lying about its name.
     */
    private void speaks(AudioReceiveHandler handler, String userId, int millis) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(userId);
        UserAudio audio = mock(UserAudio.class);
        when(audio.getUser()).thenReturn(user);
        when(audio.getAudioData(1.0)).thenReturn(tone(48 * millis * 4));
        handler.handleUserAudio(audio);
    }

    /** As loud as somebody talking: a sine at a fifth of full scale, 48 kHz stereo as Discord delivers it. */
    private static byte[] tone(int bytes) {
        byte[] samples = new byte[bytes - bytes % 2];
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(samples)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; buffer.remaining() >= 2; i++) {
            buffer.putShort((short) Math.round(Math.sin(2 * Math.PI * 220 * i / 48_000.0) * 0.2 * 32767));
        }
        return samples;
    }

    @Test
    void silenceIsNotTranscribedHoweverLongItLasts() throws Exception {
        // The defect this pins, MEASURED twice on 2026-11-07: 0.52 s and 0.58 s of microphone noise came
        // back from the local transcriber as exactly "Fluxcord Chris" - the vocabulary list of the request,
        // word for word. The words are the bot's own name, so the wake gate opened a paid session and the
        // bot answered a room where nobody had spoken for 92 seconds. min_segment_ms could not catch it:
        // length is the wrong axis, amplitude is the right one.
        List<String> posted = new java.util.ArrayList<>();
        List<PcmAudio> asked = new java.util.ArrayList<>();
        AudioReceiveHandler handler = startTranscribing((audio, language, vocabulary) -> {
            asked.add(audio);
            return "Fluxcord";
        }, recordingChannel(posted));

        User user = mock(User.class);
        when(user.getId()).thenReturn(USER_ID);
        UserAudio audio = mock(UserAudio.class);
        when(audio.getUser()).thenReturn(user);
        when(audio.getAudioData(1.0)).thenReturn(new byte[48 * 600 * 4]);
        handler.handleUserAudio(audio);
        now.addAndGet(2000);

        // A negative assertion needs a bound rather than a deadline: the segmenter polls every 100 ms and
        // the drop costs no I/O, so a second is far more than the path needs and the test stays quick.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (asked.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertTrue(asked.isEmpty(),
                "silence must never reach the transcriber, so there is nothing to hallucinate from");
        assertTrue(posted.isEmpty(), "and nothing is posted or remembered");
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
        AudioReceiveHandler handler = startTranscribing((audio, language, vocabulary) -> "", mock(MessageChannel.class));

        assertTrue(handler.canReceiveUser());
        assertFalse(handler.canReceiveCombined(), "a mixed stream cannot be attributed to anyone");
    }

    @Test
    void aTranscribedSentenceIsPostedWithTheSpeakersServerNickname() throws Exception {
        Member member = mock(Member.class);
        when(member.getEffectiveName()).thenReturn("Victor");
        when(guild.getMemberById(USER_ID)).thenReturn(member);
        List<String> posted = new java.util.ArrayList<>();

        AudioReceiveHandler handler = startTranscribing((audio, language, vocabulary) -> "  bonjour tout le monde  ",
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

        AudioReceiveHandler handler = startTranscribing((audio, language, vocabulary) -> "salut", recordingChannel(posted));
        speaks(handler, USER_ID, 600);
        now.addAndGet(2000);

        waitUntil(() -> !posted.isEmpty());

        assertEquals(List.of("**u1** salut"), posted);
    }

    @Test
    void anEmptyTranscriptionIsNeitherPostedNorRemembered() throws Exception {
        List<String> posted = new java.util.ArrayList<>();

        AudioReceiveHandler handler = startTranscribing((audio, language, vocabulary) -> "   ", recordingChannel(posted));
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
        AudioReceiveHandler handler = startTranscribing((audio, language, vocabulary) -> {
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
        startTranscribing((audio, language, vocabulary) -> "", mock(MessageChannel.class));

        assertFalse(stt.start(guild, mock(MessageChannel.class)));

        verify(audioService, times(1)).registerReceiveHandler(any(), any(), any());
        assertTrue(stt.isActive(guild));
    }

    @Test
    void stoppingTranscribesWhatWasStillBufferedAndReleasesTheHandler() throws Exception {
        List<String> posted = new java.util.ArrayList<>();
        AudioReceiveHandler handler = startTranscribing((audio, language, vocabulary) -> "la derniere phrase",
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
        AudioReceiveHandler handler = startTranscribing((audio, language, vocabulary) -> "never sent", recordingChannel(posted));
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
