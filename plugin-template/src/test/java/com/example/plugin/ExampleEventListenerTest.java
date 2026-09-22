package com.example.plugin;

import com.example.plugin.events.ExampleEventListener;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.plugin.events.PluginEnableEvent;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Mentions;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The template's listener shows the two buses side by side: a JDA override for Discord messages and
 * an {@code @EventHandler} method for Fluxcord events. Both are guarded by configuration flags.
 */
class ExampleEventListenerTest {

    private AbstractPlugin plugin;
    private Configuration configuration;
    private ExampleEventListener listener;
    private MessageChannelUnion channel;
    private SelfUser selfUser;

    @BeforeEach
    void setUp() {
        configuration = mock(Configuration.class);
        when(configuration.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));

        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(0));

        plugin = mock(AbstractPlugin.class);
        when(plugin.getConfiguration()).thenReturn(configuration);
        when(plugin.getLanguage()).thenReturn(language);
        when(plugin.getLogger()).thenReturn(LoggerFactory.getLogger("listener-test"));

        selfUser = mock(SelfUser.class);
        channel = mock(MessageChannelUnion.class);
        listener = new ExampleEventListener(plugin);
    }

    private MessageReceivedEvent message(boolean fromBot, boolean mentionsBot) {
        User author = mock(User.class);
        when(author.isBot()).thenReturn(fromBot);
        when(author.getName()).thenReturn("tester");
        when(author.getAsMention()).thenReturn("<@1>");

        Mentions mentions = mock(Mentions.class);
        when(mentions.isMentioned(selfUser)).thenReturn(mentionsBot);

        Message message = mock(Message.class);
        when(message.getContentRaw()).thenReturn("hello");
        when(message.getMentions()).thenReturn(mentions);

        JDA jda = mock(JDA.class);
        when(jda.getSelfUser()).thenReturn(selfUser);

        MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        when(event.getAuthor()).thenReturn(author);
        when(event.getMessage()).thenReturn(message);
        when(event.getJDA()).thenReturn(jda);
        when(event.getChannel()).thenReturn(channel);
        when(channel.sendMessage(anyString())).thenReturn(mock(MessageCreateAction.class));
        return event;
    }

    @Test
    void botMessagesAreIgnored() {
        listener.onMessageReceived(message(true, true));

        verify(channel, never()).sendMessage(anyString());
    }

    @Test
    void aMentionIsAnsweredOnlyWhenTheFeatureIsOn() {
        listener.onMessageReceived(message(false, true));
        verify(channel, never()).sendMessage(anyString());

        when(configuration.getBoolean("features.respond_to_mentions", false)).thenReturn(true);
        listener.onMessageReceived(message(false, true));

        verify(channel).sendMessage("messages.mention_response");
    }

    @Test
    void aMessageWithoutAMentionIsLeftAlone() {
        when(configuration.getBoolean("features.respond_to_mentions", false)).thenReturn(true);

        listener.onMessageReceived(message(false, false));

        verify(channel, never()).sendMessage(anyString());
    }

    @Test
    void theWholeListenerCanBeSwitchedOff() {
        when(configuration.getBoolean("events.enabled", true)).thenReturn(false);
        when(configuration.getBoolean("features.respond_to_mentions", false)).thenReturn(true);

        listener.onMessageReceived(message(false, true));

        verify(channel, never()).sendMessage(anyString());
    }

    @Test
    void theFluxcordEventOnlyReadsThePluginWhenLoggingIsOn() {
        PluginEnableEvent event = mock(PluginEnableEvent.class);

        listener.onPluginEvent(event);
        verify(event, never()).getPlugin();

        Plugin enabled = mock(Plugin.class);
        when(enabled.getName()).thenReturn("other-plugin");
        when(event.getPlugin()).thenReturn(enabled);
        when(configuration.getBoolean("debug.log_plugin_events", false)).thenReturn(true);

        assertDoesNotThrow(() -> listener.onPluginEvent(event));
        verify(event).getPlugin();
    }
}
