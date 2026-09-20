package fr.farmvivi.fluxcord.plugins.music;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import fr.farmvivi.fluxcord.api.command.CommandContext;
import fr.farmvivi.fluxcord.api.language.PluginLanguageAdapter;
import fr.farmvivi.fluxcord.plugins.music.audio.AudioPlayerManager;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages music players for different guilds.
 */
public class MusicManager {
    private static final Logger logger = LoggerFactory.getLogger(MusicManager.class);

    private final MusicPlugin plugin;
    private final AudioPlayerManager audioPlayerManager;
    private final Map<Long, MusicPlayer> players;
    // Recently loaded tracks per guild (title -> uri, newest first), used for /play suggestions
    private final Map<Long, java.util.LinkedHashMap<String, String>> recentTracks = new ConcurrentHashMap<>();
    private static final int RECENT_TRACKS = 25;

    public MusicManager(MusicPlugin plugin) {
        this.plugin = plugin;
        this.audioPlayerManager = new AudioPlayerManager(plugin);
        this.players = new ConcurrentHashMap<>();
    }

    /**
     * Gets the underlying LavaPlayer manager (used for track encode/decode on state persistence).
     */
    public com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager getPlayerManager() {
        return audioPlayerManager.getPlayerManager();
    }

    /**
     * Gets or creates a music player for a guild.
     */
    public synchronized MusicPlayer getPlayer(Guild guild) {
        return players.computeIfAbsent(guild.getIdLong(), id -> {
            AudioPlayer audioPlayer = audioPlayerManager.getPlayerManager().createPlayer();
            return new MusicPlayer(plugin, guild, audioPlayer);
        });
    }

    /**
     * Destroys a music player for a guild.
     */
    /** The guild'"'"'s player if one exists, without creating it (autocomplete must stay side-effect free). */
    public Optional<MusicPlayer> findPlayer(String guildId) {
        try {
            return Optional.ofNullable(players.get(Long.parseLong(guildId)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Titles recently played in the guild (newest first) with their URI. */
    public Map<String, String> getRecentTracks(String guildId) {
        try {
            java.util.LinkedHashMap<String, String> recent = recentTracks.get(Long.parseLong(guildId));
            if (recent == null) {
                return Map.of();
            }
            synchronized (recent) {
                java.util.LinkedHashMap<String, String> copy = new java.util.LinkedHashMap<>();
                java.util.ArrayList<Map.Entry<String, String>> entries = new java.util.ArrayList<>(recent.entrySet());
                java.util.Collections.reverse(entries);
                entries.forEach(e -> copy.put(e.getKey(), e.getValue()));
                return copy;
            }
        } catch (NumberFormatException e) {
            return Map.of();
        }
    }

    private void remember(Guild guild, AudioTrack track) {
        if (track.getInfo().uri == null || track.getInfo().title == null) {
            return;
        }
        java.util.LinkedHashMap<String, String> recent = recentTracks.computeIfAbsent(guild.getIdLong(), id -> new java.util.LinkedHashMap<>());
        synchronized (recent) {
            recent.remove(track.getInfo().title); // re-insert as newest
            recent.put(track.getInfo().title, track.getInfo().uri);
            while (recent.size() > RECENT_TRACKS) {
                recent.remove(recent.keySet().iterator().next());
            }
        }
    }

    public synchronized void destroyPlayer(Guild guild) {
        MusicPlayer player = players.remove(guild.getIdLong());
        if (player != null) {
            player.destroy();
        }
    }

    /**
     * Loads and plays a track.
     */
    public void loadTrack(CommandContext ctx, String query, boolean playNow) {
        Optional<Guild> optGuild = ctx.getGuild();
        if (optGuild.isEmpty()) {
            // This command must be used in a guild context
            PluginLanguageAdapter lm = plugin.getPluginLanguageManager();
            Locale locale = ctx.getLocale();
            ctx.replyError(lm.getString(locale, "music.error.guild_only"));
            return;
        }

        Guild guild = optGuild.get();
        MusicPlayer player = getPlayer(guild);
        MessageChannel channel = ctx.getChannel();

        // Set the message channel for the player
        player.setMessageChannel(channel);

        // Connect to voice channel if not connected
        AudioManager audioManager = guild.getAudioManager();
        if (!audioManager.isConnected()) {
            Member member = null;
            if (ctx.getOriginalEvent() instanceof SlashCommandInteractionEvent e) {
                member = e.getMember();
            } else if (ctx.getOriginalEvent() instanceof MessageReceivedEvent e) {
                member = e.getMember();
            }

            AudioChannel voiceChannel = member != null && member.getVoiceState() != null
                    ? member.getVoiceState().getChannel()
                    : null;

            if (voiceChannel == null) {
                PluginLanguageAdapter lm = plugin.getPluginLanguageManager();
                ctx.replyError(lm.getString(ctx.getLocale(), "music.error.not_in_voice"));
                return;
            }
            audioManager.openAudioConnection(voiceChannel);
        }

        // Defer reply for long loading
        ctx.deferReply();

        // Load the track
        audioPlayerManager.getPlayerManager().loadItemOrdered(player, query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                logger.info("[{}] Track loaded: {} ({})",
                        guild.getName(), track.getInfo().title, track.getInfo().uri);
                remember(guild, track);

                PluginLanguageAdapter lm = plugin.getPluginLanguageManager();
                Locale locale = ctx.getLocale();

                EmbedBuilder embed = new EmbedBuilder()
                        .setColor(Color.GREEN)
                        .setTitle(lm.getString(locale, "music.track_added"))
                        .addField(
                                lm.getString(locale, "music.title"),
                                String.format("[%s](%s)", track.getInfo().title, track.getInfo().uri),
                                false
                        );

                if (track.getInfo().artworkUrl != null) {
                    embed.setThumbnail(track.getInfo().artworkUrl);
                }

                ctx.replyEmbed(embed);

                if (playNow) {
                    player.playTrackNow(track);
                } else {
                    player.playTrack(track);
                }
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                List<AudioTrack> tracks = playlist.getTracks();

                if (playlist.isSearchResult() && !tracks.isEmpty()) {
                    // For search results, play the first track
                    AudioTrack track = tracks.get(0);
                    trackLoaded(track);
                } else {
                    // Queue the entire playlist
                    logger.info("[{}] Playlist loaded: {} ({} tracks)",
                            guild.getName(), playlist.getName(), tracks.size());

                    PluginLanguageAdapter lm = plugin.getPluginLanguageManager();
                    Locale locale = ctx.getLocale();

                    EmbedBuilder embed = new EmbedBuilder()
                            .setColor(Color.GREEN)
                            .setTitle(lm.getString(locale, "music.playlist_added"))
                            .addField(
                                    lm.getString(locale, "music.name"),
                                    playlist.getName(),
                                    false
                            )
                            .addField(
                                    lm.getString(locale, "music.tracks"),
                                    String.valueOf(tracks.size()),
                                    false
                            );

                    ctx.replyEmbed(embed);

                    for (AudioTrack track : tracks) {
                        if (playNow && tracks.indexOf(track) == 0) {
                            player.playTrackNow(track);
                        } else {
                            player.playTrack(track);
                        }
                    }
                }
            }

            @Override
            public void noMatches() {
                logger.warn("[{}] No matches found for: {}", guild.getName(), query);
                PluginLanguageAdapter lm = plugin.getPluginLanguageManager();
                ctx.replyError(lm.getString(ctx.getLocale(), "music.error.no_matches"));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                logger.error("[{}] Failed to load track: {}", guild.getName(), query, exception);
                PluginLanguageAdapter lm = plugin.getPluginLanguageManager();
                ctx.replyError(lm.getString(ctx.getLocale(), "music.error.load_failed", exception.getMessage()));
            }
        });
    }

    /**
     * Handles voice channel updates for the bot itself.
     *
     * <p>A "left" update is not treated as a definitive disconnect right away: JDA also closes and
     * reopens the audio connection on transient voice-server failures, which briefly looks like a
     * leave. We therefore confirm the disconnect after a grace period, and cancel it if the bot
     * (re)joins in the meantime.
     */
    public void handleVoiceUpdate(GuildVoiceUpdateEvent event) {
        if (!event.getMember().getUser().equals(event.getJDA().getSelfUser())) {
            return;
        }
        MusicPlayer player = players.get(event.getGuild().getIdLong());
        if (player == null) {
            return;
        }

        if (event.getChannelJoined() != null) {
            // (Re)connected to a voice channel: any pending disconnect is a false alarm.
            player.cancelPendingDisconnect();
        } else if (event.getChannelLeft() != null) {
            // Left a voice channel: confirm after a grace period (may be a transient reconnect).
            player.scheduleDisconnectCheck();
        }
    }

    /**
     * Persists the playback state of every active player.
     * Called on graceful shutdown so the bot can resume where it left off after a restart.
     */
    public void saveAllStates() {
        for (MusicPlayer player : players.values()) {
            try {
                player.saveState();
            } catch (Exception e) {
                logger.warn("Failed to save state for guild {}", player.getGuild().getId(), e);
            }
        }
    }

    /**
     * Restores playback for all guilds that have a persisted state.
     * Must be called once the JDA session is ready and guilds are available.
     *
     * @param jda the ready JDA instance
     */
    public void restoreAllStates(net.dv8tion.jda.api.JDA jda) {
        if (!plugin.isPersistenceEnabled()) {
            logger.info("Playback-state persistence is disabled; skipping restore");
            return;
        }
        logger.info("Restoring music playback state for {} guild(s)...", jda.getGuilds().size());
        int restored = 0;
        for (Guild guild : jda.getGuilds()) {
            try {
                if (restoreState(guild)) {
                    restored++;
                }
            } catch (Exception e) {
                logger.warn("Failed to restore state for guild {}", guild.getId(), e);
            }
        }
        logger.info("Restored playback for {} guild(s)", restored);
    }

    /**
     * Restores playback for a single guild from its persisted state, if any.
     *
     * @param guild the guild
     * @return true if a state was found and restoration was attempted
     */
    private boolean restoreState(Guild guild) {
        String guildId = guild.getId();
        java.util.Optional<?> raw = plugin.getPluginDataStorage()
                .getGuildStorage(guildId)
                .get(MusicPlayer.STATE_KEY, Map.class);
        if (raw.isEmpty()) {
            return false;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> stateMap = (Map<String, Object>) raw.get();
        fr.farmvivi.fluxcord.plugins.music.state.PlaybackState state =
                fr.farmvivi.fluxcord.plugins.music.state.PlaybackState.fromMap(stateMap);

        if (!state.hasPlayback() || state.getVoiceChannelId() == null
                || state.isExpired(plugin.getPersistenceTtlMillis())) {
            // Nothing worth restoring (empty or too old); drop the stale entry.
            if (state.isExpired(plugin.getPersistenceTtlMillis())) {
                logger.info("Discarding expired playback state for guild {}", guildId);
            }
            plugin.getPluginDataStorage().getGuildStorage(guildId).remove(MusicPlayer.STATE_KEY);
            plugin.getPluginDataStorage().saveAll();
            return false;
        }

        MusicPlayer player = getPlayer(guild);
        player.restoreFromState(state);
        return true;
    }

    /**
     * Shuts down all players and the audio player manager.
     */
    public void shutdown() {
        logger.info("Shutting down music manager...");

        // Release players without deleting their messages or clearing persisted state, so playback
        // can resume seamlessly after a restart. State was already saved via saveAllStates().
        for (MusicPlayer player : players.values()) {
            player.release();
        }
        players.clear();

        // Shut down audio player manager
        audioPlayerManager.shutdown();
    }
}