package fr.farmvivi.fluxcord.plugins.aiaudio.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import fr.farmvivi.fluxcord.plugins.aiaudio.AiSettings;
import fr.farmvivi.fluxcord.plugins.aiaudio.SpeechRecognitionService;
import fr.farmvivi.fluxcord.plugins.aiaudio.TextToSpeechService;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.AiEndpoint;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.AiRequestException;
import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationMemory;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The four commands, with the plugin and its services mocked.
 *
 * <p>What is asserted is what a user experiences: which refusal comes back and in which order the checks
 * happen — an empty text is refused before the bot joins a channel, a missing key before any request is
 * made. The language adapter echoes keys, so an assertion naming a key also proves that key is the one
 * looked up.
 */
class AiAudioCommandsTest {

    private static final String PLUGIN_ID = "ai-audio-plugin";
    private static final String USER_ID = "u1";

    private AIAudioPlugin plugin;
    private TextToSpeechService tts;
    private SpeechRecognitionService stt;
    private ConversationMemory memory;
    private PluginPermissionAdapter permissions;
    private CommandContext ctx;
    private Guild guild;
    private AudioManager audioManager;
    private GuildVoiceState voiceState;

    @BeforeEach
    void setUp() {
        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(any(Locale.class), anyString())).thenAnswer(i -> i.getArgument(1));
        when(language.getString(any(Locale.class), anyString(), any(Object[].class)))
                .thenAnswer(i -> i.getArgument(1));

        tts = mock(TextToSpeechService.class);
        stt = mock(SpeechRecognitionService.class);
        memory = mock(ConversationMemory.class);
        permissions = mock(PluginPermissionAdapter.class);

        plugin = mock(AIAudioPlugin.class);
        when(plugin.getLanguage()).thenReturn(language);
        when(plugin.getLogger()).thenReturn(LoggerFactory.getLogger("ai-audio-commands-test"));
        when(plugin.getTextToSpeech()).thenReturn(tts);
        when(plugin.getSpeechRecognition()).thenReturn(stt);
        when(plugin.getMemory()).thenReturn(memory);
        when(plugin.getPermissions()).thenReturn(permissions);
        when(plugin.getSettings()).thenReturn(localSettings());
        when(plugin.permissionKey(anyString())).thenAnswer(i -> PLUGIN_ID + "." + i.getArgument(0));

        audioManager = mock(AudioManager.class);
        guild = mock(Guild.class);
        when(guild.getId()).thenReturn("g1");
        when(guild.getName()).thenReturn("My Server");
        when(guild.getAudioManager()).thenReturn(audioManager);

        User user = mock(User.class);
        when(user.getId()).thenReturn(USER_ID);
        voiceState = mock(GuildVoiceState.class);
        Member member = mock(Member.class);
        when(member.getVoiceState()).thenReturn(voiceState);
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getMember()).thenReturn(member);

        ctx = mock(CommandContext.class);
        when(ctx.getGuild()).thenReturn(Optional.of(guild));
        when(ctx.getUser()).thenReturn(user);
        when(ctx.getLocale()).thenReturn(Locale.FRANCE);
        when(ctx.getOriginalEvent()).thenReturn(event);
        when(ctx.getChannel()).thenReturn(mock(MessageChannel.class));
    }

    /** Settings pointing at a self-hosted server, so no key is required. */
    private AiSettings localSettings() {
        AiEndpoint local = new AiEndpoint("http://localhost:8000/v1", "", "m", Duration.ofSeconds(5));
        return new AiSettings(local, "fr-FR", local, "alloy", 100, 80, 20,
                Duration.ofSeconds(1), Duration.ofSeconds(20), Duration.ofMillis(400), 10, 10, 10);
    }

    /** Same, but pointing at OpenAI without a key — the misconfiguration users hit first. */
    private AiSettings hostedWithoutKey() {
        AiEndpoint hosted = new AiEndpoint("https://api.openai.com/v1", "", "m", Duration.ofSeconds(5));
        return new AiSettings(hosted, "fr-FR", hosted, "alloy", 100, 80, 1000,
                Duration.ofSeconds(1), Duration.ofSeconds(20), Duration.ofMillis(400), 10, 10, 10);
    }

    private void inVoiceChannel(String name) {
        AudioChannelUnion channel = mock(AudioChannelUnion.class);
        when(channel.getName()).thenReturn(name);
        when(channel.getId()).thenReturn("c1");
        when(voiceState.getChannel()).thenReturn(channel);
    }

    private void botConnectedTo(String name) {
        AudioChannelUnion channel = mock(AudioChannelUnion.class);
        when(channel.getName()).thenReturn(name);
        when(channel.getId()).thenReturn("c1");
        when(audioManager.isConnected()).thenReturn(true);
        when(audioManager.getConnectedChannel()).thenReturn(channel);
    }

    // /speak

    @Test
    void speakingJoinsTheCallersChannelAndQueuesTheSpeech() {
        inVoiceChannel("General");
        when(tts.speak(same(guild), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(new PcmAudio(new byte[4], 48_000, 2)));

        new SpeakCommand(plugin).execute(ctx, "  bonjour  ", null);

        verify(audioManager).openAudioConnection(any());
        verify(ctx).deferReply();
        verify(tts).speak(same(guild), eq("bonjour"), isNull());
        verify(ctx).reply("messages.speaking");
    }

    @Test
    void speakingDoesNotRejoinAChannelTheBotIsAlreadyIn() {
        botConnectedTo("General");
        when(tts.speak(any(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(new PcmAudio(new byte[4], 48_000, 2)));

        new SpeakCommand(plugin).execute(ctx, "salut", "nova");

        verify(audioManager, never()).openAudioConnection(any());
        verify(tts).speak(same(guild), eq("salut"), eq("nova"));
    }

    @Test
    void aProviderFailureBecomesALocalisedErrorRatherThanAStackTrace() {
        botConnectedTo("General");
        when(tts.speak(any(), anyString(), any())).thenReturn(
                CompletableFuture.failedFuture(new AiRequestException("model not found")));

        new SpeakCommand(plugin).execute(ctx, "salut", null);

        verify(ctx).deferReply();
        verify(ctx).replyError("errors.synthesis_failed");
        verify(ctx, never()).reply(anyString());
    }

    @Test
    void anEmptyTextIsRefusedBeforeAnythingElseHappens() {
        new SpeakCommand(plugin).execute(ctx, "   ", null);

        verify(ctx).replyError("errors.nothing_to_say");
        verifyNoInteractions(tts);
        verify(audioManager, never()).openAudioConnection(any());
    }

    @Test
    void aTextOverTheLimitIsRefusedWithTheLimitInTheMessage() {
        new SpeakCommand(plugin).execute(ctx, "x".repeat(21), null); // the limit is 20 here

        verify(ctx).replyError("errors.text_too_long");
        verifyNoInteractions(tts);
    }

    @Test
    void aMissingKeyIsReportedBeforeAnyRequestIsAttempted() {
        when(plugin.getSettings()).thenReturn(hostedWithoutKey());

        new SpeakCommand(plugin).execute(ctx, "salut", null);

        verify(ctx).replyError("errors.api_key_missing");
        verifyNoInteractions(tts);
    }

    @Test
    void speakingWithoutAVoiceChannelSaysToJoinOne() {
        when(voiceState.getChannel()).thenReturn(null);

        new SpeakCommand(plugin).execute(ctx, "salut", null);

        verify(ctx).replyError("errors.no_voice_channel");
        verifyNoInteractions(tts);
    }

    @Test
    void everyCommandRefusesToRunOutsideAServer() {
        when(ctx.getGuild()).thenReturn(Optional.empty());

        new SpeakCommand(plugin).execute(ctx, "salut", null);
        new SilenceCommand(plugin).execute(ctx);
        new TranscribeCommand(plugin).execute(ctx, TranscribeCommand.START);
        new ForgetCommand(plugin).execute(ctx, ForgetCommand.CHANNEL);

        verify(ctx, times(4)).replyError("errors.guild_only");
        verifyNoInteractions(tts);
        verifyNoInteractions(stt);
    }

    // /silence

    @Test
    void silencingReportsWhetherTheBotWasActuallyTalking() {
        when(tts.stop(guild)).thenReturn(true);
        new SilenceCommand(plugin).execute(ctx);
        verify(ctx).replySuccess("messages.silenced");

        when(tts.stop(guild)).thenReturn(false);
        new SilenceCommand(plugin).execute(ctx);
        verify(ctx).replySuccess("messages.already_silent");
    }

    // /transcribe

    @Test
    void startingTranscriptionJoinsTheChannelAndPostsWhereItWasCalled() {
        inVoiceChannel("General");
        MessageChannel output = mock(MessageChannel.class);
        when(ctx.getChannel()).thenReturn(output);
        when(stt.start(same(guild), same(output))).thenReturn(true);

        new TranscribeCommand(plugin).execute(ctx, TranscribeCommand.START);

        verify(audioManager).openAudioConnection(any());
        verify(ctx).replySuccess("messages.transcription_started");
    }

    @Test
    void startingTwiceSaysSoInsteadOfOpeningASecondSession() {
        botConnectedTo("General");
        when(stt.start(any(), any())).thenReturn(false);

        new TranscribeCommand(plugin).execute(ctx, TranscribeCommand.START);

        verify(ctx).replyError("errors.already_transcribing");
    }

    @Test
    void stoppingTranscriptionNeedsNoVoiceChannelAtAll() {
        // Stopping has to work even if the bot was disconnected in the meantime.
        when(stt.stop(guild)).thenReturn(true);

        new TranscribeCommand(plugin).execute(ctx, TranscribeCommand.STOP);

        verify(ctx).replySuccess("messages.transcription_stopped");
        verify(audioManager, never()).openAudioConnection(any());
    }

    @Test
    void stoppingWhenNothingWasRunningSaysSo() {
        when(stt.stop(guild)).thenReturn(false);

        new TranscribeCommand(plugin).execute(ctx, TranscribeCommand.STOP);

        verify(ctx).replySuccess("errors.not_transcribing");
    }

    @Test
    void transcribingAlsoNeedsAKeyWhenTheEndpointIsHosted() {
        when(plugin.getSettings()).thenReturn(hostedWithoutKey());

        new TranscribeCommand(plugin).execute(ctx, TranscribeCommand.START);

        verify(ctx).replyError("errors.api_key_missing");
        verify(stt, never()).start(any(), any());
    }

    // /forget

    @Test
    void anyoneCanEraseTheirOwnHistoryWithoutAPermission() {
        new ForgetCommand(plugin).execute(ctx, ForgetCommand.ME);

        verify(memory).forgetPerson(USER_ID);
        verify(ctx).replySuccess("messages.forgot_me");
        verifyNoInteractions(permissions);
    }

    @Test
    void erasingOnesOwnHistoryWorksOutsideAServerToo() {
        // It spans every server, so it cannot require being in one.
        when(ctx.getGuild()).thenReturn(Optional.empty());

        new ForgetCommand(plugin).execute(ctx, null);

        verify(memory).forgetPerson(USER_ID);
        verify(ctx, never()).replyError(anyString());
    }

    @Test
    void erasingSomeoneElsesConversationNeedsTheAdminPermission() {
        when(permissions.hasPermission(USER_ID, PLUGIN_ID + ".admin")).thenReturn(false);

        new ForgetCommand(plugin).execute(ctx, ForgetCommand.SERVER);
        new ForgetCommand(plugin).execute(ctx, ForgetCommand.CHANNEL);

        verify(ctx, times(2)).replyError("errors.no_permission");
        verify(memory, never()).forgetServer(anyString());
        verify(memory, never()).forgetChannel(anyString(), anyString());
    }

    @Test
    void anAdminCanClearAChannelOrTheWholeServer() {
        when(permissions.hasPermission(USER_ID, PLUGIN_ID + ".admin")).thenReturn(true);
        botConnectedTo("General");

        new ForgetCommand(plugin).execute(ctx, ForgetCommand.SERVER);
        verify(memory).forgetServer("g1");
        verify(ctx).replySuccess("messages.forgot_server");

        new ForgetCommand(plugin).execute(ctx, "CHANNEL"); // the value is matched case-insensitively
        verify(memory).forgetChannel("g1", "c1");
        verify(ctx).replySuccess("messages.forgot_channel");
    }

    @Test
    void clearingAChannelWhileNotConnectedToOneSaysSo() {
        when(permissions.hasPermission(USER_ID, PLUGIN_ID + ".admin")).thenReturn(true);
        when(audioManager.getConnectedChannel()).thenReturn(null);

        new ForgetCommand(plugin).execute(ctx, ForgetCommand.CHANNEL);

        verify(ctx).replyError("errors.not_connected");
        verify(memory, never()).forgetChannel(anyString(), anyString());
    }
}
