package fr.farmvivi.fluxcord.plugins.aiaudio;

import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.plugins.aiaudio.conversation.ConversationService;
import fr.farmvivi.fluxcord.plugins.aiaudio.testing.MemoryDataStorage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whether a restart resumes listening, and the three cases where it had better not.
 *
 * <p>A pod rescheduled overnight used to end {@code /converse} silently: the bot came back, said nothing,
 * and nobody could tell the difference between that and a quiet channel. Resuming is therefore the default
 * — but resuming into a room that emptied hours ago is worse than not resuming at all, so the cases pinned
 * here are the refusals as much as the restore.
 */
class SessionPersistenceTest {

    private static final long NOW = 1_000_000L;
    /** The time-to-live every test here applies, so the refresh threshold is half of it. */
    private static final long TTL = 3_600_000L;
    private static final String GUILD_ID = "g1";
    private static final String VOICE_ID = "v1";
    private static final String TEXT_ID = "t1";

    private final AtomicLong clock = new AtomicLong(NOW);
    private PluginDataStorageAdapter storage;
    private AIAudioPlugin plugin;
    private SpeechRecognitionService speech;
    private ConversationService conversation;
    private AudioManager audioManager;
    private Guild guild;
    private JDA jda;
    private AudioChannelUnion voice;
    private SessionPersistence persistence;

    @BeforeEach
    void setUp() {
        storage = new PluginDataStorageAdapter("ai-audio-plugin", new MemoryDataStorage());
        plugin = mock(AIAudioPlugin.class);
        speech = mock(SpeechRecognitionService.class);
        conversation = mock(ConversationService.class);
        when(plugin.getSpeechRecognition()).thenReturn(speech);
        when(plugin.getConversation()).thenReturn(conversation);

        guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        audioManager = mock(AudioManager.class);
        when(guild.getAudioManager()).thenReturn(audioManager);

        voice = mock(AudioChannelUnion.class);
        when(voice.getId()).thenReturn(VOICE_ID);
        when(voice.getName()).thenReturn("General");
        // The member is built before the stubbing and not inside it: a when(...) evaluated while
        // another when(...) is open is what Mockito calls an unfinished stubbing.
        Member listener = human();
        when(voice.getMembers()).thenReturn(List.of(listener));
        when(guild.getChannelById(AudioChannel.class, VOICE_ID)).thenReturn(voice);

        GuildMessageChannel text = mock(GuildMessageChannel.class);
        when(guild.getChannelById(GuildMessageChannel.class, TEXT_ID)).thenReturn(text);

        jda = mock(JDA.class);
        when(jda.getGuilds()).thenReturn(List.of(guild));

        persistence = new SessionPersistence(plugin, storage, LoggerFactory.getLogger("test"), clock::get);
    }

    private Member human() {
        Member member = mock(Member.class);
        User user = mock(User.class);
        when(user.isBot()).thenReturn(false);
        when(member.getUser()).thenReturn(user);
        return member;
    }

    /** A guild that was listening is saved, and on the way back the bot rejoins and listens again. */
    @Test
    void listeningSurvivesARestart() {
        when(speech.isActive(guild)).thenReturn(true);
        when(speech.outputFor(guild)).thenReturn(java.util.Optional.empty());
        when(conversation.isActive(guild)).thenReturn(true);
        when(audioManager.getConnectedChannel()).thenReturn(voice);

        persistence.save(jda, TTL);
        persistence.restore(jda, TTL);

        verify(audioManager).openAudioConnection(voice);
        verify(speech).start(any(), any());
        verify(conversation).start(any(), any());
    }

    @Test
    void theConversationsChannelIsKeptWhenNothingIsBeingWrittenDown() {
        // Since "/converse" stopped publishing transcriptions there is no transcription output to save, and
        // asking only the transcriber meant the channel was lost on every restart. What followed was quiet
        // and nasty: a command the model ran afterwards had no channel, so the music player's own message
        // was never updated again - it simply froze in the channel, hours out of date.
        var text = mock(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class);
        when(text.getId()).thenReturn("tc-1");
        when(guild.getChannelById(
                eq(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class),
                eq("tc-1"))).thenReturn(text);
        when(speech.isActive(guild)).thenReturn(true);
        when(speech.outputFor(guild)).thenReturn(java.util.Optional.empty());
        when(conversation.outputFor(GUILD_ID)).thenReturn(java.util.Optional.of(text));
        when(conversation.isActive(guild)).thenReturn(true);
        when(audioManager.getConnectedChannel()).thenReturn(voice);

        persistence.save(jda, TTL);
        persistence.restore(jda, TTL);

        verify(conversation).start(any(), eq(text));
        // And listening does NOT become publishing. Victor ran "/converse", never "/transcribe", and
        // restoring the one stored channel into both made the bot narrate the channel after a restart.
        verify(speech).start(any(), eq((net.dv8tion.jda.api.entities.channel.middleman.MessageChannel) null));
    }

    @Test
    void aTranscriptThatWasAskedForKeepsItsOwnChannel() {
        var text = mock(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class);
        when(text.getId()).thenReturn("tc-2");
        when(guild.getChannelById(
                eq(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class),
                eq("tc-2"))).thenReturn(text);
        when(speech.isActive(guild)).thenReturn(true);
        when(speech.outputFor(guild)).thenReturn(java.util.Optional.of(text));
        when(conversation.outputFor(GUILD_ID)).thenReturn(java.util.Optional.empty());
        when(audioManager.getConnectedChannel()).thenReturn(voice);

        persistence.save(jda, TTL);
        persistence.restore(jda, TTL);

        verify(speech).start(any(), eq(text));
    }

    /**
     * Somebody who ran {@code /converse stop} before the restart meant it. The entry is removed rather
     * than left behind, or the bot would walk back into a channel it was dismissed from.
     */
    @Test
    void aStoppedSessionIsNotResumed() {
        when(speech.isActive(guild)).thenReturn(true);
        when(speech.outputFor(guild)).thenReturn(java.util.Optional.empty());
        when(audioManager.getConnectedChannel()).thenReturn(voice);
        persistence.save(jda, TTL);

        when(speech.isActive(guild)).thenReturn(false);
        persistence.save(jda, TTL);

        assertTrue(storage.getGuildStorage(GUILD_ID).get(SessionPersistence.STATE_KEY, java.util.Map.class)
                .isEmpty(), "the state of a guild that stopped listening is cleared, not kept");
    }

    /** Rejoining a channel somebody left hours ago is not resuming, it is haunting. */
    @Test
    void stateOlderThanTheTtlIsDropped() {
        when(speech.isActive(guild)).thenReturn(true);
        when(speech.outputFor(guild)).thenReturn(java.util.Optional.empty());
        when(audioManager.getConnectedChannel()).thenReturn(voice);
        persistence.save(jda, TTL);

        clock.addAndGet(7_200_000L);
        persistence.restore(jda, TTL);

        verify(audioManager, never()).openAudioConnection(any());
        assertTrue(storage.getGuildStorage(GUILD_ID).get(SessionPersistence.STATE_KEY, java.util.Map.class)
                .isEmpty(), "an expired state is removed so it cannot be weighed again");
    }

    /** An empty channel costs a voice connection and a poll every 250 ms to hear nobody. */
    @Test
    void anEmptyChannelIsNotRejoined() {
        when(speech.isActive(guild)).thenReturn(true);
        when(speech.outputFor(guild)).thenReturn(java.util.Optional.empty());
        when(audioManager.getConnectedChannel()).thenReturn(voice);
        persistence.save(jda, TTL);

        Member bot = mock(Member.class);
        User botUser = mock(User.class);
        when(botUser.isBot()).thenReturn(true);
        when(bot.getUser()).thenReturn(botUser);
        List<Member> onlyTheBot = List.of(bot);
        when(voice.getMembers()).thenReturn(onlyTheBot);

        persistence.restore(jda, TTL);

        verify(audioManager, never()).openAudioConnection(any());
        verify(speech, never()).start(any(), any());
    }

    /** A guild that was never listening has nothing to say about itself. */
    @Test
    void aGuildThatWasNotListeningIsNotResumed() {
        when(speech.isActive(guild)).thenReturn(false);

        persistence.save(jda, TTL);
        persistence.restore(jda, TTL);

        verify(audioManager, never()).openAudioConnection(any());
        assertFalse(storage.getGuildStorage(GUILD_ID).exists(SessionPersistence.STATE_KEY));
    }

    // The periodic save: onDisable alone only covers a graceful stop, and an OOM kill is not one.

    @Test
    void savingAgainWithNothingChangedRewritesNothing() {
        // Called every few seconds by the plugin, so a save that always writes would be a write per tick
        // per guild, on a shared database, forever. Observable without counting writes: the stored
        // timestamp is what a rewrite would change.
        listening();
        persistence.save(jda, TTL);
        long first = savedAt();

        clock.addAndGet(30_000L);
        persistence.save(jda, TTL);

        assertEquals(first, savedAt(), "an unchanged state is left exactly as it was");
    }

    @Test
    void aChangeIsWrittenAtOnce() {
        listening();
        persistence.save(jda, TTL);
        long first = savedAt();

        clock.addAndGet(30_000L);
        when(conversation.isActive(guild)).thenReturn(true);
        persistence.save(jda, TTL);

        assertNotEquals(first, savedAt(), "the state changed, so it is written");
        assertEquals(Boolean.TRUE, stored().get("conversing"));
    }

    @Test
    void aSessionListeningQuietlyLongerThanTheTtlStillComesBack() {
        // The trap in not rewriting an unchanged state: its timestamp ages, and restore drops anything
        // older than the TTL. Somebody listening in a quiet channel for two hours would have come back
        // from a crash to nothing - a regression introduced by the optimisation, not by the original bug.
        // So an unchanged state is refreshed once it is halfway to expiring.
        listening();
        persistence.save(jda, TTL);

        for (int minute = 0; minute < 120; minute++) {
            clock.addAndGet(60_000L);
            persistence.save(jda, TTL);
        }
        persistence.restore(jda, TTL);

        verify(audioManager).openAudioConnection(voice);
        verify(speech).start(any(), any());
    }

    @Test
    void withNoTtlThereIsNothingToKeepFresh() {
        listening();
        persistence.save(jda, 0L);
        long first = savedAt();

        clock.addAndGet(10 * 3_600_000L);
        persistence.save(jda, 0L);

        assertEquals(first, savedAt(), "nothing expires, so only a change is worth a write");
    }

    /** The common arrangement: this guild is listening in the voice channel, not conversing. */
    private void listening() {
        when(speech.isActive(guild)).thenReturn(true);
        when(speech.outputFor(guild)).thenReturn(java.util.Optional.empty());
        when(audioManager.getConnectedChannel()).thenReturn(voice);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> stored() {
        return (java.util.Map<String, Object>) storage.getGuildStorage(GUILD_ID)
                .get(SessionPersistence.STATE_KEY, java.util.Map.class).orElseThrow();
    }

    private long savedAt() {
        Object value = stored().get("savedAtMs");
        return value instanceof Number number ? number.longValue() : -1L;
    }
}
