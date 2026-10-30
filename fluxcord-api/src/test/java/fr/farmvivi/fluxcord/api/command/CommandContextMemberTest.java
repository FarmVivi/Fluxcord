package fr.farmvivi.fluxcord.api.command;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link CommandContext#getMember()}, which is how a command reaches the caller without asking how the
 * caller got there.
 *
 * <p>It is a default method so that every context written before it keeps working, and the three cases below
 * are the three it has to cover: the two event types commands used to pattern-match by hand, and everything
 * else — a modal, a console line, an invocation made on somebody's behalf — which used to resolve to nobody
 * and made {@code /play} tell a person in a voice channel that they were not in one.
 */
class CommandContextMemberTest {

    @Test
    void aSlashInvocationAnswersTheEventsMember() {
        Member member = mock(Member.class);
        SlashCommandInteractionEvent event = mock(SlashCommandInteractionEvent.class);
        when(event.getMember()).thenReturn(member);

        assertEquals(Optional.of(member), new StubCommandContext(event, null, null, null).getMember());
    }

    @Test
    void aTextInvocationAnswersTheEventsMember() {
        Member member = mock(Member.class);
        MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        when(event.getMember()).thenReturn(member);

        assertEquals(Optional.of(member), new StubCommandContext(event, null, null, null).getMember());
    }

    @Test
    void anythingElseFallsBackToTheGuildsOwnCache() {
        // The case that matters: no event, or an event nobody foresaw. Resolved from the cache and never
        // over REST, because a command may run on a thread that cannot wait for a round trip.
        Member member = mock(Member.class);
        User user = mock(User.class);
        when(user.getId()).thenReturn("u1");
        Guild guild = mock(Guild.class);
        when(guild.getMemberById("u1")).thenReturn(member);

        assertEquals(Optional.of(member), new StubCommandContext(null, user, guild, null).getMember());
    }

    @Test
    void somebodyTheGuildCannotNameIsEmptyRatherThanAnError() {
        User user = mock(User.class);
        when(user.getId()).thenReturn("u2");
        Guild guild = mock(Guild.class);
        when(guild.getMemberById("u2")).thenReturn(null);

        assertTrue(new StubCommandContext(null, user, guild, null).getMember().isEmpty());
    }

    @Test
    void aConsoleInvocationHasNobodyToResolve() {
        // getUser() == null is what the core reads as "console"; there is no member behind it.
        assertTrue(new StubCommandContext(null, null, mock(Guild.class), null).getMember().isEmpty());
    }

    @Test
    void outsideAServerThereIsNoMemberEither() {
        User user = mock(User.class);
        when(user.getId()).thenReturn("u3");

        assertTrue(new StubCommandContext(null, user, null, null).getMember().isEmpty());
    }

}
