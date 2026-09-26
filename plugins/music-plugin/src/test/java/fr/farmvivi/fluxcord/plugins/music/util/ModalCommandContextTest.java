package fr.farmvivi.fluxcord.plugins.music.util;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The adapter that lets the modal submission reuse the ordinary command code (
 * {@code MusicManager.loadTrack} takes a {@link fr.farmvivi.fluxcord.api.command.CommandContext}).
 *
 * <p>Its whole job is one decision, repeated for each reply shape: a Discord interaction may be
 * acknowledged only once, so a reply goes through {@code event.reply} the first time and through the
 * webhook hook afterwards. Getting that wrong throws from inside a callback, where the user sees nothing
 * but "the application did not respond" — and the modal path always defers first, so the hook branch is
 * the one that actually runs in production.
 */
class ModalCommandContextTest {

    private ModalInteractionEvent event;
    private InteractionHook hook;
    private ReplyCallbackAction reply;
    private WebhookMessageCreateAction<net.dv8tion.jda.api.entities.Message> webhook;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        reply = mock(ReplyCallbackAction.class);
        when(reply.setEphemeral(anyBoolean())).thenReturn(reply);
        when(reply.addComponents(any(java.util.Collection.class))).thenReturn(reply);

        webhook = mock(WebhookMessageCreateAction.class);
        when(webhook.setEphemeral(anyBoolean())).thenReturn(webhook);
        when(webhook.addComponents(any(java.util.Collection.class))).thenReturn(webhook);

        hook = mock(InteractionHook.class);
        when(hook.sendMessage(anyString())).thenReturn(webhook);
        when(hook.sendMessageEmbeds(any(net.dv8tion.jda.api.entities.MessageEmbed.class))).thenReturn(webhook);

        event = mock(ModalInteractionEvent.class);
        when(event.getHook()).thenReturn(hook);
        when(event.reply(anyString())).thenReturn(reply);
        when(event.replyEmbeds(any(net.dv8tion.jda.api.entities.MessageEmbed.class))).thenReturn(reply);
        when(event.deferReply(anyBoolean())).thenReturn(reply);
    }

    private ModalCommandContext context(boolean deferred, boolean ephemeral) {
        return new ModalCommandContext(event, Locale.FRANCE, deferred, ephemeral);
    }

    @Test
    void aFreshContextRepliesDirectly() {
        context(false, true).reply("hello");

        verify(event).reply("hello");
        verify(hook, never()).sendMessage(anyString());
    }

    @Test
    void anAlreadyDeferredContextRepliesThroughTheHook() {
        // This is the production path: the modal listener defers before dispatching.
        context(true, true).reply("hello");

        verify(hook).sendMessage("hello");
        verify(event, never()).reply(anyString());
    }

    @Test
    void anAcknowledgedInteractionAlsoGoesThroughTheHook() {
        when(event.isAcknowledged()).thenReturn(true);

        context(false, true).reply("hello");

        verify(hook).sendMessage("hello");
        verify(event, never()).reply(anyString());
    }

    @Test
    void theEphemeralFlagIsHonouredOnADirectReply() {
        context(false, false).reply("visible to all");

        verify(reply).setEphemeral(false);
    }

    @Test
    void aReplyThroughTheHookIsAlwaysEphemeral() {
        // A deferred modal reply belongs to the person who submitted it; the channel does not need it.
        context(true, false).reply("only for you");

        verify(webhook).setEphemeral(true);
    }

    @Test
    void everyReplyShapeFollowsTheSameRule() {
        ModalCommandContext direct = context(false, true);
        direct.reply("a", List.of());
        direct.replyEmbed(new EmbedBuilder().setDescription("b"));
        direct.replyEmbed(new EmbedBuilder().setDescription("c"), List.of());

        verify(event).reply("a");
        verify(event, times(2)).replyEmbeds(any(net.dv8tion.jda.api.entities.MessageEmbed.class));

        ModalCommandContext deferred = context(true, true);
        deferred.reply("d", List.of());
        deferred.replyEmbed(new EmbedBuilder().setDescription("e"));
        deferred.replyEmbed(new EmbedBuilder().setDescription("f"), List.of());

        verify(hook).sendMessage("d");
        verify(hook, times(2)).sendMessageEmbeds(any(net.dv8tion.jda.api.entities.MessageEmbed.class));
    }

    @Test
    void theSuccessInfoWarningAndErrorRepliesAreAllPlainReplies() {
        // No colour or prefix here: the modal context has no embed template of its own.
        ModalCommandContext context = context(false, true);

        context.replySuccess("ok");
        context.replyInfo("fyi");
        context.replyWarning("careful");
        context.replyError("nope");

        verify(event).reply("ok");
        verify(event).reply("fyi");
        verify(event).reply("careful");
        verify(event).reply("nope");
    }

    @Test
    void deferringMarksTheContextAndRemembersTheVisibility() {
        ModalCommandContext context = context(false, false);
        assertFalse(context.isDeferred());

        context.deferReply(true);

        assertTrue(context.isDeferred());
        assertTrue(context.isEphemeral(), "the deferral decides the visibility of what follows");
        verify(event).deferReply(true);
    }

    @Test
    void deferringWithoutAnArgumentKeepsTheCurrentVisibility() {
        ModalCommandContext context = context(false, false);

        context.deferReply();

        verify(event).deferReply(false);
    }

    @Test
    void anAlreadyAcknowledgedInteractionIsNotDeferredTwice() {
        // Discord rejects the second acknowledgement, but the context must still consider itself deferred.
        when(event.isAcknowledged()).thenReturn(true);
        ModalCommandContext context = context(false, true);

        context.deferReply(true);

        verify(event, never()).deferReply(anyBoolean());
        assertTrue(context.isDeferred());
    }

    @Test
    void theVisibilityCanBeChangedAfterwards() {
        ModalCommandContext context = context(false, true);

        context.setEphemeral(false);

        assertFalse(context.isEphemeral());
    }

    @Test
    void theContextExposesWhoSubmittedTheModalAndWhere() {
        User user = mock(User.class);
        Guild guild = mock(Guild.class);
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        JDA jda = mock(JDA.class);
        when(event.getUser()).thenReturn(user);
        when(event.getGuild()).thenReturn(guild);
        when(event.getChannel()).thenReturn(channel);
        when(event.getJDA()).thenReturn(jda);
        ModalCommandContext context = context(true, true);

        assertSame(user, context.getUser());
        assertEquals(Optional.of(guild), context.getGuild());
        assertSame(channel, context.getChannel());
        assertSame(jda, context.getJDA());
        assertSame(event, context.getOriginalEvent());
        assertEquals(Locale.FRANCE, context.getLocale());
    }

    @Test
    void aModalSubmittedOutsideAGuildReportsNoGuild() {
        when(event.getGuild()).thenReturn(null);

        assertEquals(Optional.empty(), context(true, true).getGuild());
    }

    @Test
    void aModalHasNoCommandAndNoOptions() {
        // It is not a command invocation: the queue and the track come from the modal's fields, which the
        // listener reads itself. Anything asking this context for options must get a clear nothing.
        ModalCommandContext context = context(true, true);

        assertNull(context.getCommand());
        assertEquals(Optional.empty(), context.getOption("anything"));
        assertEquals("fallback", context.getOption("anything", "fallback"));
        assertThrows(IllegalArgumentException.class, () -> context.getRequiredOption("anything"),
                "asking a modal for a required option is a programming error, not an empty answer");
        assertEquals(Optional.empty(), context.getOptionDefinition("anything"));
        assertFalse(context.hasOption("anything"));
    }
}
