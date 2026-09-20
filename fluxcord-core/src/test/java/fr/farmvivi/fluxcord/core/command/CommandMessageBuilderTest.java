package fr.farmvivi.fluxcord.core.command;

import fr.farmvivi.fluxcord.api.language.LanguageManager;
import fr.farmvivi.fluxcord.core.command.parser.event.ConsoleCommandEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Characterization of command replies (plan item C5): how a composed message reaches each transport — slash
 * interaction (initial reply, deferral, edit of the placeholder), text message (reply + ephemeral emulation) and
 * console (plain-text rendering) — and the Discord limits applied while composing.
 */
class CommandMessageBuilderTest {

    private final LanguageManager lang = mock(LanguageManager.class);
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream console = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        when(lang.getString(any(), anyString())).thenAnswer(inv -> "T:" + inv.getArgument(1, String.class));
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    // ---- composition ------------------------------------------------------------------------------------------

    @Test
    void typedEmbedsGetTranslatedTitlesAndColours() {
        CommandMessageBuilder builder = new CommandMessageBuilder(new ConsoleCommandEvent(mock(JDA.class), "x"), lang, Locale.US);
        builder.error("boom");
        builder.success("Custom", "done");

        List<MessageEmbed> embeds = builder.build().getEmbeds();
        assertEquals("T:commands.titles.error", embeds.get(0).getTitle());
        assertEquals("boom", embeds.get(0).getDescription());
        assertEquals("Custom", embeds.get(1).getTitle());
        assertNotEquals(embeds.get(0).getColorRaw(), embeds.get(1).getColorRaw());
    }

    @Test
    void discordLimitsAreAppliedWhileComposing() {
        CommandMessageBuilder builder = new CommandMessageBuilder(new ConsoleCommandEvent(mock(JDA.class), "x"), lang, Locale.US);
        builder.setContent("x".repeat(Message.MAX_CONTENT_LENGTH + 50));
        builder.setEmbeds(IntStream.range(0, Message.MAX_EMBED_COUNT + 3)
                .mapToObj(i -> new EmbedBuilder().setDescription("e" + i).build()).toList());

        MessageCreateData data = builder.build();
        assertEquals(Message.MAX_CONTENT_LENGTH, data.getContent().length());
        assertTrue(data.getContent().endsWith("..."));
        assertEquals(Message.MAX_EMBED_COUNT, data.getEmbeds().size());
    }

    // ---- slash interaction -------------------------------------------------------------------------------------

    @Test
    void freshInteractionGetsAnInitialReply() {
        SlashCommandInteractionEvent event = slash(false);
        ReplyCallbackAction action = mock(ReplyCallbackAction.class, RETURNS_SELF);
        when(event.reply(anyString())).thenReturn(action);

        CommandMessageBuilder builder = new CommandMessageBuilder(event, lang, Locale.US);
        builder.setContent("hello");
        builder.info("details");
        builder.setEphemeral(true);
        builder.replyNow();

        verify(event).reply("hello");
        verify(action).setEphemeral(true);
        verify(action).addEmbeds(anyCollection());
        verify(action).queue();
        verify(event, never()).deferReply(anyBoolean());
    }

    @Test
    void deferFlagOnAFreshInteractionOnlyDefers() {
        SlashCommandInteractionEvent event = slash(false);
        when(event.deferReply(anyBoolean())).thenReturn(mock(ReplyCallbackAction.class, RETURNS_SELF));

        CommandMessageBuilder builder = new CommandMessageBuilder(event, lang, Locale.US);
        builder.setContent("later");
        builder.setDiffer(true);
        builder.setEphemeral(true);
        builder.replyNow();

        verify(event).deferReply(true);
        verify(event, never()).reply(anyString());
    }

    @Test
    void acknowledgedInteractionEditsThePlaceholderOrDeletesItWhenEmpty() {
        SlashCommandInteractionEvent event = slash(true);
        InteractionHook hook = mock(InteractionHook.class, RETURNS_DEEP_STUBS);
        WebhookMessageEditAction<Message> edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
        when(hook.editOriginal(anyString())).thenReturn(edit);
        when(event.getHook()).thenReturn(hook);

        CommandMessageBuilder builder = new CommandMessageBuilder(event, lang, Locale.US);
        builder.error("nope");
        builder.replyNow();
        verify(hook).editOriginal("");
        verify(edit).setEmbeds(anyCollection());
        verify(edit).queue();

        new CommandMessageBuilder(event, lang, Locale.US).replyNow();
        verify(hook.deleteOriginal()).queue();
    }

    @Test
    void emptyInitialReplyIsAPlaceholderThatDeletesItself() {
        SlashCommandInteractionEvent event = slash(false);
        ReplyCallbackAction action = mock(ReplyCallbackAction.class, RETURNS_DEEP_STUBS);
        when(event.reply(anyString())).thenReturn(action);
        when(action.setEphemeral(anyBoolean())).thenReturn(action);

        new CommandMessageBuilder(event, lang, Locale.US).replyNow();

        verify(event).reply("​");
        verify(action).flatMap(any());
    }

    // ---- text message ------------------------------------------------------------------------------------------

    @Test
    void textReplyAnswersTheMessageAndEphemeralIsEmulatedByDeletion() {
        MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        Message message = mock(Message.class);
        MessageCreateAction action = mock(MessageCreateAction.class);
        when(event.getMessage()).thenReturn(message);
        when(event.isFromGuild()).thenReturn(false);
        when(message.reply(any(MessageCreateData.class))).thenReturn(action);

        CommandMessageBuilder plain = new CommandMessageBuilder(event, lang, Locale.US);
        plain.setContent("hi");
        plain.replyNow();
        verify(action).queue();

        CommandMessageBuilder ephemeral = new CommandMessageBuilder(event, lang, Locale.US);
        ephemeral.setContent("secret");
        ephemeral.setEphemeral(true);
        ephemeral.replyNow();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Message>> onSent = ArgumentCaptor.forClass(Consumer.class);
        verify(action).queue(onSent.capture());
        Message sent = mock(Message.class, RETURNS_DEEP_STUBS);
        onSent.getValue().accept(sent);
        verify(sent.delete()).queueAfter(eq(1L), eq(java.util.concurrent.TimeUnit.MINUTES));

        new CommandMessageBuilder(event, lang, Locale.US).replyNow();
        verify(message, times(2)).reply(any(MessageCreateData.class)); // nothing sent for an empty message
    }

    // ---- console -----------------------------------------------------------------------------------------------

    @Test
    void consoleRendersContentAndEmbedsAsPrefixedLines() {
        CommandMessageBuilder builder = new CommandMessageBuilder(new ConsoleCommandEvent(mock(JDA.class), "x"), lang, Locale.US);
        builder.setContent("line1\nline2");
        builder.addEmbeds(new EmbedBuilder().setTitle("Title").setDescription("desc")
                .addField("Name", "v1\nv2", false).addField("Single", "s", false).build());
        builder.replyNow();

        String out = console.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals("""
                [CONSOLE] line1
                [CONSOLE] line2

                [CONSOLE] === Title ===
                [CONSOLE] desc
                [CONSOLE] Name: v1
                [CONSOLE] v2
                [CONSOLE] Single: s
                """, out);

        console.reset();
        new CommandMessageBuilder(new ConsoleCommandEvent(mock(JDA.class), "x"), lang, Locale.US).replyNow();
        assertEquals("", console.toString(StandardCharsets.UTF_8), "nothing printed for an empty message");
    }

    private static SlashCommandInteractionEvent slash(boolean acknowledged) {
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.isAcknowledged()).thenReturn(acknowledged);
        return event;
    }
}
