package fr.farmvivi.fluxcord.plugins.aiaudio.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import fr.farmvivi.fluxcord.plugins.aiaudio.AiSettings;
import fr.farmvivi.fluxcord.plugins.aiaudio.SpeechRecognitionService;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.AiEndpoint;
import fr.farmvivi.fluxcord.plugins.aiaudio.conversation.ConversationService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@code /converse} — the switch that lets the bot answer out loud.
 *
 * <p>Starting has to do two things at once: join and start listening. The bot cannot answer what it does not
 * hear, and making a user run two commands to get one behaviour is the kind of thing nobody remembers.
 */
class ConverseCommandTest {

    private static final String GUILD_ID = "g1";

    private AIAudioPlugin plugin;
    private ConversationService conversation;
    private SpeechRecognitionService speech;
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

        conversation = mock(ConversationService.class);
        speech = mock(SpeechRecognitionService.class);

        plugin = mock(AIAudioPlugin.class);
        when(plugin.getLanguage()).thenReturn(language);
        when(plugin.getLogger()).thenReturn(LoggerFactory.getLogger("converse-test"));
        when(plugin.getConversation()).thenReturn(conversation);
        when(plugin.getSpeechRecognition()).thenReturn(speech);
        when(plugin.getSettings()).thenReturn(settings(true, ""));

        AudioChannelUnion channel = mock(AudioChannelUnion.class);
        when(channel.getId()).thenReturn("c1");
        audioManager = mock(AudioManager.class);
        when(audioManager.isConnected()).thenReturn(true);
        guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        when(guild.getAudioManager()).thenReturn(audioManager);

        voiceState = mock(GuildVoiceState.class);
        when(voiceState.getChannel()).thenReturn(channel);
        Member member = mock(Member.class);
        when(member.getVoiceState()).thenReturn(voiceState);
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getMember()).thenReturn(member);

        ctx = mock(CommandContext.class);
        when(ctx.getGuild()).thenReturn(Optional.of(guild));
        when(ctx.getLocale()).thenReturn(Locale.FRANCE);
        when(ctx.getOriginalEvent()).thenReturn(event);
        when(ctx.getChannel()).thenReturn(mock(MessageChannel.class));
    }

    /** The usual case: a self-hosted model, which needs no key. */
    private AiSettings settings(boolean enabled, String wakeWord) {
        return settings(enabled, wakeWord,
                new AiEndpoint("http://localhost:11434/v1", "", "m", Duration.ofSeconds(5)));
    }

    private AiSettings settings(boolean enabled, String wakeWord, AiEndpoint chat) {
        AiEndpoint local = new AiEndpoint("http://localhost:11434/v1", "", "m", Duration.ofSeconds(5));
        return new AiSettings(local, AiSettings.SpeechApi.OLLAMA, "fr-FR", local, "alloy", 100, 80, 1000,
                Duration.ofSeconds(1), Duration.ofSeconds(20), Duration.ofMillis(400), 20, 20, 20,
                AiSettings.PersonaSettings.defaults(),
                new AiSettings.ChatSettings(chat, enabled, wakeWord, 8, 120, 0.7, "none", "low", false, 3));
    }

    /** OpenAI with no key: the misconfiguration users hit first. */
    private AiSettings hostedWithoutKey() {
        return settings(true, "",
                new AiEndpoint("https://api.openai.com/v1", "", "m", Duration.ofSeconds(5)));
    }

    private void run(String action) {
        new ConverseCommand(plugin).execute(ctx, action);
    }

    @Test
    void startingAlsoStartsListening() {
        // Answering requires hearing; two commands for one behaviour is one too many.
        when(conversation.start(guild)).thenReturn(true);

        run(ConverseCommand.START);

        verify(speech).start(same(guild), any());
        verify(conversation).start(guild);
        verify(ctx).replySuccess("messages.converse_started");
    }

    @Test
    void startingJoinsTheCallersChannelWhenTheBotIsNotConnected() {
        when(audioManager.isConnected()).thenReturn(false);
        when(conversation.start(guild)).thenReturn(true);

        run(ConverseCommand.START);

        verify(audioManager).openAudioConnection(any());
    }

    @Test
    void startingWithoutAVoiceChannelSaysToJoinOne() {
        when(audioManager.isConnected()).thenReturn(false);
        when(voiceState.getChannel()).thenReturn(null);

        run(ConverseCommand.START);

        verify(ctx).replyError("errors.no_voice_channel");
        verifyNoInteractions(conversation);
    }

    @Test
    void theWakeWordIsReportedWhenThereIsOne() {
        // Otherwise nobody knows the bot is waiting to be addressed.
        when(plugin.getSettings()).thenReturn(settings(true, "hé flux"));
        when(conversation.start(guild)).thenReturn(true);

        run(ConverseCommand.START);

        verify(ctx).replySuccess("messages.converse_started_wake_word");
    }

    @Test
    void startingTwiceSaysSo() {
        when(conversation.start(guild)).thenReturn(false);

        run(ConverseCommand.START);

        verify(ctx).replyError("errors.already_conversing");
    }

    @Test
    void aDisabledConversationIsRefusedWithTheReason() {
        // The setting is the operator's; saying "already answering" or staying silent would be a guess.
        when(plugin.getSettings()).thenReturn(settings(false, ""));

        run(ConverseCommand.START);

        verify(ctx).replyError("errors.conversation_disabled");
        verifyNoInteractions(conversation);
        verifyNoInteractions(speech);
    }

    @Test
    void aHostedModelWithoutAKeyIsRefusedBeforeAnythingStarts() {
        when(plugin.getSettings()).thenReturn(hostedWithoutKey());

        run(ConverseCommand.START);

        verify(ctx).replyError("errors.api_key_missing");
        verifyNoInteractions(conversation);
    }

    @Test
    void aLocalModelNeedsNoKey() {
        // The default in this test: nothing is asked of a self-hosted endpoint.
        when(conversation.start(guild)).thenReturn(true);

        run(ConverseCommand.START);

        verify(ctx).replySuccess("messages.converse_started");
    }

    @Test
    void stoppingLeavesTheTranscriptionRunning() {
        // Writing down a conversation without taking part in it is a reasonable thing to want.
        when(conversation.stop(guild)).thenReturn(true);

        run(ConverseCommand.STOP);

        verify(ctx).replySuccess("messages.converse_stopped");
        verify(speech, never()).stop(any());
    }

    @Test
    void stoppingWhenNothingWasRunningSaysSo() {
        when(conversation.stop(guild)).thenReturn(false);

        run(ConverseCommand.STOP);

        verify(ctx).replySuccess("errors.not_conversing");
    }

    @Test
    void stoppingNeedsNoVoiceChannelAtAll() {
        when(audioManager.isConnected()).thenReturn(false);
        when(voiceState.getChannel()).thenReturn(null);
        when(conversation.stop(guild)).thenReturn(true);

        run(ConverseCommand.STOP);

        verify(ctx).replySuccess("messages.converse_stopped");
    }

    @Test
    void theCommandRefusesToRunOutsideAServer() {
        when(ctx.getGuild()).thenReturn(Optional.empty());

        run(ConverseCommand.START);

        verify(ctx).replyError("errors.guild_only");
        verifyNoInteractions(conversation);
    }
}
