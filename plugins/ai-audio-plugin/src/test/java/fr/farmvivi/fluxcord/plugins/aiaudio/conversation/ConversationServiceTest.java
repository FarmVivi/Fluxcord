package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import fr.farmvivi.fluxcord.plugins.aiaudio.AiSettings;
import fr.farmvivi.fluxcord.plugins.aiaudio.TextToSpeechService;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.AiEndpoint;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.AiRequestException;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;
import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationMemory;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.Persona;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaStore;
import fr.farmvivi.fluxcord.plugins.aiaudio.testing.MemoryDataStorage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The turn that makes the bot answer: a transcribed sentence in, spoken words out.
 *
 * <p>Most of these tests are about the guards, because that is where the behaviour lives — answering everything
 * said in a busy channel is the failure mode, not the feature. The model is a lambda and the clock is injected,
 * so nothing here waits or reaches the network.
 */
class ConversationServiceTest {

    private static final String GUILD_ID = "g1";
    private static final String CHANNEL_ID = "c1";
    private static final String BOT_ID = "bot-1";
    private static final long NOW = 1_000_000L;

    private AIAudioPlugin plugin;
    private TextToSpeechService tts;
    private ConversationMemory memory;
    private PersonaStore personaStore;
    private Guild guild;
    private AudioManager audioManager;
    private ConversationService conversation;
    private final AtomicReference<List<ChatModel.Message>> asked = new AtomicReference<>();
    /** What the model was offered on each call, so a withheld tool list can be asserted. */
    private final List<List<ChatModel.Tool>> offered = new CopyOnWriteArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        PluginDataStorageAdapter storage =
                new PluginDataStorageAdapter("ai-audio-plugin", new MemoryDataStorage());
        memory = new ConversationMemory(storage, 20, 20, 20);
        personaStore = new PersonaStore(storage,
                new Persona("Fluxcord", List.of(), "neutre", Locale.FRANCE, ""));

        tts = mock(TextToSpeechService.class);
        when(tts.speak(any(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(new PcmAudio(new byte[4], 48_000, 2)));

        SelfUser self = mock(SelfUser.class);
        when(self.getId()).thenReturn(BOT_ID);
        JDA jda = mock(JDA.class);
        when(jda.getSelfUser()).thenReturn(self);
        DiscordAPI discordAPI = mock(DiscordAPI.class);
        when(discordAPI.getJDA()).thenReturn(jda);
        PluginContext context = mock(PluginContext.class);
        when(context.getDiscordAPI()).thenReturn(discordAPI);

        plugin = mock(AIAudioPlugin.class);
        when(plugin.getLogger()).thenReturn(LoggerFactory.getLogger("conversation-test"));
        when(plugin.getContext()).thenReturn(context);
        when(plugin.getTextToSpeech()).thenReturn(tts);
        when(plugin.getPersonaStore()).thenReturn(personaStore);
        when(plugin.getSettings()).thenReturn(settings(true, ""));

        AudioChannelUnion channel = mock(AudioChannelUnion.class);
        when(channel.getId()).thenReturn(CHANNEL_ID);
        when(channel.getName()).thenReturn("General");
        when(channel.getMembers()).thenReturn(List.of());
        audioManager = mock(AudioManager.class);
        when(audioManager.getConnectedChannel()).thenReturn(channel);
        guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        when(guild.getName()).thenReturn("My Server");
        when(guild.getAudioManager()).thenReturn(audioManager);
        when(channel.getGuild()).thenReturn(guild);
    }

    @AfterEach
    void tearDown() {
        if (conversation != null) {
            conversation.shutdown();
        }
    }

    private AiSettings settings(boolean enabled, String wakeWord) {
        return settings(enabled, wakeWord, false, 3);
    }

    private AiSettings settings(boolean enabled, String wakeWord, boolean memoryTools, int maxToolRounds) {
        AiEndpoint local = new AiEndpoint("http://localhost:11434/v1", "", "m", Duration.ofSeconds(5));
        return new AiSettings(local, AiSettings.SpeechApi.OLLAMA, "fr-FR", local, "alloy", 100, 80, 1000,
                Duration.ofSeconds(1), Duration.ofSeconds(20), Duration.ofMillis(400), 20, 20, 20,
                AiSettings.PersonaSettings.defaults(),
                new AiSettings.ChatSettings(local, enabled, wakeWord, 8, 120, 0.7, "none", "low",
                        memoryTools, maxToolRounds));
    }

    /** A service whose model answers {@code reply} and records what it was asked. */
    private ConversationService serviceAnswering(String reply) {
        return serviceAnswering(List.of(ChatModel.Answer.spoken(reply)));
    }

    /**
     * A service whose model gives one scripted answer per call, and records what it was asked each time.
     *
     * <p>Several answers is how a tool round is expressed: the first asks for a lookup, the next one speaks.
     */
    private ConversationService serviceAnswering(List<ChatModel.Answer> answers) {
        conversation = new ConversationService(plugin, (messages, tools, maxTokens) -> {
            asked.set(messages);
            offered.add(tools);
            int call = calls.getAndIncrement();
            return answers.get(Math.min(call, answers.size() - 1));
        }, memory, () -> NOW);
        return conversation;
    }

    private Turn heard(String text) {
        return new Turn(NOW, "u1", "Victor", GUILD_ID, "My Server", CHANNEL_ID, "General", text);
    }

    /** Waits for the worker, which is where the model call and the speech happen. */
    private void waitForAnswer() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (asked.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    void nothingHappensUntilAConversationIsStarted() throws Exception {
        ConversationService service = serviceAnswering("bonjour");

        service.onTranscription(guild, heard("salut"));

        Thread.sleep(200);
        assertNull(asked.get());
        verifyNoInteractions(tts);
        assertFalse(service.isActive(guild));
    }

    @Test
    void startingAndStoppingReportWhetherAnythingChanged() {
        ConversationService service = serviceAnswering("ok");

        assertTrue(service.start(guild));
        assertTrue(service.isActive(guild));
        assertFalse(service.start(guild), "already answering");
        assertTrue(service.stop(guild));
        assertFalse(service.stop(guild), "was not answering");
    }

    @Test
    void anAnsweredSentenceIsSpokenAndRemembered() throws Exception {
        ConversationService service = serviceAnswering("il est six heures");
        service.start(guild);

        service.onTranscription(guild, heard("quelle heure il est ?"));
        waitForAnswer();

        verify(tts, timeout(5_000)).speak(same(guild), eq("il est six heures"), isNull());
        List<Turn> remembered = memory.channelHistory(GUILD_ID, CHANNEL_ID, 10);
        assertEquals(1, remembered.size(), "the answer is remembered; the question was remembered upstream");
        assertEquals(BOT_ID, remembered.get(0).userId(), "attributed to the bot, so the next prompt knows");
        assertEquals("Fluxcord", remembered.get(0).speaker());
        assertEquals("il est six heures", remembered.get(0).text());
    }

    @Test
    void theModelIsGivenTheQuestionAndThePersona() throws Exception {
        ConversationService service = serviceAnswering("ok");
        service.start(guild);

        service.onTranscription(guild, heard("dis quelque chose"));
        waitForAnswer();

        List<ChatModel.Message> messages = asked.get();
        assertNotNull(messages);
        assertEquals(ChatModel.Role.SYSTEM, messages.get(0).role());
        assertTrue(messages.get(0).content().contains("Fluxcord"));
        assertTrue(messages.get(messages.size() - 1).content().contains("dis quelque chose"));
    }

    @Test
    void aSilentModelIsNotSpokenAndNotRemembered() throws Exception {
        // The prompt tells the model to answer with an empty line when it has nothing to add.
        ConversationService service = serviceAnswering("   ");
        service.start(guild);

        service.onTranscription(guild, heard("bla bla"));
        waitForAnswer();

        Thread.sleep(200);
        verifyNoInteractions(tts);
        assertTrue(memory.channelHistory(GUILD_ID, CHANNEL_ID, 10).isEmpty());
    }

    @Test
    void withAWakeWordOnlyTheSentencesContainingItAreAnswered() throws Exception {
        // Answering everything said is the failure mode in a channel with more than two people.
        when(plugin.getSettings()).thenReturn(settings(true, "hé flux"));
        ConversationService service = serviceAnswering("oui ?");
        service.start(guild);

        service.onTranscription(guild, heard("on mange quoi ce soir"));
        Thread.sleep(200);
        assertNull(asked.get(), "not addressed to us");

        service.onTranscription(guild, heard("Hé Flux, on mange quoi ?"));
        waitForAnswer();

        assertNotNull(asked.get(), "the wake word is matched whatever the case");
        verify(tts, timeout(5_000)).speak(any(), eq("oui ?"), isNull());
    }

    @Test
    void answeringCanBeSwitchedOffInTheConfigurationEvenWhenStarted() throws Exception {
        when(plugin.getSettings()).thenReturn(settings(false, ""));
        ConversationService service = serviceAnswering("bonjour");
        service.start(guild);

        service.onTranscription(guild, heard("salut"));

        Thread.sleep(200);
        assertNull(asked.get());
    }

    @Test
    void anEmptySentenceIsNeverSentToTheModel() throws Exception {
        ConversationService service = serviceAnswering("ok");
        service.start(guild);

        service.onTranscription(guild, heard("   "));
        service.onTranscription(guild, heard(null));

        Thread.sleep(200);
        assertNull(asked.get());
    }

    @Test
    void nothingIsAskedWhenTheBotIsNoLongerInAChannel() throws Exception {
        // It could have been moved or disconnected between the sentence and its answer.
        when(audioManager.getConnectedChannel()).thenReturn(null);
        ConversationService service = serviceAnswering("ok");
        service.start(guild);

        service.onTranscription(guild, heard("salut"));

        Thread.sleep(200);
        assertNull(asked.get());
    }

    @Test
    void aModelFailureIsLoggedAndTheConversationKeepsGoing() throws Exception {
        // The worker must survive a provider error, or one rate limit ends the conversation.
        AtomicInteger attempts = new AtomicInteger();
        conversation = new ConversationService(plugin, (messages, tools, maxTokens) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new AiRequestException("rate limited");
            }
            asked.set(messages);
            return ChatModel.Answer.spoken("deuxième essai");
        }, memory, () -> NOW);
        conversation.start(guild);

        conversation.onTranscription(guild, heard("premier"));
        Thread.sleep(200);
        conversation.onTranscription(guild, heard("second"));
        waitForAnswer();

        verify(tts, timeout(5_000)).speak(any(), eq("deuxième essai"), isNull());
    }

    @Test
    void aSpeechFailureDoesNotLoseTheAnswerFromTheMemory() throws Exception {
        // Remembered before spoken, so the next turn still sees what the bot said it would say.
        when(tts.speak(any(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new AiRequestException("no voice")));
        ConversationService service = serviceAnswering("une réponse");
        service.start(guild);

        service.onTranscription(guild, heard("salut"));
        waitForAnswer();

        Thread.sleep(200);
        assertEquals(1, memory.channelHistory(GUILD_ID, CHANNEL_ID, 10).size());
    }

    @Test
    void theMoodOfTheChannelReachesTheModel() throws Exception {
        personaStore.nudgeMood(GUILD_ID, CHANNEL_ID, 0.9, 0.3, NOW);
        ConversationService service = serviceAnswering("ok");
        service.start(guild);

        service.onTranscription(guild, heard("salut"));
        waitForAnswer();

        assertTrue(asked.get().get(0).content().contains("enthusiastic"), asked.get().get(0).content());
    }

    @Test
    void whoIsPresentReachesTheModelAsContext() throws Exception {
        Member member = mock(Member.class);
        User user = mock(User.class);
        when(user.isBot()).thenReturn(false);
        when(member.getUser()).thenReturn(user);
        when(member.getId()).thenReturn("u2");
        when(member.getEffectiveName()).thenReturn("Alice");
        when(audioManager.getConnectedChannel().getMembers()).thenReturn(List.of(member));
        ConversationService service = serviceAnswering("ok");
        service.start(guild);

        service.onTranscription(guild, heard("salut"));
        waitForAnswer();

        assertTrue(asked.get().get(1).content().contains("Alice"), asked.get().get(1).content());
    }

    @Test
    void shuttingDownStopsAnsweringEverywhereAndIsSafeTwice() {
        ConversationService service = serviceAnswering("ok");
        service.start(guild);

        service.shutdown();

        assertFalse(service.isActive(guild));
        assertDoesNotThrow(service::shutdown);
    }

    // Memory as tool calls

    private static ChatModel.Answer asksFor(String tool, String arguments) {
        return new ChatModel.Answer("", List.of(new ChatModel.ToolCall("call_1", tool, arguments)));
    }

    /** Waits for the model to have been called {@code n} times. */
    private void waitForCalls(int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (calls.get() < n && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    void noToolIsOfferedUnlessTheConfigurationAsksForIt() throws Exception {
        // A model that does not support them would refuse the request outright.
        ConversationService service = serviceAnswering("ok");
        service.start(guild);

        service.onTranscription(guild, heard("salut"));
        waitForAnswer();

        assertEquals(List.of(), offered.get(0));
    }

    @Test
    void aLookupIsRunAndItsResultGoesBackBeforeTheAnswer() throws Exception {
        when(plugin.getSettings()).thenReturn(settings(true, "", true, 3));
        memory.remember(new Turn(NOW - 60_000, "u1", "Victor", GUILD_ID, "My Server", CHANNEL_ID, "General",
                "on parlait de rhubarbe"));
        ConversationService service = serviceAnswering(List.of(
                asksFor(MemoryTools.RECALL_CHANNEL, "{\"limit\":5}"),
                ChatModel.Answer.spoken("de la rhubarbe, oui")));
        service.start(guild);

        service.onTranscription(guild, heard("on parlait de quoi ?"));
        waitForCalls(2);

        verify(tts, timeout(5_000)).speak(any(), eq("de la rhubarbe, oui"), isNull());
        List<ChatModel.Message> second = asked.get();
        ChatModel.Message replayed = second.get(second.size() - 2);
        assertEquals(ChatModel.Role.ASSISTANT, replayed.role());
        assertEquals("call_1", replayed.toolCalls().get(0).id());
        ChatModel.Message result = second.get(second.size() - 1);
        assertEquals(ChatModel.Role.TOOL, result.role());
        assertEquals("call_1", result.toolCallId());
        assertTrue(result.content().contains("rhubarbe"), result.content());
        assertFalse(offered.get(0).isEmpty(), "the tools were offered on the first round");
    }

    @Test
    void aModelThatKeepsAskingIsMadeToAnswerWithWhatItHas() throws Exception {
        // Otherwise one stubborn model holds the voice channel silent for as long as it likes.
        when(plugin.getSettings()).thenReturn(settings(true, "", true, 2));
        ConversationService service = serviceAnswering(List.of(
                asksFor(MemoryTools.RECALL_CHANNEL, "{}"),
                asksFor(MemoryTools.RECALL_SERVER, "{}"),
                ChatModel.Answer.spoken("bon, je ne sais pas")));
        service.start(guild);

        service.onTranscription(guild, heard("alors ?"));
        waitForCalls(3);

        verify(tts, timeout(5_000)).speak(any(), eq("bon, je ne sais pas"), isNull());
        assertEquals(3, calls.get(), "two rounds of tools, then the answer");
        assertFalse(offered.get(1).isEmpty());
        assertEquals(List.of(), offered.get(2), "on the last round the tools are withheld");
    }

    @Test
    void anUnknownToolIsAnsweredRatherThanEndingTheTurn() throws Exception {
        when(plugin.getSettings()).thenReturn(settings(true, "", true, 3));
        ConversationService service = serviceAnswering(List.of(
                asksFor("search_the_web", "{}"),
                ChatModel.Answer.spoken("je ne peux pas chercher")));
        service.start(guild);

        service.onTranscription(guild, heard("cherche ça"));
        waitForCalls(2);

        verify(tts, timeout(5_000)).speak(any(), eq("je ne peux pas chercher"), isNull());
        assertTrue(asked.get().get(asked.get().size() - 1).content().contains("no tool"));
    }
}
