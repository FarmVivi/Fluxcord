package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import fr.farmvivi.fluxcord.api.command.Command;
import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The context handed to a command that somebody asked for out loud.
 *
 * <p>It exists to be indistinguishable from a real invocation in every way a command can observe, with two
 * deliberate exceptions: there is no JDA event, and nothing is sent. Both are pinned here, because both are
 * load-bearing — the first is why {@code CommandContext.getMember()} had to exist, and the second is what
 * lets the bot speak the outcome instead of leaving it written in a channel.
 */
class AiCommandContextTest {

    private Member member;
    private User user;
    private Guild guild;
    private Command command;
    private MessageChannel channel;

    @BeforeEach
    void setUp() {
        user = mock(User.class);
        guild = mock(Guild.class);
        member = mock(Member.class);
        when(member.getUser()).thenReturn(user);
        when(member.getGuild()).thenReturn(guild);
        command = mock(Command.class);
        channel = mock(MessageChannel.class);
    }

    private AiCommandContext context(Map<String, Object> options) {
        return new AiCommandContext(command, member, channel, Locale.FRANCE, options);
    }

    @Test
    void theAskerIsTheUserAndIsNeverNull() {
        // A null user is what the core reads as a console invocation, which skips every permission check.
        AiCommandContext context = context(Map.of());

        assertSame(user, context.getUser());
        assertEquals(java.util.Optional.of(member), context.getMember());
        assertEquals(java.util.Optional.of(guild), context.getGuild());
        assertTrue(context.isFromGuild());
    }

    @Test
    void thereIsNoEventBecauseNothingHappenedOnDiscord() {
        assertNull(context(Map.of()).getOriginalEvent());
    }

    @Test
    void optionsAreReadBackAsTheyWerePutIn() {
        AiCommandContext context = context(Map.of("query", "daft punk", "now", true));

        assertEquals("daft punk", context.getOption("query").orElseThrow());
        assertEquals(Boolean.TRUE, context.getOption("now").orElseThrow());
        assertTrue(context.hasOption("query"));
        assertFalse(context.hasOption("volume"));
        assertEquals("fallback", context.getOption("missing", "fallback"));
        assertEquals("daft punk", context.getRequiredOption("query"));
    }

    @Test
    void aRequiredOptionThatIsNotThereFailsTheWayCommandsExpect() {
        assertThrows(IllegalArgumentException.class, () -> context(Map.of()).getRequiredOption("query"));
    }

    @Test
    void anOptionDefinitionComesFromTheCommandBeingRun() {
        CommandOption<?> option = mock(CommandOption.class);
        when(option.getName()).thenReturn("query");
        when(command.getOptions()).thenReturn(List.of(option));

        assertEquals(java.util.Optional.of(option), context(Map.of()).getOptionDefinition("query"));
        assertTrue(context(Map.of()).getOptionDefinition("elsewhere").isEmpty());
    }

    @Test
    void everyShapeOfReplyIsCapturedInOrderAndNothingIsSent() {
        AiCommandContext context = context(Map.of());

        context.replySuccess("started");
        context.replyInfo("for the record");
        context.replyWarning("careful");
        context.replyError("refused");
        context.reply("plain", List.of());

        assertEquals(List.of("started", "for the record", "careful", "refused", "plain"), context.replies());
        assertTrue(context.hasReplied());
        verifyNoInteractions(channel);
    }

    @Test
    void anEmbedIsReducedToWhatAVoiceCanCarry() {
        AiCommandContext context = context(Map.of());

        context.replyEmbed(new EmbedBuilder().setTitle("Now playing").setDescription("Around the World"));

        assertEquals(List.of("Now playing — Around the World"), context.replies());
    }

    @Test
    void anEmptyReplyIsNotWorthReporting() {
        AiCommandContext context = context(Map.of());

        context.reply("   ");
        context.replySuccess(null);
        context.replyEmbed(new EmbedBuilder());

        assertTrue(context.replies().isEmpty());
        assertFalse(context.hasReplied());
    }

    @Test
    void aReplyTooLongToSpeakIsCutRatherThanRead() {
        AiCommandContext context = context(Map.of());

        context.reply("x".repeat(AiCommandContext.MAX_REPLY_LENGTH + 50));

        String captured = context.replies().get(0);
        assertEquals(AiCommandContext.MAX_REPLY_LENGTH + 3, captured.length());
        assertTrue(captured.endsWith("..."));
    }

    @Test
    void deferringIsRecordedSoACommandThatDefersDoesNotThinkItFailed() {
        AiCommandContext context = context(Map.of());

        assertFalse(context.isDeferred());
        context.deferReply(true);

        assertTrue(context.isDeferred());
        assertTrue(context.isEphemeral());
    }

    @Test
    void ephemeralIsAcceptedAndMeansNothingHere() {
        AiCommandContext context = context(Map.of());

        context.setEphemeral(true);

        assertTrue(context.isEphemeral(), "a command may set it; there is simply nobody to hide it from");
    }
}
