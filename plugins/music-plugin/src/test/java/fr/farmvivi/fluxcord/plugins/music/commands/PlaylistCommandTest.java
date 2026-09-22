package fr.farmvivi.fluxcord.plugins.music.commands;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.plugins.music.MusicManager;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.player.TrackScheduler;
import fr.farmvivi.fluxcord.plugins.music.playlist.Playlist;
import fr.farmvivi.fluxcord.plugins.music.playlist.PlaylistManager;
import fr.farmvivi.fluxcord.plugins.music.playlist.PlaylistScope;
import fr.farmvivi.fluxcord.plugins.music.testing.MemoryDataStorage;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@code /playlist}: action routing, the personal/server split and the admin gate on server
 * playlists. The language adapter answers with the key itself, so the assertions name the message
 * that would be shown.
 */
class PlaylistCommandTest {

    private static final String GUILD_ID = "g1";
    private static final String USER_ID = "u1";
    private static final String ADMIN_PERMISSION = "music-plugin.admin";

    private MusicPlugin plugin;
    private MusicManager musicManager;
    private MusicPlayer player;
    private TrackScheduler scheduler;
    private PlaylistManager playlists;
    private PluginPermissionAdapter permissions;
    private CommandContext ctx;
    private PlaylistCommand command;

    @BeforeEach
    void setUp() {
        playlists = new PlaylistManager(new PluginDataStorageAdapter("music-plugin", new MemoryDataStorage()), 5, 5, 10);

        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(any(Locale.class), anyString())).thenAnswer(i -> i.getArgument(1));
        when(language.getString(any(Locale.class), anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(1));

        permissions = mock(PluginPermissionAdapter.class);
        scheduler = mock(TrackScheduler.class);
        when(scheduler.getQueue()).thenReturn(List.of());
        player = mock(MusicPlayer.class);
        when(player.getTrackScheduler()).thenReturn(scheduler);
        musicManager = mock(MusicManager.class);
        when(musicManager.getPlayer(any())).thenReturn(player);

        plugin = mock(MusicPlugin.class);
        when(plugin.getLanguage()).thenReturn(language);
        when(plugin.getPermissions()).thenReturn(permissions);
        when(plugin.getMusicManager()).thenReturn(musicManager);
        when(plugin.getPlaylistManager()).thenReturn(playlists);
        when(plugin.permissionKey("admin")).thenReturn(ADMIN_PERMISSION);

        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        User user = mock(User.class);
        when(user.getId()).thenReturn(USER_ID);
        ctx = mock(CommandContext.class);
        when(ctx.getGuild()).thenReturn(Optional.of(guild));
        when(ctx.getUser()).thenReturn(user);
        when(ctx.getLocale()).thenReturn(Locale.ENGLISH);

        command = new PlaylistCommand(plugin);
    }

    private AudioTrack track(String title) {
        AudioTrack track = mock(AudioTrack.class);
        when(track.getInfo()).thenReturn(
                new AudioTrackInfo(title, "author", 1000, title, false, "https://example.test/" + title));
        when(track.getDuration()).thenReturn(1000L);
        return track;
    }

    private String lastError() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(ctx, atLeastOnce()).replyError(captor.capture());
        return captor.getValue();
    }

    private String lastSuccess() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(ctx, atLeastOnce()).replySuccess(captor.capture());
        return captor.getValue();
    }

    @Test
    void savingStoresTheCurrentTrackThenTheQueue() {
        // Mockito forbids creating a mock inside an ongoing when(...), so build the tracks first.
        AudioTrack current = track("current");
        AudioTrack next = track("next");
        when(player.getPlayingTrack()).thenReturn(current);
        when(scheduler.getQueue()).thenReturn(List.of(next));

        command.execute(ctx, "save", "road trip", null);

        assertEquals("music.playlist.saved", lastSuccess());
        Playlist saved = playlists.find(PlaylistScope.USER, USER_ID, "road trip").orElseThrow();
        assertEquals(List.of("current", "next"), saved.getTracks().stream().map(Playlist.PlaylistTrack::title).toList());
        assertTrue(playlists.find(PlaylistScope.GUILD, GUILD_ID, "road trip").isEmpty(), "personal by default");
    }

    @Test
    void savingAnEmptyPlayerIsRefused() {
        when(player.getPlayingTrack()).thenReturn(null);

        command.execute(ctx, "save", "empty", null);

        assertEquals("music.playlist.nothing_to_save", lastError());
    }

    @Test
    void aServerPlaylistNeedsTheAdminPermission() {
        AudioTrack current = track("a");
        when(player.getPlayingTrack()).thenReturn(current);
        when(permissions.hasPermission(USER_ID, GUILD_ID, ADMIN_PERMISSION)).thenReturn(false);

        command.execute(ctx, "save", "party", "server");

        assertEquals("music.playlist.admin_only", lastError());
        assertTrue(playlists.find(PlaylistScope.GUILD, GUILD_ID, "party").isEmpty());

        when(permissions.hasPermission(USER_ID, GUILD_ID, ADMIN_PERMISSION)).thenReturn(true);
        command.execute(ctx, "save", "party", "server");

        assertTrue(playlists.find(PlaylistScope.GUILD, GUILD_ID, "party").isPresent());
    }

    @Test
    void readingAServerPlaylistNeedsNoPermission() {
        playlists.save(PlaylistScope.GUILD, GUILD_ID, "party", List.of(
                new Playlist.PlaylistTrack("https://example.test/a", "a", "author", 1000)));

        command.execute(ctx, "load", "party", "server");

        verify(musicManager).loadPlaylist(same(ctx), argThat(playlist -> "party".equals(playlist.getName())));
        verify(permissions, never()).hasPermission(anyString(), anyString(), anyString());
    }

    @Test
    void loadingAnUnknownPlaylistReportsIt() {
        command.execute(ctx, "load", "nope", null);

        assertEquals("music.playlist.not_found", lastError());
        verify(musicManager, never()).loadPlaylist(any(), any());
    }

    @Test
    void deletingRemovesThePlaylist() {
        playlists.save(PlaylistScope.USER, USER_ID, "mix", List.of(
                new Playlist.PlaylistTrack("https://example.test/a", "a", "author", 1000)));

        command.execute(ctx, "delete", "mix", "personal");

        assertEquals("music.playlist.deleted", lastSuccess());
        assertTrue(playlists.find(PlaylistScope.USER, USER_ID, "mix").isEmpty());
    }

    @Test
    void listingAndShowingReplyWithAnEmbed() {
        playlists.save(PlaylistScope.USER, USER_ID, "mix", List.of(
                new Playlist.PlaylistTrack("https://example.test/a", "a", "author", 1000)));

        command.execute(ctx, "list", null, null);
        command.execute(ctx, "show", "mix", null);

        verify(ctx, times(2)).replyEmbed(any());
    }

    @Test
    void listingWithoutAnyPlaylistSaysSo() {
        command.execute(ctx, "list", null, null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(ctx).replyInfo(captor.capture());
        assertEquals("music.playlist.none", captor.getValue());
    }

    @Test
    void anUnknownActionShowsTheUsage() {
        command.execute(ctx, "sing", null, null);
        assertEquals("music.playlist.usage", lastError());
    }

    @Test
    void theCommandIsRefusedOutsideAGuildAndFromTheConsole() {
        when(ctx.getGuild()).thenReturn(Optional.empty());
        command.execute(ctx, "list", null, null);
        assertEquals("music.error.guild_only", lastError());

        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        when(ctx.getGuild()).thenReturn(Optional.of(guild));
        when(ctx.getUser()).thenReturn(null);
        command.execute(ctx, "list", null, null);
        assertEquals("music.playlist.discord_only", lastError());
    }
}
