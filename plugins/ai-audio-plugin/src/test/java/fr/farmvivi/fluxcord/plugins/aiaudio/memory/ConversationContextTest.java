package fr.farmvivi.fluxcord.plugins.aiaudio.memory;

import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.plugins.aiaudio.testing.MemoryDataStorage;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The snapshot the voice-to-voice step will be handed: where the conversation is, who is in it, and what
 * has been said. Nothing consumes it yet, so what is pinned here is the contract it promises.
 */
class ConversationContextTest {

    private static final String GUILD_ID = "g1";
    private static final String CHANNEL_ID = "c1";

    private ConversationMemory memory;
    private AudioChannel channel;
    private Guild guild;

    @BeforeEach
    void setUp() {
        memory = new ConversationMemory(
                new PluginDataStorageAdapter("ai-audio-plugin", new MemoryDataStorage()), 10, 10, 10);

        guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        when(guild.getName()).thenReturn("My Server");

        channel = mock(AudioChannel.class);
        when(channel.getId()).thenReturn(CHANNEL_ID);
        when(channel.getName()).thenReturn("General");
        when(channel.getGuild()).thenReturn(guild);
        when(channel.getMembers()).thenReturn(List.of());
    }

    private Member member(String id, String displayName, boolean bot) {
        User user = mock(User.class);
        when(user.isBot()).thenReturn(bot);
        Member member = mock(Member.class);
        when(member.getUser()).thenReturn(user);
        when(member.getId()).thenReturn(id);
        when(member.getEffectiveName()).thenReturn(displayName);
        return member;
    }

    @Test
    void theSnapshotNamesThePlaceAndWhoIsThere() {
        Member victor = member("u1", "Victor", false);
        Member alice = member("u2", "Alice", false);
        when(channel.getMembers()).thenReturn(List.of(victor, alice));

        ConversationContext context = ConversationContext.of(channel, memory, 10, 10);

        assertEquals(GUILD_ID, context.guildId());
        assertEquals("My Server", context.guildName());
        assertEquals(CHANNEL_ID, context.channelId());
        assertEquals("General", context.channelName());
        assertEquals(List.of("Victor", "Alice"),
                context.present().stream().map(ConversationContext.Participant::displayName).toList());
        assertEquals(List.of("u1", "u2"),
                context.present().stream().map(ConversationContext.Participant::userId).toList());
        assertFalse(context.isEmpty());
    }

    @Test
    void theBotItselfIsNotAParticipant() {
        // Otherwise the AI would be told it is talking to itself.
        Member bot = member("bot", "Fluxcord", true);
        Member victor = member("u1", "Victor", false);
        when(channel.getMembers()).thenReturn(List.of(bot, victor));

        ConversationContext context = ConversationContext.of(channel, memory, 10, 10);

        assertEquals(List.of("Victor"),
                context.present().stream().map(ConversationContext.Participant::displayName).toList());
    }

    @Test
    void anEmptyChannelIsReportedAsHavingNobodyToTalkTo() {
        ConversationContext context = ConversationContext.of(channel, memory, 10, 10);

        assertTrue(context.isEmpty());
        assertTrue(context.channelTurns().isEmpty());
    }

    @Test
    void theTwoHistoriesAreReadAtTheirOwnDepth() {
        memory.remember(new Turn(1000, "u1", "Victor", GUILD_ID, "My Server", CHANNEL_ID, "General", "ici"));
        memory.remember(new Turn(2000, "u1", "Victor", GUILD_ID, "My Server", "c2", "Music", "ailleurs"));

        ConversationContext context = ConversationContext.of(channel, memory, 10, 1);

        assertEquals(List.of("ici"), context.channelTurns().stream().map(Turn::text).toList(),
                "only this channel's conversation");
        assertEquals(List.of("ailleurs"), context.serverTurns().stream().map(Turn::text).toList(),
                "the server history spans channels and was asked for one turn only");
    }

    @Test
    void aTurnKnowsWhenItWasSaid() {
        Turn turn = Turn.now("u1", "Victor", GUILD_ID, "My Server", CHANNEL_ID, "General", "salut");

        assertEquals(turn.timestampMs(), turn.instant().toEpochMilli());
        assertEquals(turn.timestampMs() + "-u1", turn.storageKey());
    }
}
