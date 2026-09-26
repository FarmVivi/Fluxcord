package fr.farmvivi.fluxcord.plugins.music.events;

import fr.farmvivi.fluxcord.api.config.Configuration;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.plugins.music.MusicManager;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The submission of the add-track modal.
 *
 * <p>The interesting part is the query mapping: a modal gives free text, while most source managers only
 * understand URLs. Anything that is not a link is turned into a YouTube search, so typing a song name
 * works whichever provider was picked — and a pasted link is never rewritten, which would break it.
 */
class MusicModalListenerTest {

    private static final String GUILD_ID = "g1";

    private MusicPlugin plugin;
    private MusicManager manager;
    private Configuration configuration;
    private ModalInteractionEvent event;
    private ReplyCallbackAction reply;

    @BeforeEach
    void setUp() {
        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(anyString())).thenAnswer(i -> i.getArgument(0));
        when(language.getDefaultLocale()).thenReturn(Locale.FRANCE);

        configuration = mock(Configuration.class);
        when(configuration.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));

        manager = mock(MusicManager.class);
        plugin = mock(MusicPlugin.class);
        when(plugin.getLanguage()).thenReturn(language);
        when(plugin.getConfiguration()).thenReturn(configuration);
        when(plugin.getMusicManager()).thenReturn(manager);

        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        User user = mock(User.class);
        when(user.getId()).thenReturn("u1");
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        when(channel.getId()).thenReturn("c1");

        reply = mock(ReplyCallbackAction.class);
        when(reply.setEphemeral(anyBoolean())).thenReturn(reply);

        event = mock(ModalInteractionEvent.class);
        when(event.getUser()).thenReturn(user);
        when(event.isFromGuild()).thenReturn(true);
        when(event.getGuild()).thenReturn(guild);
        when(event.getChannel()).thenReturn(channel);
        when(event.reply(anyString())).thenReturn(reply);
        when(event.deferReply(anyBoolean())).thenReturn(reply);
    }

    /**
     * Submits the modal with the given id and field values.
     *
     * <p>The mappings are built into locals first: Mockito forbids creating and stubbing a mock inside an
     * ongoing {@code when(...)}, which fails as an unrelated UnfinishedStubbingException in the next test.
     */
    private void submit(String modalId, String provider, String query) {
        ModalMapping providerField = mapping(provider);
        ModalMapping queryField = mapping(query);
        when(event.getModalId()).thenReturn(modalId);
        when(event.getValue("provider")).thenReturn(providerField);
        when(event.getValue("query")).thenReturn(queryField);
        new MusicModalListener(plugin).onModalInteraction(event);
    }

    private ModalMapping mapping(String value) {
        if (value == null) {
            return null;
        }
        ModalMapping mapping = mock(ModalMapping.class);
        when(mapping.getAsString()).thenReturn(value);
        return mapping;
    }

    /** The query that reached the music manager. */
    private String loadedQuery() {
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(manager).loadTrack(any(), captor.capture(), eq(false));
        return captor.getValue();
    }

    @Test
    void aModalOfAnotherFeatureIsLeftAlone() {
        // Every plugin sees every modal; this listener must ignore the ones that are not its own.
        submit("someotherplugin:thing", "youtube", "a song");

        verifyNoInteractions(manager);
        verify(event, never()).reply(anyString());
    }

    @Test
    void aMusicModalWithAnUnsupportedActionIsIgnored() {
        submit("music:" + GUILD_ID + ":remove", "youtube", "a song");

        verifyNoInteractions(manager);
        verify(event, never()).reply(anyString());
    }

    @Test
    void aMissingFieldIsReportedWithoutLoadingAnything() {
        submit("music:" + GUILD_ID + ":add", "youtube", null);

        verify(event).reply("music.error.modal.invalid");
        verifyNoInteractions(manager);
    }

    @Test
    void anEmptyQueryIsReportedToo() {
        submit("music:" + GUILD_ID + ":add", "youtube", "");

        verify(event).reply("music.error.modal.invalid");
        verifyNoInteractions(manager);
    }

    @Test
    void freeTextBecomesAYoutubeSearchWhateverTheProvider() {
        // Source managers want URLs; without this, typing a song name would simply fail.
        submit("music:" + GUILD_ID + ":add", "spotify", "daft punk around the world");

        assertEquals("ytsearch:daft punk around the world", loadedQuery());
        verify(event).deferReply(true);
    }

    @Test
    void aPastedLinkIsPassedThroughUntouched() {
        // Rewriting a URL into a search would lose the exact track the user asked for.
        submit("music:" + GUILD_ID + ":add", "spotify",
                "https://open.spotify.com/track/1234567890abcdefghijkl");

        assertEquals("https://open.spotify.com/track/1234567890abcdefghijkl", loadedQuery());
    }

    @Test
    void anUnknownProviderLeavesTheQueryAsTyped() {
        // Nothing is assumed about a provider the mapping does not know.
        submit("music:" + GUILD_ID + ":add", "some-new-provider", "a song");

        assertEquals("a song", loadedQuery());
    }

    @Test
    void theSearchProviderAlsoPrefixesTheQuery() {
        submit("music:" + GUILD_ID + ":add", "search", "a song");

        assertEquals("ytsearch:a song", loadedQuery());
    }

    @Test
    void aProviderFieldThatCannotBeReadFallsBackToTheConfiguredDefault() {
        // A STRING_SELECT inside a modal is not readable as a string on every JDA version; the listener
        // must still load something rather than dropping the user's request.
        ModalMapping broken = mock(ModalMapping.class);
        when(broken.getAsString()).thenThrow(new IllegalStateException("not a text input"));
        ModalMapping queryField = mapping("a song");
        when(event.getModalId()).thenReturn("music:" + GUILD_ID + ":add");
        when(event.getValue("provider")).thenReturn(broken);
        when(event.getValue("query")).thenReturn(queryField);

        new MusicModalListener(plugin).onModalInteraction(event);

        assertEquals("ytsearch:a song", loadedQuery(), "youtube is the default when it is enabled");
    }

    @Test
    void theFallbackFollowsWhichProviderIsActuallyEnabled() {
        when(configuration.getBoolean("providers.youtube.enabled", true)).thenReturn(false);
        when(configuration.getBoolean("providers.soundcloud.enabled", false)).thenReturn(true);
        ModalMapping broken = mock(ModalMapping.class);
        when(broken.getAsString()).thenThrow(new IllegalStateException("not a text input"));
        ModalMapping queryField = mapping("a song");
        when(event.getModalId()).thenReturn("music:" + GUILD_ID + ":add");
        when(event.getValue("provider")).thenReturn(broken);
        when(event.getValue("query")).thenReturn(queryField);

        new MusicModalListener(plugin).onModalInteraction(event);

        // SoundCloud is in the "expects a URL" group, so a plain name still becomes a search.
        assertEquals("ytsearch:a song", loadedQuery());
    }

    @Test
    void aFailureWhileLoadingIsReportedInsteadOfEscaping() {
        // The listener is called by JDA: an exception escaping here would only appear in JDA's own log.
        doThrow(new IllegalStateException("registry down"))
                .when(manager).loadTrack(any(), anyString(), anyBoolean());

        assertDoesNotThrow(() -> submit("music:" + GUILD_ID + ":add", "youtube", "a song"));

        verify(event).reply("music.error.modal.unexpected");
    }

    @Test
    void aModalSubmittedOutsideAGuildStillDoesNotThrow() {
        when(event.isFromGuild()).thenReturn(false);

        assertDoesNotThrow(() -> submit("music:" + GUILD_ID + ":add", "youtube", "a song"));
    }
}
