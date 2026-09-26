package fr.farmvivi.fluxcord.plugins.aiaudio.commands;

import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.Persona;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaStore;
import fr.farmvivi.fluxcord.plugins.aiaudio.testing.MemoryDataStorage;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@code /persona} — the way an operator sees and changes who the bot is, without waiting for the model step.
 *
 * <p>Two rules run through these tests: showing is open to anyone while changing needs the admin permission,
 * and a change touches exactly one field at one level.
 */
class PersonaCommandTest {

    private static final String PLUGIN_ID = "ai-audio-plugin";
    private static final String GUILD_ID = "g1";
    private static final String CHANNEL_ID = "c1";
    private static final String USER_ID = "u1";

    private AIAudioPlugin plugin;
    private PersonaStore store;
    private PluginPermissionAdapter permissions;
    private CommandContext ctx;
    private Guild guild;
    private AudioManager audioManager;

    @BeforeEach
    void setUp() {
        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(any(Locale.class), anyString())).thenAnswer(i -> i.getArgument(1));
        when(language.getString(any(Locale.class), anyString(), any(Object[].class)))
                .thenAnswer(i -> i.getArgument(1));

        store = new PersonaStore(new PluginDataStorageAdapter(PLUGIN_ID, new MemoryDataStorage()),
                new Persona("Fluxcord", List.of("curieux"), "familier", Locale.FRANCE, ""));
        permissions = mock(PluginPermissionAdapter.class);

        plugin = mock(AIAudioPlugin.class);
        when(plugin.getLanguage()).thenReturn(language);
        when(plugin.getLogger()).thenReturn(LoggerFactory.getLogger("persona-command-test"));
        when(plugin.getPersonaStore()).thenReturn(store);
        when(plugin.getPermissions()).thenReturn(permissions);
        when(plugin.permissionKey(anyString())).thenAnswer(i -> PLUGIN_ID + "." + i.getArgument(0));

        AudioChannelUnion channel = mock(AudioChannelUnion.class);
        when(channel.getId()).thenReturn(CHANNEL_ID);
        audioManager = mock(AudioManager.class);
        when(audioManager.getConnectedChannel()).thenReturn(channel);
        guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        when(guild.getName()).thenReturn("My Server");
        when(guild.getAudioManager()).thenReturn(audioManager);

        User user = mock(User.class);
        when(user.getId()).thenReturn(USER_ID);
        ctx = mock(CommandContext.class);
        when(ctx.getGuild()).thenReturn(Optional.of(guild));
        when(ctx.getUser()).thenReturn(user);
        when(ctx.getLocale()).thenReturn(Locale.FRANCE);
    }

    private void run(String action, String field, String value, String scope) {
        new PersonaCommand(plugin).execute(ctx, action, field, value, scope);
    }

    private void asAdmin() {
        when(permissions.hasPermission(USER_ID, PLUGIN_ID + ".admin")).thenReturn(true);
    }

    @Test
    void showingThePersonaIsOpenToAnyone() {
        run(PersonaCommand.SHOW, null, null, PersonaCommand.SCOPE_SERVER);

        verify(ctx).replyInfo("messages.persona_shown");
        verifyNoInteractions(permissions);
    }

    @Test
    void noActionMeansShow() {
        run(null, null, null, null);

        verify(ctx).replyInfo("messages.persona_shown");
    }

    @Test
    void changingThePersonaNeedsTheAdminPermission() {
        run(PersonaCommand.SET, PersonaCommand.FIELD_TONE, "formel", PersonaCommand.SCOPE_SERVER);
        run(PersonaCommand.RESET, null, null, PersonaCommand.SCOPE_SERVER);

        verify(ctx, times(2)).replyError("errors.no_permission");
        assertTrue(store.guildOverride(GUILD_ID).isEmpty(), "nothing was written");
    }

    @Test
    void anAdminCanSetOneFieldForTheServer() {
        asAdmin();

        run(PersonaCommand.SET, PersonaCommand.FIELD_TONE, "  formel  ", PersonaCommand.SCOPE_SERVER);

        verify(ctx).replySuccess("messages.persona_set");
        assertEquals("formel", store.effective(GUILD_ID, null).tone(), "trimmed");
        assertEquals("Fluxcord", store.effective(GUILD_ID, null).name(), "and nothing else moved");
    }

    @Test
    void settingASecondFieldKeepsTheFirst() {
        // Each call edits the existing override rather than replacing it.
        asAdmin();

        run(PersonaCommand.SET, PersonaCommand.FIELD_TONE, "formel", PersonaCommand.SCOPE_SERVER);
        run(PersonaCommand.SET, PersonaCommand.FIELD_NAME, "Autre", PersonaCommand.SCOPE_SERVER);

        Persona effective = store.effective(GUILD_ID, null);
        assertEquals("formel", effective.tone());
        assertEquals("Autre", effective.name());
    }

    @Test
    void traitsAreSplitOnCommas() {
        asAdmin();

        run(PersonaCommand.SET, PersonaCommand.FIELD_TRAITS, " sérieux , calme ,, ",
                PersonaCommand.SCOPE_SERVER);

        assertEquals(List.of("sérieux", "calme"), store.effective(GUILD_ID, null).traits());
    }

    @Test
    void aChannelScopedChangeOnlyAffectsThatChannel() {
        asAdmin();

        run(PersonaCommand.SET, PersonaCommand.FIELD_TONE, "taquin", PersonaCommand.SCOPE_CHANNEL);

        assertEquals("taquin", store.effective(GUILD_ID, CHANNEL_ID).tone());
        assertEquals("familier", store.effective(GUILD_ID, "other").tone(), "the base still applies there");
    }

    @Test
    void aChannelScopedChangeNeedsTheBotToBeInAChannel() {
        asAdmin();
        when(audioManager.getConnectedChannel()).thenReturn(null);

        run(PersonaCommand.SET, PersonaCommand.FIELD_TONE, "taquin", PersonaCommand.SCOPE_CHANNEL);

        verify(ctx).replyError("errors.not_connected");
    }

    @Test
    void aLanguageIsAcceptedOnlyIfJavaCanReadIt() {
        // An unreadable tag would leave a persona answering in a language nobody asked for.
        asAdmin();

        run(PersonaCommand.SET, PersonaCommand.FIELD_LANGUAGE, "en-US", PersonaCommand.SCOPE_SERVER);
        assertEquals(Locale.forLanguageTag("en-US"), store.effective(GUILD_ID, null).language());

        run(PersonaCommand.SET, PersonaCommand.FIELD_LANGUAGE, "!!!", PersonaCommand.SCOPE_SERVER);
        verify(ctx).replyError("errors.persona_unknown_field");
        assertEquals(Locale.forLanguageTag("en-US"), store.effective(GUILD_ID, null).language(),
                "the previous value is left alone");
    }

    @Test
    void anUnknownFieldIsRefused() {
        asAdmin();

        run(PersonaCommand.SET, "favourite-colour", "bleu", PersonaCommand.SCOPE_SERVER);

        verify(ctx).replyError("errors.persona_unknown_field");
        assertTrue(store.guildOverride(GUILD_ID).isEmpty());
    }

    @Test
    void settingWithoutAFieldOrAValueExplainsItself() {
        asAdmin();

        run(PersonaCommand.SET, null, "formel", PersonaCommand.SCOPE_SERVER);
        run(PersonaCommand.SET, PersonaCommand.FIELD_TONE, null, PersonaCommand.SCOPE_SERVER);
        run(PersonaCommand.SET, PersonaCommand.FIELD_TONE, "   ", PersonaCommand.SCOPE_SERVER);

        verify(ctx, times(3)).replyError("errors.persona_usage");
    }

    @Test
    void resettingRemovesTheOverrideOfItsOwnLevel() {
        asAdmin();
        run(PersonaCommand.SET, PersonaCommand.FIELD_TONE, "formel", PersonaCommand.SCOPE_SERVER);

        run(PersonaCommand.RESET, null, null, PersonaCommand.SCOPE_SERVER);

        verify(ctx).replySuccess("messages.persona_reset");
        assertEquals("familier", store.effective(GUILD_ID, null).tone(), "back to the configured one");
    }

    @Test
    void resettingSomethingThatWasNeverSetSaysSo() {
        asAdmin();

        run(PersonaCommand.RESET, null, null, PersonaCommand.SCOPE_SERVER);

        verify(ctx).replySuccess("messages.persona_nothing_to_reset");
    }

    @Test
    void theMoodIsResetOnItsOwn() {
        // Not an override: clearing a feeling, which is why it is a field of its own.
        asAdmin();
        store.nudgeMood(GUILD_ID, CHANNEL_ID, 0.9, 0, System.currentTimeMillis());

        run(PersonaCommand.RESET, PersonaCommand.FIELD_MOOD, null, PersonaCommand.SCOPE_CHANNEL);

        verify(ctx).replySuccess("messages.mood_reset");
        assertTrue(store.mood(GUILD_ID, CHANNEL_ID, System.currentTimeMillis()).isNeutral());
    }

    @Test
    void theCommandRefusesToRunOutsideAServer() {
        when(ctx.getGuild()).thenReturn(Optional.empty());

        run(PersonaCommand.SHOW, null, null, null);

        verify(ctx).replyError("errors.guild_only");
    }
}
