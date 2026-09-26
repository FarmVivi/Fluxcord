package fr.farmvivi.fluxcord.plugins.music;

import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.api.permissions.PluginPermissionAdapter;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.ui.MusicPlayerMessage;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import fr.farmvivi.fluxcord.api.config.Configuration;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.requests.restaction.interactions.ModalCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import net.dv8tion.jda.api.requests.restaction.interactions.MessageEditCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The buttons under the player message — the part of the plugin a user touches most, and the one the
 * earlier coverage passes left to the smoke run.
 *
 * <p>Two things are asserted throughout: that an action is refused <em>before</em> it reaches the player,
 * and that a refusal never edits the message. A permission check that runs after the side effect would
 * look identical in a screenshot and be wrong.
 */
class ButtonHandlerTest {

    private static final String GUILD_ID = "g1";
    private static final String USER_ID = "u1";
    private static final String PLUGIN_ID = "music-plugin";

    private MusicPlugin plugin;
    private MusicPlayer player;
    private PluginPermissionAdapter permissions;
    private ButtonInteractionEvent event;
    private ReplyCallbackAction reply;
    private MessageEditCallbackAction edit;
    private Guild guild;
    private Member member;

    @BeforeEach
    void setUp() {
        PluginLanguageAdapter language = mock(PluginLanguageAdapter.class);
        when(language.getString(anyString())).thenAnswer(i -> i.getArgument(0));
        when(language.getString(any(Locale.class), anyString())).thenAnswer(i -> i.getArgument(1));

        permissions = mock(PluginPermissionAdapter.class);
        player = mock(MusicPlayer.class);
        MusicManager manager = mock(MusicManager.class);

        plugin = mock(MusicPlugin.class);
        when(plugin.getId()).thenReturn(PLUGIN_ID);
        when(plugin.getLanguage()).thenReturn(language);
        when(plugin.getPermissions()).thenReturn(permissions);
        when(plugin.getMusicManager()).thenReturn(manager);

        guild = mock(Guild.class);
        when(guild.getId()).thenReturn(GUILD_ID);
        when(manager.getPlayer(guild)).thenReturn(player);

        AudioChannelUnion channel = mock(AudioChannelUnion.class);
        GuildVoiceState voiceState = mock(GuildVoiceState.class);
        when(voiceState.getChannel()).thenReturn(channel);
        member = mock(Member.class);
        when(member.getId()).thenReturn(USER_ID);
        when(member.getGuild()).thenReturn(guild);
        when(member.getVoiceState()).thenReturn(voiceState);

        reply = mock(ReplyCallbackAction.class);
        when(reply.setEphemeral(anyBoolean())).thenReturn(reply);
        edit = mock(MessageEditCallbackAction.class);

        event = mock(ButtonInteractionEvent.class);
        when(event.getGuild()).thenReturn(guild);
        when(event.getMember()).thenReturn(member);
        when(event.reply(anyString())).thenReturn(reply);
        when(event.deferEdit()).thenReturn(edit);
    }

    private void press(String action) {
        new ButtonHandler(plugin).handleButton(event, new MusicPlayerMessage.ButtonInfo(GUILD_ID, action));
    }

    private void allow(String node) {
        when(permissions.hasPermission(USER_ID, GUILD_ID, PLUGIN_ID + "." + node)).thenReturn(true);
    }

    @Test
    void aButtonPressedFromAnotherGuildIsRefused() {
        // Button ids carry their guild: a message forwarded elsewhere must not drive this player.
        new ButtonHandler(plugin).handleButton(event, new MusicPlayerMessage.ButtonInfo("other", "pause"));

        verify(event).reply("music.error.wrong_guild");
        verifyNoInteractions(player);
    }

    @Test
    void aButtonPressedOutsideAnyGuildIsRefused() {
        when(event.getGuild()).thenReturn(null);

        press("pause");

        verify(event).reply("music.error.wrong_guild");
        verifyNoInteractions(player);
    }

    @Test
    void someoneNotInAVoiceChannelCannotDriveThePlayer() {
        when(member.getVoiceState()).thenReturn(null);

        press("pause");

        verify(event).reply("music.error.not_in_voice");
        verifyNoInteractions(player);
    }

    @Test
    void aMemberWhoLeftTheChannelCannotDriveThePlayerEither() {
        GuildVoiceState state = mock(GuildVoiceState.class);
        when(state.getChannel()).thenReturn(null);
        when(member.getVoiceState()).thenReturn(state);

        press("pause");

        verify(event).reply("music.error.not_in_voice");
        verifyNoInteractions(player);
    }

    @Test
    void pauseSkipAndStopEachNeedTheirOwnPermission() {
        press("pause");
        press("skip");
        press("stop");

        verify(event, times(3)).reply("music.error.no_permission");
        verifyNoInteractions(player);
        verify(event, never()).deferEdit();
    }

    @Test
    void pauseAndStopArePartOfTheSamePermissionAsPlaying() {
        allow("play");

        press("pause");
        press("stop");

        verify(player).togglePause();
        verify(player).stop();
        verify(event, times(2)).deferEdit();
    }

    @Test
    void skippingNeedsTheSkipPermission() {
        allow("skip");

        press("skip");

        verify(player).skip();
    }

    @Test
    void clearingTheQueueIsAnAdminAction() {
        press("clear");
        verify(event).reply("music.error.no_permission");
        verify(player, never()).clearQueue();

        allow("admin");
        press("clear");

        verify(player).clearQueue();
    }

    @Test
    void aGlobalPermissionIsEnoughWhenTheGuildScopedOneIsAbsent() {
        // The handler accepts either scope, so an operator granted globally is not locked out per guild.
        when(permissions.hasPermission(USER_ID, GUILD_ID, PLUGIN_ID + ".skip")).thenReturn(false);
        when(permissions.hasPermission(USER_ID, PLUGIN_ID + ".skip")).thenReturn(true);

        press("skip");

        verify(player).skip();
    }

    @Test
    void theLegacyNodeNamesAreRewrittenToTheRegisteredOnes() {
        // The handler passes "music.skip" internally while the plugin registers "music-plugin.skip";
        // the prefix is rebuilt from the plugin id, so the check must land on the registered name.
        allow("skip");

        press("skip");

        verify(permissions).hasPermission(USER_ID, GUILD_ID, PLUGIN_ID + ".skip");
        verify(permissions, never()).hasPermission(USER_ID, GUILD_ID, "music.skip");
    }

    @Test
    void loopAndShuffleNeedTheSamePermissionAsTheirCommands() {
        // A permission gates an action, not the way it was invoked: /loop and the loop button must agree,
        // otherwise a restriction is bypassable by using the other one.
        press("loop");
        press("loopqueue");
        press("shuffle");

        verify(event, times(3)).reply("music.error.no_permission");
        verifyNoInteractions(player);

        allow("queue");
        press("loop");
        press("loopqueue");
        press("shuffle");

        verify(player).toggleLoop();
        verify(player).toggleLoopQueue();
        verify(player).toggleShuffle();
    }

    @Test
    void volumeCarriesItsChangeInTheActionItself() {
        allow("volume");

        press("volume:+10");
        press("volume:-25");

        verify(player).changeVolume(10);
        verify(player).changeVolume(-25);
    }

    @Test
    void anUnreadableVolumeChangeIsIgnoredInsteadOfThrowing() {
        // The button id is data from Discord; a malformed one must not break the interaction.
        allow("volume");

        press("volume:loud");
        press("volume");

        verify(player, never()).changeVolume(anyInt());
        verify(event, times(2)).deferEdit();
    }

    @Test
    void mutingNeedsTheVolumePermission() {
        press("mute");
        verify(event).reply("music.error.no_permission");

        allow("volume");
        press("mute");

        verify(player).toggleMute();
    }

    @Test
    void anUnknownActionSaysSoAndTouchesNothing() {
        press("selfdestruct");

        verify(event).reply("music.error.unknown_action");
        verifyNoInteractions(player);
    }

    @Test
    void anAlreadyAcknowledgedInteractionIsNeverRepliedToTwice() {
        // Discord rejects a second acknowledgement; the guards exist for a re-delivered interaction.
        when(event.isAcknowledged()).thenReturn(true);

        press("selfdestruct");
        new ButtonHandler(plugin).handleButton(event, new MusicPlayerMessage.ButtonInfo("other", "pause"));

        verify(event, never()).reply(anyString());
    }

    // The "add" button, which opens the modal and is the only reader of the provider list

    /** Configuration answering the defaults, so each provider can be switched on one at a time. */
    private Configuration providerConfig() {
        Configuration cfg = mock(Configuration.class);
        when(cfg.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(cfg.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(plugin.getConfiguration()).thenReturn(cfg);
        return cfg;
    }

    @Test
    void theAddButtonOpensAModalOfferingTheEnabledProviders() {
        providerConfig(); // defaults: YouTube on, everything else off
        ModalCallbackAction modalAction = mock(ModalCallbackAction.class);
        ArgumentCaptor<Modal> modal = ArgumentCaptor.forClass(Modal.class);
        when(event.replyModal(any(Modal.class))).thenReturn(modalAction);

        press("add");

        verify(event).replyModal(modal.capture());
        assertTrue(modal.getValue().toString().contains("music:" + GUILD_ID + ":add"),
                "the modal carries its guild, so a stale message cannot drive another server");
        verifyNoInteractions(player);
    }

    @Test
    void aProviderIsOfferedOnlyWhenItsCredentialsArePresent() {
        // Offering Spotify without a client id would produce a choice that always fails.
        Configuration cfg = providerConfig();
        when(cfg.getBoolean("providers.spotify.enabled", false)).thenReturn(true);

        assertEquals(List.of("youtube"), providerValues(), "enabled but unconfigured: not offered");

        when(cfg.getString("providers.spotify.client_id", null)).thenReturn("id");
        assertEquals(List.of("youtube"), providerValues(), "half-configured is still not configured");

        when(cfg.getString("providers.spotify.client_secret", null)).thenReturn("secret");
        assertEquals(List.of("youtube", "spotify"), providerValues());
    }

    @Test
    void deezerAndAppleMusicAlsoNeedTheirCredentials() {
        Configuration cfg = providerConfig();
        when(cfg.getBoolean("providers.deezer.enabled", false)).thenReturn(true);
        when(cfg.getBoolean("providers.apple_music.enabled", false)).thenReturn(true);

        assertEquals(List.of("youtube"), providerValues());

        when(cfg.getString("providers.deezer.master_decryption_key", null)).thenReturn("key");
        when(cfg.getString("providers.deezer.arl_cookie", null)).thenReturn("arl");
        when(cfg.getString("providers.apple_music.token", null)).thenReturn("token");

        assertEquals(List.of("youtube", "deezer", "apple_music"), providerValues());
    }

    @Test
    void aProviderNeedingNoCredentialsIsOfferedAsSoonAsItIsEnabled() {
        Configuration cfg = providerConfig();
        for (String name : List.of("soundcloud", "bandcamp", "vimeo", "twitch", "getyarn")) {
            when(cfg.getBoolean("providers." + name + ".enabled", false)).thenReturn(true);
        }

        assertTrue(providerValues().containsAll(
                List.of("youtube", "soundcloud", "bandcamp", "vimeo", "twitch")));
    }

    @Test
    void youtubeIsOfferedByDefaultAndPreselected() {
        providerConfig();

        List<SelectOption> options = ButtonHandler.ProviderOptions.fromConfig(plugin);

        assertEquals(1, options.size());
        assertEquals("youtube", options.get(0).getValue());
        assertTrue(options.get(0).isDefault(), "so the menu is never submitted empty");
    }

    @Test
    void everyProviderCanBeTurnedOffLeavingNothingToOffer() {
        Configuration cfg = providerConfig();
        when(cfg.getBoolean("providers.youtube.enabled", true)).thenReturn(false);

        assertEquals(List.of(), providerValues());
    }

    /** The provider values the modal would offer, in order. */
    private List<String> providerValues() {
        return ButtonHandler.ProviderOptions.fromConfig(plugin).stream()
                .map(SelectOption::getValue)
                .toList();
    }

    @Test
    void withEveryProviderOffTheUserIsToldRatherThanShownAnEmptyMenu() {
        // Discord rejects a select menu with no option, so the modal must not even be built.
        Configuration cfg = providerConfig();
        when(cfg.getBoolean("providers.youtube.enabled", true)).thenReturn(false);

        press("add");

        verify(event).reply("music.error.no_providers");
        verify(event, never()).replyModal(any(Modal.class));
    }

    @Test
    void theAddButtonIsIgnoredOnAnAlreadyAcknowledgedInteraction() {
        providerConfig();
        when(event.isAcknowledged()).thenReturn(true);

        press("add");

        verify(event, never()).replyModal(any(Modal.class));
        verify(event, never()).reply(anyString());
    }
}

