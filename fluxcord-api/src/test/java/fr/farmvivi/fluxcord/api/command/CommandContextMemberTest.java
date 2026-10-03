package fr.farmvivi.fluxcord.api.command;

import fr.farmvivi.fluxcord.api.command.option.CommandOption;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Locale;
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

        assertEquals(Optional.of(member), new Fixture(event, null, null).getMember());
    }

    @Test
    void aTextInvocationAnswersTheEventsMember() {
        Member member = mock(Member.class);
        MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        when(event.getMember()).thenReturn(member);

        assertEquals(Optional.of(member), new Fixture(event, null, null).getMember());
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

        assertEquals(Optional.of(member), new Fixture(null, user, guild).getMember());
    }

    @Test
    void somebodyTheGuildCannotNameIsEmptyRatherThanAnError() {
        User user = mock(User.class);
        when(user.getId()).thenReturn("u2");
        Guild guild = mock(Guild.class);
        when(guild.getMemberById("u2")).thenReturn(null);

        assertTrue(new Fixture(null, user, guild).getMember().isEmpty());
    }

    @Test
    void aConsoleInvocationHasNobodyToResolve() {
        // getUser() == null is what the core reads as "console"; there is no member behind it.
        assertTrue(new Fixture(null, null, mock(Guild.class)).getMember().isEmpty());
    }

    @Test
    void outsideAServerThereIsNoMemberEither() {
        User user = mock(User.class);
        when(user.getId()).thenReturn("u3");

        assertTrue(new Fixture(null, user, null).getMember().isEmpty());
    }

    /** The smallest context that exercises the default method: three fields, everything else refused. */
    private record Fixture(Event event, User user, Guild guild) implements CommandContext {

        @Override
        public Event getOriginalEvent() {
            return event;
        }

        @Override
        public User getUser() {
            return user;
        }

        @Override
        public Optional<Guild> getGuild() {
            return Optional.ofNullable(guild);
        }

        @Override
        public Command getCommand() {
            return null;
        }

        @Override
        public MessageChannel getChannel() {
            return null;
        }

        @Override
        public Locale getLocale() {
            return Locale.ROOT;
        }

        @Override
        public <T> Optional<T> getOption(String name) {
            return Optional.empty();
        }

        @Override
        public <T> T getOption(String name, T defaultValue) {
            return defaultValue;
        }

        @Override
        public <T> T getRequiredOption(String name) {
            throw new IllegalArgumentException(name);
        }

        @Override
        public <T> Optional<CommandOption<T>> getOptionDefinition(String name) {
            return Optional.empty();
        }

        @Override
        public boolean hasOption(String name) {
            return false;
        }

        @Override
        public void reply(String message) {
        }

        @Override
        public void reply(String message, Collection<MessageTopLevelComponent> components) {
        }

        @Override
        public void replyEmbed(EmbedBuilder embed) {
        }

        @Override
        public void replyEmbed(EmbedBuilder embed, Collection<MessageTopLevelComponent> components) {
        }

        @Override
        public void replySuccess(String message) {
        }

        @Override
        public void replyInfo(String message) {
        }

        @Override
        public void replyWarning(String message) {
        }

        @Override
        public void replyError(String message) {
        }

        @Override
        public void deferReply() {
        }

        @Override
        public void deferReply(boolean ephemeral) {
        }

        @Override
        public boolean isDeferred() {
            return false;
        }

        @Override
        public boolean isEphemeral() {
            return false;
        }

        @Override
        public void setEphemeral(boolean ephemeral) {
        }

        @Override
        public JDA getJDA() {
            return null;
        }
    }
}
