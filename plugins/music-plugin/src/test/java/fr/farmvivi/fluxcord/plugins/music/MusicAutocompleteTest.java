package fr.farmvivi.fluxcord.plugins.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.command.CommandBuilder;
import fr.farmvivi.fluxcord.api.command.PluginCommandAdapter;
import fr.farmvivi.fluxcord.api.command.option.AutocompleteContext;
import fr.farmvivi.fluxcord.api.command.option.AutocompleteProvider;
import fr.farmvivi.fluxcord.api.command.option.OptionChoice;
import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.discord.DiscordAPI;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.plugin.PluginContext;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.player.TrackScheduler;
import fr.farmvivi.fluxcord.plugins.music.playlist.Playlist;
import fr.farmvivi.fluxcord.plugins.music.playlist.PlaylistScope;
import fr.farmvivi.fluxcord.plugins.music.testing.MemoryDataStorage;
import fr.farmvivi.fluxcord.plugins.music.utils.TimeParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The autocomplete providers {@link MusicPlugin} attaches to its options. They are reached the way
 * Discord reaches them: through the option the plugin registered, so a provider wired to the wrong
 * option would show up here.
 */
class MusicAutocompleteTest {

    private static final String GUILD_ID = "100";
    private static final String USER_ID = "u1";

    @TempDir Path dataFolder;

    private MusicPlugin plugin;
    private PluginCommandAdapter commands;
    private MusicManager musicManager;
    private MusicPlayer player;
    private TrackScheduler scheduler;

    @BeforeEach
    void setUp() {
        commands = mock(PluginCommandAdapter.class);

        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(anyString())).thenAnswer(i -> i.getArgument(0));

        Configuration configuration = mock(Configuration.class);
        when(configuration.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(configuration.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));

        PluginContext context = mock(PluginContext.class);
        when(context.getPluginId()).thenReturn("music-plugin");
        when(context.getPluginName()).thenReturn("Fluxcord Plugin - Music");
        when(context.getPluginVersion()).thenReturn("3.0.0-TEST");
        when(context.getLogger()).thenReturn(LoggerFactory.getLogger("music-plugin-test"));
        when(context.getEventManager()).thenReturn(mock(EventManager.class));
        when(context.getDiscordAPI()).thenReturn(mock(DiscordAPI.class));
        when(context.getConfiguration()).thenReturn(configuration);
        when(context.getDataFolder()).thenReturn(dataFolder.toString());
        when(context.getAudioService()).thenReturn(mock(AudioService.class));
        when(context.getCommands()).thenReturn(commands);
        when(context.getPermissions()).thenReturn(mock(PluginPermissionAdapter.class));
        when(context.getLanguage()).thenReturn(language);
        when(context.getStorage()).thenReturn(new PluginDataStorageAdapter("music-plugin", new MemoryDataStorage()));

        plugin = new MusicPlugin();
        plugin.onLoad(context);
        plugin.onEnable();

        // Replace the managers with mocks so the providers see a scripted player.
        scheduler = mock(TrackScheduler.class);
        when(scheduler.getQueue()).thenReturn(List.of());
        player = mock(MusicPlayer.class);
        when(player.getTrackScheduler()).thenReturn(scheduler);
        musicManager = mock(MusicManager.class);
        when(musicManager.getRecentTracks(anyString())).thenReturn(Map.of());
        when(musicManager.findPlayer(anyString())).thenReturn(Optional.of(player));
        inject("musicManager", musicManager);
    }

    @AfterEach
    void tearDown() {
        plugin.onDisable();
    }

    private void inject(String field, Object value) {
        try {
            java.lang.reflect.Field f = MusicPlugin.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(plugin, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Runs every registration and returns the autocomplete provider attached to {@code option} of
     * {@code command}, as Discord would use it.
     */
    @SuppressWarnings("unchecked")
    private <T> AutocompleteProvider<T> providerOf(String command, String option) {
        ArgumentCaptor<Consumer<CommandBuilder>> registrations = ArgumentCaptor.forClass(Consumer.class);
        verify(commands, atLeastOnce()).registerCommand(registrations.capture());

        for (Consumer<CommandBuilder> registration : registrations.getAllValues()) {
            Map<String, Object> options = new HashMap<>();
            String[] name = new String[1];
            CommandBuilder builder = mock(CommandBuilder.class, invocation -> {
                if ("name".equals(invocation.getMethod().getName())) {
                    name[0] = invocation.getArgument(0);
                } else if (invocation.getMethod().getName().endsWith("Option")) {
                    for (Object argument : invocation.getArguments()) {
                        if (argument instanceof AutocompleteProvider<?> provider) {
                            options.put(invocation.getArgument(0), provider);
                        }
                    }
                }
                return CommandBuilder.class.isAssignableFrom(invocation.getMethod().getReturnType())
                        ? invocation.getMock() : null;
            });
            registration.accept(builder);
            if (command.equals(name[0]) && options.containsKey(option)) {
                return (AutocompleteProvider<T>) options.get(option);
            }
        }
        return fail("no autocomplete provider on " + command + " " + option);
    }

    private AutocompleteContext typed(String partial) {
        return new AutocompleteContext(partial, GUILD_ID, USER_ID, Map.of());
    }

    private AudioTrack track(String title, long duration) {
        AudioTrack track = mock(AudioTrack.class);
        when(track.getInfo()).thenReturn(new AudioTrackInfo(title, "author", duration, title, false, "uri:" + title));
        when(track.getDuration()).thenReturn(duration);
        return track;
    }

    @Test
    void playSuggestsTheTracksRecentlyPlayedHere() {
        when(musicManager.getRecentTracks(GUILD_ID)).thenReturn(new java.util.LinkedHashMap<>(Map.of(
                "Never Gonna", "uri:1")));

        List<OptionChoice<String>> choices = this.<String>providerOf("play", "query").suggest(typed("never"));

        assertEquals(1, choices.size());
        assertEquals("Never Gonna", choices.get(0).name());
        assertEquals("uri:1", choices.get(0).value());
        assertTrue(this.<String>providerOf("play", "query").suggest(typed("zzz")).isEmpty(), "filtered by what is typed");
        assertTrue(this.<String>providerOf("play", "query")
                .suggest(new AutocompleteContext("", null, USER_ID, Map.of())).isEmpty(), "no guild, no history");
    }

    @Test
    void seekSuggestsPositionsTheParserAccepts() {
        AudioTrack current = track("a", 200_000);
        when(player.getPlayingTrack()).thenReturn(current);

        List<OptionChoice<String>> choices = this.<String>providerOf("seek", "time").suggest(typed(""));

        assertFalse(choices.isEmpty());
        for (OptionChoice<String> choice : choices) {
            assertTrue(TimeParser.parseTime(choice.value()) >= 0,
                    "a suggestion the user picks must be accepted by /seek: " + choice.value());
        }
        assertEquals("0:00", choices.get(0).value(), "the start of the track comes first");
    }

    @Test
    void seekSuggestsNothingForALiveStream() {
        AudioTrack live = track("live", Long.MAX_VALUE);
        when(player.getPlayingTrack()).thenReturn(live);

        assertTrue(this.<String>providerOf("seek", "time").suggest(typed("")).isEmpty());
    }

    @Test
    void removeSuggestsTheQueuedTracksByPosition() {
        List<AudioTrack> queue = List.of(track("first", 1000), track("second", 1000));
        when(scheduler.getQueue()).thenReturn(queue);

        List<OptionChoice<Integer>> choices = this.<Integer>providerOf("remove", "position").suggest(typed(""));

        assertEquals(List.of("1. first", "2. second"), choices.stream().map(OptionChoice::name).toList());
        assertEquals(List.of(1, 2), choices.stream().map(OptionChoice::value).toList());
        assertEquals(List.of(2), this.<Integer>providerOf("remove", "position").suggest(typed("second")).stream()
                .map(OptionChoice::value).toList());
    }

    @Test
    void queueSuggestsTheExistingPages() {
        when(scheduler.getQueueSize()).thenReturn(23);

        List<OptionChoice<Integer>> choices = this.<Integer>providerOf("queue", "page").suggest(typed(""));

        assertEquals(List.of(1, 2, 3), choices.stream().map(OptionChoice::value).toList());
        assertEquals("1 / 3", choices.get(0).name());
    }

    @Test
    void volumeSuggestsTheCurrentLevelFirstThenTheUsualSteps() {
        when(player.getVolume()).thenReturn(42);

        List<OptionChoice<Integer>> choices = this.<Integer>providerOf("volume", "level").suggest(typed(""));

        assertEquals(42, choices.get(0).value());
        assertTrue(choices.get(0).name().contains("current"));
        assertTrue(choices.stream().map(OptionChoice::value).toList().containsAll(List.of(100, 75, 50, 25, 10, 0)));
    }

    @Test
    void playlistSuggestsTheCallersPlaylistsOrTheServerOnes() {
        plugin.getPlaylistManager().save(PlaylistScope.USER, USER_ID, "mine",
                List.of(new Playlist.PlaylistTrack("u", "t", "a", 1)));
        plugin.getPlaylistManager().save(PlaylistScope.GUILD, GUILD_ID, "ours",
                List.of(new Playlist.PlaylistTrack("u", "t", "a", 1)));

        AutocompleteProvider<String> provider = this.<String>providerOf("playlist", "name");

        assertEquals(List.of("mine"), provider.suggest(typed("")).stream().map(OptionChoice::value).toList());
        assertEquals(List.of("ours"), provider
                .suggest(new AutocompleteContext("", GUILD_ID, USER_ID, Map.of("scope", "server"))).stream()
                .map(OptionChoice::value).toList());
    }
}
