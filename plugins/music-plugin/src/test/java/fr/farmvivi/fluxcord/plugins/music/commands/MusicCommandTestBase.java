package fr.farmvivi.fluxcord.plugins.music.commands;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.plugins.music.MusicManager;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.player.TrackScheduler;
import fr.farmvivi.fluxcord.plugins.music.ui.MusicPlayerMessage;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Fixture shared by the music command tests: a plugin whose language adapter answers with the key
 * itself (so an assertion names the message the user would see), a mocked player and a command
 * context sent from a guild by a user holding every permission.
 */
abstract class MusicCommandTestBase {

    protected static final String PLUGIN_ID = "music-plugin";
    protected static final String GUILD_ID = "g1";
    protected static final String USER_ID = "u1";

    protected MusicPlugin plugin;
    protected MusicManager musicManager;
    protected MusicPlayer player;
    protected TrackScheduler scheduler;
    protected MusicPlayerMessage playerMessage;
    protected PluginPermissionAdapter permissions;
    protected CommandContext ctx;
    protected Guild guild;

    @BeforeEach
    void setUpFixture() {
        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(any(Locale.class), anyString())).thenAnswer(i -> i.getArgument(1));
        when(language.getString(any(Locale.class), anyString(), any(Object[].class))).thenAnswer(i -> i.getArgument(1));

        permissions = mock(PluginPermissionAdapter.class);
        when(permissions.hasPermission(anyString(), anyString(), anyString())).thenReturn(true);
        when(permissions.hasPermission(anyString(), anyString())).thenReturn(true);

        guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);

        scheduler = mock(TrackScheduler.class);
        when(scheduler.getQueue()).thenReturn(List.of());
        playerMessage = mock(MusicPlayerMessage.class);
        player = mock(MusicPlayer.class);
        when(player.getGuild()).thenReturn(guild);
        when(player.getTrackScheduler()).thenReturn(scheduler);
        when(player.getPlayerMessage()).thenReturn(playerMessage);

        musicManager = mock(MusicManager.class);
        when(musicManager.getPlayer(any())).thenReturn(player);

        plugin = mock(MusicPlugin.class);
        when(plugin.getId()).thenReturn(PLUGIN_ID);
        when(plugin.getLanguage()).thenReturn(language);
        when(plugin.getPermissions()).thenReturn(permissions);
        when(plugin.getMusicManager()).thenReturn(musicManager);

        User user = mock(User.class);
        when(user.getId()).thenReturn(USER_ID);
        ctx = mock(CommandContext.class);
        when(ctx.getGuild()).thenReturn(Optional.of(guild));
        when(ctx.getUser()).thenReturn(user);
        when(ctx.getLocale()).thenReturn(Locale.ENGLISH);
    }

    /** A track mock; {@code duration} is also what {@code getInfo().length} reports. */
    protected AudioTrack track(String title, long duration) {
        AudioTrack track = mock(AudioTrack.class);
        when(track.getInfo()).thenReturn(
                new AudioTrackInfo(title, "author", duration, title, false, "https://example.test/" + title,
                        "https://example.test/" + title + ".png", null));
        when(track.getDuration()).thenReturn(duration);
        when(track.isSeekable()).thenReturn(true);
        return track;
    }

    protected AudioTrack playing(String title, long duration) {
        AudioTrack track = track(title, duration);
        when(player.getPlayingTrack()).thenReturn(track);
        return track;
    }

    protected void queued(AudioTrack... tracks) {
        when(scheduler.getQueue()).thenReturn(List.of(tracks));
        when(scheduler.getQueueSize()).thenReturn(tracks.length);
    }

    protected String lastError() {
        return capture(ArgumentCaptor.forClass(String.class), Reply.ERROR);
    }

    protected String lastSuccess() {
        return capture(ArgumentCaptor.forClass(String.class), Reply.SUCCESS);
    }

    protected String lastInfo() {
        return capture(ArgumentCaptor.forClass(String.class), Reply.INFO);
    }

    protected EmbedBuilder lastEmbed() {
        ArgumentCaptor<EmbedBuilder> captor = ArgumentCaptor.forClass(EmbedBuilder.class);
        verify(ctx, atLeastOnce()).replyEmbed(captor.capture());
        return captor.getValue();
    }

    private enum Reply {ERROR, SUCCESS, INFO}

    private String capture(ArgumentCaptor<String> captor, Reply kind) {
        CommandContext verified = verify(ctx, atLeastOnce());
        switch (kind) {
            case ERROR -> verified.replyError(captor.capture());
            case SUCCESS -> verified.replySuccess(captor.capture());
            case INFO -> verified.replyInfo(captor.capture());
        }
        return captor.getValue();
    }
}
