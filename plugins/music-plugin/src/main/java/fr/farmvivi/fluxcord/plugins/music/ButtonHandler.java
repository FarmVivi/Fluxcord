package fr.farmvivi.fluxcord.plugins.music;

import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import fr.farmvivi.fluxcord.plugins.music.ui.MusicPlayerMessage;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.modals.Modal;

/**
 * Handles button interactions for the music player.
 */
public class ButtonHandler {
    private final MusicPlugin plugin;

    public ButtonHandler(MusicPlugin plugin) {
        this.plugin = plugin;
    }

    public void handleButton(ButtonInteractionEvent event, MusicPlayerMessage.ButtonInfo info) {
        Guild guild = event.getGuild();
        if (guild == null || !guild.getId().equals(info.guildId())) {
            if (!event.isAcknowledged()) {
                event.reply(plugin.getLanguage().getString("music.error.wrong_guild"))
                        .setEphemeral(true)
                        .queue();
            }
            return;
        }

        Member member = event.getMember();
        if (member == null || member.getVoiceState() == null || member.getVoiceState().getChannel() == null) {
            if (!event.isAcknowledged()) {
                event.reply(plugin.getLanguage().getString("music.error.not_in_voice"))
                        .setEphemeral(true)
                        .queue();
            }
            return;
        }

        MusicPlayer player = plugin.getMusicManager().getPlayer(guild);

        // Handle action with optional value (e.g., "volume:+10")
        String action = info.action();
        String value = null;
        if (action.contains(":")) {
            String[] parts = action.split(":", 2);
            action = parts[0];
            value = parts[1];
        }

        switch (action) {
            case "add":
                // Open modal to add a new track with provider selection
                if (event.isAcknowledged()) return;

                // Build provider options dynamically from configuration
                var providerMenuBuilder = StringSelectMenu.create("provider")
                        .setPlaceholder(plugin.getLanguage().getString("music.modal.provider.placeholder"))
                        .setMaxValues(1)
                        .setMinValues(1);

                for (SelectOption opt : ProviderOptions.fromConfig(plugin)) {
                    providerMenuBuilder.addOptions(opt);
                }

                // Fallback: if no option is available, inform the user
                if (providerMenuBuilder.getOptions().isEmpty()) {
                    event.reply(plugin.getLanguage().getString("music.error.no_providers"))
                            .setEphemeral(true)
                            .queue();
                    return;
                }

                // Text input for query or URL
                TextInput queryInput = TextInput.create(
                                "query",
                                TextInputStyle.SHORT
                        )
                        .setPlaceholder(plugin.getLanguage().getString("music.modal.query.placeholder"))
                        .setRequired(true)
                        .build();

                String modalId = "music:" + guild.getId() + ":add";
                Modal modal = Modal.create(modalId, plugin.getLanguage().getString("music.modal.title"))
                        .addComponents(
                                Label.of(plugin.getLanguage().getString("music.modal.provider.label"), providerMenuBuilder.build()),
                                Label.of(plugin.getLanguage().getString("music.modal.query.label"), queryInput)
                        )
                        .build();

                event.replyModal(modal).queue();
                break;

            case "pause":
                if (!hasPermission(member, MusicPlugin.PERM_PLAY)) {
                    replyNoPermission(event);
                    return;
                }
                event.deferEdit().queue();
                player.togglePause();
                break;

            case "skip":
                if (!hasPermission(member, MusicPlugin.PERM_SKIP)) {
                    replyNoPermission(event);
                    return;
                }
                event.deferEdit().queue();
                player.skip();
                break;

            case "stop":
                if (!hasPermission(member, MusicPlugin.PERM_PLAY)) {
                    replyNoPermission(event);
                    return;
                }
                event.deferEdit().queue();
                player.stop(); // stay in the channel until the auto-leave timeout
                break;

            case "clear":
                if (!hasPermission(member, MusicPlugin.PERM_ADMIN)) {
                    replyNoPermission(event);
                    return;
                }
                event.deferEdit().queue();
                player.clearQueue();
                break;

            case "loop":
                if (!hasPermission(member, MusicPlugin.PERM_QUEUE)) {
                    replyNoPermission(event);
                    return;
                }
                event.deferEdit().queue();
                player.toggleLoop();
                break;

            case "loopqueue":
                if (!hasPermission(member, MusicPlugin.PERM_QUEUE)) {
                    replyNoPermission(event);
                    return;
                }
                event.deferEdit().queue();
                player.toggleLoopQueue();
                break;

            case "shuffle":
                if (!hasPermission(member, MusicPlugin.PERM_QUEUE)) {
                    replyNoPermission(event);
                    return;
                }
                event.deferEdit().queue();
                player.toggleShuffle();
                break;

            case "volume":
                if (!hasPermission(member, MusicPlugin.PERM_VOLUME)) {
                    replyNoPermission(event);
                    return;
                }
                event.deferEdit().queue();
                if (value != null) {
                    try {
                        int change = Integer.parseInt(value);
                        player.changeVolume(change);
                    } catch (NumberFormatException ignored) {
                    }
                }
                break;

            case "mute":
                if (!hasPermission(member, MusicPlugin.PERM_VOLUME)) {
                    replyNoPermission(event);
                    return;
                }
                event.deferEdit().queue();
                player.toggleMute();
                break;

            default:
                if (!event.isAcknowledged()) {
                    event.reply(plugin.getLanguage().getString("music.error.unknown_action"))
                            .setEphemeral(true)
                            .queue();
                }
                break;
        }
    }

    /**
     * Whether the member holds one of this plugin's permission nodes.
     *
     * <p>Takes the short node (the constants on {@link MusicPlugin}) rather than a {@code music.<node>}
     * literal that had to have its prefix rewritten here at every call. Either scope is accepted, so an
     * operator granted globally is not locked out of one guild.
     *
     * @param member the person who pressed the button
     * @param node   a node from {@link MusicPlugin}, without the plugin id
     * @return true when the action is allowed
     */
    private boolean hasPermission(Member member, String node) {
        String userId = member.getId();
        String guildId = member.getGuild().getId();
        String permission = plugin.permissionKey(node);
        return plugin.getPermissions().hasPermission(userId, guildId, permission)
                || plugin.getPermissions().hasPermission(userId, permission);
    }

    private void replyNoPermission(ButtonInteractionEvent event) {
        PluginLanguageAdapter lm = plugin.getLanguage();
        if (!event.isAcknowledged()) {
            event.reply(lm.getString("music.error.no_permission")).setEphemeral(true).queue();
        }
    }

    /**
     * Builds the provider choices of the add-track modal from the configuration.
     *
     * <p>Package-private rather than private so it can be tested on its own: which providers are offered
     * depends on eight configuration branches and on credentials being present, and reaching that through
     * a modal would test JDA's component tree instead of the rule.
     */
    static final class ProviderOptions {
        static java.util.List<SelectOption> fromConfig(MusicPlugin plugin) {
            var cfg = plugin.getConfiguration();
            var options = new java.util.ArrayList<SelectOption>();

            // YouTube (links)
            boolean youtubeEnabled = cfg.getBoolean("providers.youtube.enabled", true);
            if (youtubeEnabled) {
                options.add(SelectOption.of("YouTube", "youtube").withDefault(true));
            }

            // Spotify
            boolean spotifyEnabled = cfg.getBoolean("providers.spotify.enabled", false);
            String spId = cfg.getString("providers.spotify.client_id", null);
            String spSecret = cfg.getString("providers.spotify.client_secret", null);
            if (spotifyEnabled && spId != null && spSecret != null) {
                options.add(SelectOption.of("Spotify", "spotify"));
            }

            // Deezer
            boolean deezerEnabled = cfg.getBoolean("providers.deezer.enabled", false);
            String dzKey = cfg.getString("providers.deezer.master_decryption_key", null);
            String dzArl = cfg.getString("providers.deezer.arl_cookie", null);
            if (deezerEnabled && dzKey != null && dzArl != null) {
                options.add(SelectOption.of("Deezer", "deezer"));
            }

            // Apple Music
            boolean appleEnabled = cfg.getBoolean("providers.apple_music.enabled", false);
            String appleToken = cfg.getString("providers.apple_music.token", null);
            if (appleEnabled && appleToken != null) {
                options.add(SelectOption.of("Apple Music", "apple_music"));
            }

            // SoundCloud
            if (cfg.getBoolean("providers.soundcloud.enabled", false)) {
                options.add(SelectOption.of("SoundCloud", "soundcloud"));
            }

            // Bandcamp
            if (cfg.getBoolean("providers.bandcamp.enabled", false)) {
                options.add(SelectOption.of("Bandcamp", "bandcamp"));
            }

            // Vimeo
            if (cfg.getBoolean("providers.vimeo.enabled", false)) {
                options.add(SelectOption.of("Vimeo", "vimeo"));
            }

            // Twitch
            if (cfg.getBoolean("providers.twitch.enabled", false)) {
                options.add(SelectOption.of("Twitch", "twitch"));
            }

            // Getyarn
            if (cfg.getBoolean("providers.getyarn.enabled", false)) {
                options.add(SelectOption.of("GetYarn", "getyarn"));
            }

            // HTTP direct
            if (cfg.getBoolean("providers.http.enabled", false)) {
                options.add(SelectOption.of("HTTP (direct)", "http"));
            }

            // Local files
            if (cfg.getBoolean("providers.local.enabled", false)) {
                options.add(SelectOption.of("Local", "local"));
            }

            // Flowery TTS (acts like a generator)
            boolean floweryEnabled = cfg.getBoolean("providers.flowery_tts.enabled", false);
            String floweryVoice = cfg.getString("providers.flowery_tts.voice", null);
            if (floweryEnabled && floweryVoice != null) {
                options.add(SelectOption.of("Flowery TTS", "flowery_tts"));
            }

            return options;
        }
    }
}