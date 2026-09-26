package fr.farmvivi.fluxcord.plugins.music;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import org.mockito.ArgumentCaptor;
import net.dv8tion.jda.api.EmbedBuilder;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.StorageKey;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.playlist.Playlist;
import fr.farmvivi.fluxcord.plugins.music.state.PlaybackState;
import fr.farmvivi.fluxcord.plugins.music.state.TrackCodec;
import fr.farmvivi.fluxcord.plugins.music.testing.MemoryDataStorage;
import fr.farmvivi.fluxcord.plugins.music.testing.ScriptedAudioPlayer;
import fr.farmvivi.fluxcord.plugins.music.testing.TestAudioSource;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link MusicManager}: one player per guild, the voice-update handling that tells a transient
 * reconnect from a real disconnect, and the boot-time restore of persisted playback.
 */
class MusicManagerTest {

    private static final String GUILD_ID = "100";
    private static final String VOICE_ID = "vc1";

    private final MemoryDataStorage storage = new MemoryDataStorage();
    private final com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager playerManager =
            TestAudioSource.newPlayerManager();

    private com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager spiedPlayerManager;
    private ScheduledExecutorService scheduler;
    private MusicPlugin plugin;
    private MusicManager manager;
    private Guild guild;
    private AudioManager audioManager;
    private JDA jda;
    private User selfUser;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();

        audioManager = mock(AudioManager.class);
        when(audioManager.isConnected()).thenReturn(true);
        AudioChannelUnion connected = mock(AudioChannelUnion.class);
        when(connected.getId()).thenReturn(VOICE_ID);
        when(audioManager.getConnectedChannel()).thenReturn(connected);

        selfUser = mock(User.class);
        net.dv8tion.jda.api.entities.SelfUser self = mock(net.dv8tion.jda.api.entities.SelfUser.class);
        jda = mock(JDA.class);
        when(jda.getSelfUser()).thenReturn(self);

        guild = guild(GUILD_ID);

        AudioService audioService = mock(AudioService.class);
        PluginContext context = mock(PluginContext.class);
        when(context.getAudioService()).thenReturn(audioService);

        // Mockito forbids creating and stubbing a mock inside an ongoing when(...).
        PluginLanguageAdapter language = languageAdapter();
        fr.farmvivi.fluxcord.plugins.music.audio.AudioPlayerManager sources = sourceRegistry();

        plugin = mock(MusicPlugin.class);
        when(plugin.getLanguage()).thenReturn(language);
        when(plugin.getStorage()).thenReturn(new PluginDataStorageAdapter("music-plugin", storage));
        when(plugin.getScheduler()).thenReturn(scheduler);
        when(plugin.getContext()).thenReturn(context);
        when(plugin.isPersistenceEnabled()).thenReturn(true);
        when(plugin.getPersistenceTtlMillis()).thenReturn(3_600_000L);
        when(plugin.getAutoLeaveTimeoutMs()).thenReturn(60_000L);

        manager = new MusicManager(plugin, sources);
        when(plugin.getMusicManager()).thenReturn(manager);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        playerManager.shutdown();
    }

    /**
     * The real registry, except that {@code createPlayer()} hands out scripted players: a real one
     * would try to decode the test tracks, fail, and drain the queue on its own.
     */
    private fr.farmvivi.fluxcord.plugins.music.audio.AudioPlayerManager sourceRegistry() {
        com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager spied = spy(playerManager);
        spiedPlayerManager = spied;
        doAnswer(invocation -> ScriptedAudioPlayer.create()).when(spied).createPlayer();

        fr.farmvivi.fluxcord.plugins.music.audio.AudioPlayerManager sources =
                mock(fr.farmvivi.fluxcord.plugins.music.audio.AudioPlayerManager.class);
        when(sources.getPlayerManager()).thenReturn(spied);
        return sources;
    }

    private PluginLanguageAdapter languageAdapter() {
        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(any(Locale.class), anyString())).thenAnswer(i -> i.getArgument(1));
        when(language.getString(any(Locale.class), anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(1));
        return language;
    }

    private Guild guild(String id) {
        net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel voiceChannel =
                mock(net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel.class);
        Guild g = mock(Guild.class);
        when(g.getId()).thenReturn(id);
        when(g.getIdLong()).thenReturn(Long.parseLong(id));
        when(g.getName()).thenReturn("guild-" + id);
        when(g.getAudioManager()).thenReturn(audioManager);
        when(g.getJDA()).thenReturn(jda);
        when(g.getVoiceChannelById(VOICE_ID)).thenReturn(voiceChannel);
        return g;
    }

    private void storeState(String guildId, PlaybackState state) {
        storage.set(StorageKey.guild(guildId, "music-plugin." + MusicPlayer.STATE_KEY), state.toMap());
    }

    private PlaybackState resumableState() {
        PlaybackState state = new PlaybackState();
        state.setVoiceChannelId(VOICE_ID);
        state.setCurrentTrack(TrackCodec.encode(playerManager, TestAudioSource.trackOf(playerManager, "a", 1000)));
        state.setSavedAt(System.currentTimeMillis());
        return state;
    }

    @Test
    void thereIsOnePlayerPerGuildAndFindingOneNeverCreatesIt() {
        assertTrue(manager.findPlayer(GUILD_ID).isEmpty(), "autocomplete must not create a player");

        MusicPlayer player = manager.getPlayer(guild);

        assertSame(player, manager.getPlayer(guild), "cached per guild");
        assertSame(player, manager.findPlayer(GUILD_ID).orElseThrow());
        assertNotSame(player, manager.getPlayer(guild("200")));
    }

    @Test
    void findPlayerToleratesAnIdThatIsNotANumber() {
        assertTrue(manager.findPlayer("not-an-id").isEmpty());
        assertTrue(manager.getRecentTracks("not-an-id").isEmpty());
    }

    @Test
    void destroyingAPlayerForgetsIt() {
        manager.getPlayer(guild);

        manager.destroyPlayer(guild);

        assertTrue(manager.findPlayer(GUILD_ID).isEmpty());
        assertDoesNotThrow(() -> manager.destroyPlayer(guild), "destroying twice is harmless");
    }

    @Test
    void onlyTheBotsOwnVoiceUpdatesAreHandled() {
        ScheduledExecutorService scheduledMock = mock(ScheduledExecutorService.class);
        when(plugin.getScheduler()).thenReturn(scheduledMock);
        manager.getPlayer(guild);
        GuildVoiceUpdateEvent event = voiceUpdate(false, null, mock(AudioChannelUnion.class));

        manager.handleVoiceUpdate(event);

        verify(scheduledMock, never()).schedule(any(Runnable.class), anyLong(), any());
    }

    @Test
    void leavingAVoiceChannelOnlyConfirmsTheDisconnectAfterAGracePeriod() {
        ScheduledExecutorService scheduledMock = mock(ScheduledExecutorService.class);
        when(plugin.getScheduler()).thenReturn(scheduledMock);
        manager.getPlayer(guild);

        manager.handleVoiceUpdate(voiceUpdate(true, null, mock(AudioChannelUnion.class)));

        verify(scheduledMock).schedule(any(Runnable.class), eq(5L), eq(TimeUnit.SECONDS));
    }

    @Test
    void anUpdateForAGuildWithoutAPlayerIsIgnored() {
        assertDoesNotThrow(() -> manager.handleVoiceUpdate(voiceUpdate(true, null, mock(AudioChannelUnion.class))));
    }

    @Test
    void restoringIsSkippedWhenPersistenceIsOff() {
        when(plugin.isPersistenceEnabled()).thenReturn(false);
        storeState(GUILD_ID, resumableState());
        when(jda.getGuilds()).thenReturn(List.of(guild));

        manager.restoreAllStates(jda);

        assertTrue(manager.findPlayer(GUILD_ID).isEmpty());
    }

    @Test
    void aGuildWithoutAStoredStateIsLeftAlone() {
        when(jda.getGuilds()).thenReturn(List.of(guild));

        manager.restoreAllStates(jda);

        assertTrue(manager.findPlayer(GUILD_ID).isEmpty());
    }

    @Test
    void aResumableStateBringsThePlayerBack() {
        storeState(GUILD_ID, resumableState());
        when(jda.getGuilds()).thenReturn(List.of(guild));

        manager.restoreAllStates(jda);

        MusicPlayer player = manager.findPlayer(GUILD_ID).orElseThrow();
        assertNotNull(player.getPlayingTrack(), "the saved track is playing again");
    }

    @Test
    void anExpiredStateIsDiscardedInsteadOfRejoiningHoursLater() {
        PlaybackState state = resumableState();
        state.setSavedAt(System.currentTimeMillis() - 7_200_000L);
        storeState(GUILD_ID, state);
        when(jda.getGuilds()).thenReturn(List.of(guild));

        manager.restoreAllStates(jda);

        assertTrue(manager.findPlayer(GUILD_ID).isEmpty());
        assertTrue(storage.scope(StorageKey.guildScope(GUILD_ID)).isEmpty(), "the stale entry is dropped");
    }

    @Test
    void aStateWithNothingToPlayIsDropped() {
        PlaybackState empty = new PlaybackState();
        empty.setVoiceChannelId(VOICE_ID);
        empty.setSavedAt(System.currentTimeMillis());
        storeState(GUILD_ID, empty);
        when(jda.getGuilds()).thenReturn(List.of(guild));

        manager.restoreAllStates(jda);

        assertTrue(manager.findPlayer(GUILD_ID).isEmpty());
        assertTrue(storage.scope(StorageKey.guildScope(GUILD_ID)).isEmpty());
    }

    @Test
    void savingEveryStateGoesThroughEveryPlayer() {
        manager.getPlayer(guild).playTrack(TestAudioSource.trackOf(playerManager, "a", 1000));

        manager.saveAllStates();

        assertFalse(storage.scope(StorageKey.guildScope(GUILD_ID)).isEmpty());
    }

    @Test
    void loadingASavedPlaylistQueuesEveryTrackAndRemembersThem() {
        Playlist playlist = new Playlist("mix", "u1", fr.farmvivi.fluxcord.plugins.music.playlist.PlaylistScope.USER);
        playlist.setTracks(List.of(
                new Playlist.PlaylistTrack("first", "first", "author", 1000),
                new Playlist.PlaylistTrack("second", "second", "author", 1000)));
        CommandContext ctx = commandContext();

        manager.loadPlaylist(ctx, playlist);

        verify(ctx, timeout(3000)).replyEmbed(any());
        MusicPlayer player = manager.findPlayer(GUILD_ID).orElseThrow();
        assertEquals("first", player.getPlayingTrack().getInfo().title, "queued in the order they were saved");
        assertEquals(1, player.getTrackScheduler().getQueueSize(), "the second one waits its turn");
        assertTrue(manager.getRecentTracks(GUILD_ID).keySet().containsAll(java.util.Set.of("first", "second")),
                "loaded tracks feed /play autocomplete");
    }

    @Test
    void loadingAPlaylistOutsideAGuildIsRefused() {
        CommandContext ctx = commandContext();
        when(ctx.getGuild()).thenReturn(Optional.empty());

        manager.loadPlaylist(ctx, new Playlist("mix", "u1", fr.farmvivi.fluxcord.plugins.music.playlist.PlaylistScope.USER));

        verify(ctx).replyError("music.error.guild_only");
        verify(ctx, never()).deferReply();
    }

    private CommandContext commandContext() {
        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getGuild()).thenReturn(Optional.of(guild));
        when(ctx.getLocale()).thenReturn(Locale.ENGLISH);
        when(ctx.getChannel()).thenReturn(mock(MessageChannel.class, withSettings().defaultAnswer(invocation ->
                invocation.getMethod().getReturnType() == long.class ? 1L : null)));
        return ctx;
    }

    private GuildVoiceUpdateEvent voiceUpdate(boolean self, AudioChannelUnion joined, AudioChannelUnion left) {
        GuildVoiceUpdateEvent event = mock(GuildVoiceUpdateEvent.class);
        Member member = mock(Member.class);
        User user = self ? jda.getSelfUser() : selfUser;
        when(member.getUser()).thenReturn(user);
        when(event.getMember()).thenReturn(member);
        when(event.getJDA()).thenReturn(jda);
        when(event.getGuild()).thenReturn(guild);
        when(event.getChannelJoined()).thenReturn(joined);
        when(event.getChannelLeft()).thenReturn(left);
        return event;
    }

    @Test
    void shutdownReleasesEveryPlayerWithoutLosingTheirState() {
        manager.getPlayer(guild).playTrack(TestAudioSource.trackOf(playerManager, "a", 1000));
        Map<String, Object> before = storage.scope(StorageKey.guildScope(GUILD_ID));
        assertFalse(before.isEmpty());

        manager.shutdown();

        assertFalse(storage.scope(StorageKey.guildScope(GUILD_ID)).isEmpty(),
                "a graceful shutdown must leave something to resume");
    }

    // Loading a query: what the user is told, and what reaches the player

    /** A command context in this guild, already connected, whose replies the test can observe. */
    private CommandContext loadContext() {
        // A real loadItemOrdered goes looking for the query, fails, and calls the handler back
        // asynchronously - on top of whatever outcome the test drives explicitly. Only these tests
        // neutralise it: the /playlist load test relies on the real one.
        doAnswer(invocation -> null).when(spiedPlayerManager).loadItemOrdered(any(), anyString(), any());
        CommandContext ctx = mock(CommandContext.class);
        MessageChannel channel = mock(MessageChannel.class);
        when(ctx.getGuild()).thenReturn(Optional.of(guild));
        when(ctx.getChannel()).thenReturn(channel);
        when(ctx.getLocale()).thenReturn(Locale.FRANCE);
        return ctx;
    }

    /** Runs a load and returns the handler the manager gave LavaPlayer, so its outcomes can be driven. */
    private AudioLoadResultHandler handlerFor(CommandContext ctx, String query, boolean playNow) {
        manager.loadTrack(ctx, query, playNow);

        ArgumentCaptor<AudioLoadResultHandler> captor = ArgumentCaptor.forClass(AudioLoadResultHandler.class);
        verify(spiedPlayerManager).loadItemOrdered(any(), eq(query), captor.capture());
        return captor.getValue();
    }

    private AudioTrack testTrack(String title) {
        return TestAudioSource.trackOf(playerManager, title, 120_000);
    }

    @Test
    void loadingDefersTheReplyBecauseAQueryCanTakeSeconds() {
        CommandContext ctx = loadContext();

        manager.loadTrack(ctx, "ytsearch:a song", false);

        verify(ctx).deferReply();
    }

    @Test
    void aLoadedTrackIsAnnouncedAndStartsWhenNothingIsPlaying() {
        CommandContext ctx = loadContext();
        MusicPlayer player = manager.getPlayer(guild);

        handlerFor(ctx, "ytsearch:a song", false).trackLoaded(testTrack("A Song"));

        verify(ctx).replyEmbed(any(EmbedBuilder.class));
        assertNotNull(player.getPlayingTrack());
        assertEquals(0, player.getTrackScheduler().getQueueSize());
    }

    @Test
    void aSecondTrackWaitsItsTurn() {
        CommandContext ctx = loadContext();
        MusicPlayer player = manager.getPlayer(guild);

        handlerFor(ctx, "ytsearch:one", false).trackLoaded(testTrack("One"));
        reset(spiedPlayerManager);
        handlerFor(ctx, "ytsearch:two", false).trackLoaded(testTrack("Two"));

        assertEquals(1, player.getTrackScheduler().getQueueSize());
    }

    @Test
    void aTrackAskedForNowJumpsTheQueue() {
        CommandContext ctx = loadContext();
        MusicPlayer player = manager.getPlayer(guild);
        AudioTrack urgent = testTrack("Urgent");
        handlerFor(ctx, "ytsearch:queued", false).trackLoaded(testTrack("Queued"));
        reset(spiedPlayerManager);

        handlerFor(ctx, "ytsearch:urgent", true).trackLoaded(urgent);

        assertEquals(urgent, player.getPlayingTrack(), "played at once rather than appended");
    }

    @Test
    void aSearchResultPlaysItsFirstHitRatherThanTheWholeList() {
        // A search answers with a playlist; queueing all of it would add fifty tracks for one request.
        CommandContext ctx = loadContext();
        AudioTrack first = testTrack("First");
        AudioPlaylist search = mock(AudioPlaylist.class);
        when(search.isSearchResult()).thenReturn(true);
        when(search.getTracks()).thenReturn(List.of(first, testTrack("Second")));
        MusicPlayer player = manager.getPlayer(guild);

        handlerFor(ctx, "ytsearch:a song", false).playlistLoaded(search);

        assertEquals(first, player.getPlayingTrack());
        assertEquals(0, player.getTrackScheduler().getQueueSize(), "the other hits are dropped");
    }

    @Test
    void aRealPlaylistIsQueuedWholeAndAnnouncedOnce() {
        CommandContext ctx = loadContext();
        AudioPlaylist album = mock(AudioPlaylist.class);
        when(album.isSearchResult()).thenReturn(false);
        when(album.getName()).thenReturn("An Album");
        when(album.getTracks()).thenReturn(List.of(testTrack("One"), testTrack("Two"), testTrack("Three")));
        MusicPlayer player = manager.getPlayer(guild);

        handlerFor(ctx, "https://example.com/album", false).playlistLoaded(album);

        verify(ctx, times(1)).replyEmbed(any(EmbedBuilder.class));
        assertEquals(2, player.getTrackScheduler().getQueueSize(), "one playing, two waiting");
    }

    @Test
    void onlyTheFirstTrackOfAPlaylistJumpsTheQueue() {
        // The loop used to identify the first track with indexOf, which is quadratic and would have
        // jumped the queue again for a playlist holding the same track twice.
        CommandContext ctx = loadContext();
        AudioTrack repeated = testTrack("Repeated");
        AudioPlaylist album = mock(AudioPlaylist.class);
        when(album.isSearchResult()).thenReturn(false);
        when(album.getName()).thenReturn("Album");
        when(album.getTracks()).thenReturn(List.of(repeated, testTrack("Other"), repeated));
        MusicPlayer player = manager.getPlayer(guild);

        handlerFor(ctx, "https://example.com/album", true).playlistLoaded(album);

        assertEquals(repeated, player.getPlayingTrack());
        assertEquals(2, player.getTrackScheduler().getQueueSize(), "the repeat waits like any other track");
    }

    @Test
    void anEmptySearchResultDoesNotFail() {
        CommandContext ctx = loadContext();
        AudioPlaylist empty = mock(AudioPlaylist.class);
        when(empty.isSearchResult()).thenReturn(true);
        when(empty.getName()).thenReturn("Nothing");
        when(empty.getTracks()).thenReturn(List.of());

        assertDoesNotThrow(() -> handlerFor(ctx, "ytsearch:nothing", false).playlistLoaded(empty));
    }

    @Test
    void nothingFoundTellsTheUserInsteadOfStayingSilent() {
        CommandContext ctx = loadContext();

        handlerFor(ctx, "ytsearch:zzzz", false).noMatches();

        verify(ctx).replyError("music.error.no_matches");
    }

    @Test
    void aFailedLoadReportsTheProvidersReason() {
        CommandContext ctx = loadContext();

        handlerFor(ctx, "https://example.com/gone", false).loadFailed(new FriendlyException(
                "video unavailable", FriendlyException.Severity.COMMON, null));

        verify(ctx).replyError("music.error.load_failed");
    }

    @Test
    void loadingOutsideAGuildNeverReachesTheProvider() {
        CommandContext ctx = mock(CommandContext.class);
        when(ctx.getGuild()).thenReturn(Optional.empty());
        // any(Locale.class) does not match null in Mockito, so an unstubbed locale would make the
        // language adapter return null and this assertion unreadable.
        when(ctx.getLocale()).thenReturn(Locale.FRANCE);

        manager.loadTrack(ctx, "ytsearch:a song", false);

        verify(ctx).replyError("music.error.guild_only");
        verify(ctx, never()).deferReply();
        verify(spiedPlayerManager, never()).loadItemOrdered(any(), anyString(), any());
    }
}
