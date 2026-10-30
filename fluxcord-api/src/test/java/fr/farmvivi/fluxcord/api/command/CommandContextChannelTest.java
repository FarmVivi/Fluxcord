package fr.farmvivi.fluxcord.api.command;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CommandContext#getAttachedChannel()}: which channel is safe to keep.
 *
 * <p>Discord's channel obfuscation becomes mandatory on 2026-11-16, and from then on an interaction in a
 * channel the bot cannot see carries a detached snapshot. The snapshot answers the interaction perfectly
 * well; what it cannot do is receive a message. The whole point of this method is that the refusal happens
 * here, while somebody is waiting for an answer, instead of twenty minutes later on a scheduler thread
 * inside whatever kept the channel.
 */
class CommandContextChannelTest {

    @Test
    void anOrdinaryChannelIsItsOwnAnswer() {
        MessageChannel channel = mock(MessageChannel.class);
        when(channel.isDetached()).thenReturn(false);

        assertEquals(Optional.of(channel),
                new StubCommandContext(null, null, mock(Guild.class), channel).getAttachedChannel());
    }

    @Test
    void aDetachedSnapshotIsTradedForTheRealChannelTheGuildHolds() {
        // Detached is not the same as unreachable: a channel arrives detached for several reasons, and when
        // the bot is in fact a member of that guild the cache has the real thing under the same id.
        TextChannel real = mock(TextChannel.class);
        when(real.isObfuscated()).thenReturn(false);

        assertEquals(Optional.of(real), new StubCommandContext(null, null,
                guildHolding(42L, real), snapshot(42L)).getAttachedChannel());
    }

    @Test
    void anObfuscatedChannelIsNowhereToWrite() {
        // The bot has no view of it, so the cached copy is no better than the snapshot. Empty, and the
        // caller decides what to tell the user.
        TextChannel cached = mock(TextChannel.class);
        when(cached.isObfuscated()).thenReturn(true);

        assertTrue(new StubCommandContext(null, null, guildHolding(42L, cached), snapshot(42L))
                .getAttachedChannel().isEmpty());
    }

    @Test
    void aSnapshotOfAChannelTheGuildDoesNotHoldIsEmptyRatherThanAnError() {
        assertTrue(new StubCommandContext(null, null, guildHolding(42L, null), snapshot(42L))
                .getAttachedChannel().isEmpty());
    }

    @Test
    void aDetachedChannelOutsideAnyServerHasNoCacheToFallBackOn() {
        assertTrue(new StubCommandContext(null, null, null, snapshot(42L))
                .getAttachedChannel().isEmpty());
    }

    @Test
    void aConsoleInvocationHasNoChannelAtAll() {
        // getChannel() == null is the console and the AI's own invocations; nothing to anchor, and asking
        // must not throw.
        assertTrue(new StubCommandContext(null, null, mock(Guild.class), null)
                .getAttachedChannel().isEmpty());
    }

    private static MessageChannel snapshot(long id) {
        MessageChannel channel = mock(MessageChannel.class);
        when(channel.isDetached()).thenReturn(true);
        when(channel.getIdLong()).thenReturn(id);
        return channel;
    }

    private static Guild guildHolding(long id, GuildMessageChannel channel) {
        Guild guild = mock(Guild.class);
        when(guild.getChannelById(GuildMessageChannel.class, id)).thenReturn(channel);
        return guild;
    }
}
