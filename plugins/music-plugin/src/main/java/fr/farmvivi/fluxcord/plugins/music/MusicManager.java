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
import fr.farmvivi.fluxcord.plugins.music.playlist.Playlist;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
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
import java.util.concurrent.atomic.AtomicInteger;

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
        this(plugin, new AudioPlayerManager(plugin));
    }

    /**
     * Test seam: reuses an already configured source registry instead of building one (the real
     * one instantiates every YouTube/Spotify/Deezer client from the plugin configuration).
     *
     * @param plugin             the owning plugin
     * @param audioPlayerManager the source registry to play from
     */
    MusicManager(MusicPlugin plugin, AudioPlayerManager audioPlayerManager) {
        this.plugin = plugin;
        this.audioPlayerManager = audioPlayerManager;
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

    /** The guild's player if one exists, without creating it (autocomplete must stay side-effect free). */
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
                // Most recent first: the insertion order is oldest first, so the view is reversed.
                new java.util.ArrayList<>(recent.entrySet()).reversed()
                        .forEach(e -> copy.put(e.getKey(), e.getValue()));
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

    /** Destroys the guild's player, releasing lavaplayer and the persisted state. */
    public synchronized void destroyPlayer(Guild guild) {
        MusicPlayer player = players.remove(guild.getIdLong());
        if (player != null) {
            player.destroy();
        }
    }

    /**
     * Resolves the player for the command's guild and makes sure the bot is in a voice channel.
     *
     * <p>Replies with the matching error and returns empty when the command was not sent from a
     * guild, or when the bot is not connected and the caller is not in a voice channel either.
     *
     * @param ctx the command context
     * @return the ready-to-use player, or empty when playback cannot start
     */
    private Optional<MusicPlayer> preparePlayback(CommandContext ctx) {
        PluginLanguageAdapter lm = plugin.getLanguage();
        Optional<Guild> optGuild = ctx.getGuild();
        if (optGuild.isEmpty()) {
            ctx.replyError(lm.getString(ctx.getLocale(), "music.error.guild_only"));
            return Optional.empty();
        }

        Guild guild = optGuild.get();
        MusicPlayer player = getPlayer(guild);
        player.setMessageChannel(ctx.getChannel());

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
                ctx.replyError(lm.getString(ctx.getLocale(), "music.error.not_in_voice"));
                return Optional.empty();
            }
            audioManager.openAudioConnection(voiceChannel);
        }
        return Optional.of(player);
    }

    /**
     * Queues every track of a saved playlist, resolving the stored URLs one by one.
     *
     * <p>Tracks are loaded with {@code loadItemOrdered} keyed on the player, so they are queued in
     * the order they were saved. A single summary is sent once every track has been resolved.
     *
     * @param ctx      the command context
     * @param playlist the playlist to queue
     */
    public void loadPlaylist(CommandContext ctx, Playlist playlist) {
        Optional<MusicPlayer> optPlayer = preparePlayback(ctx);
        if (optPlayer.isEmpty()) {
            return;
        }
        MusicPlayer player = optPlayer.get();
        Guild guild = player.getGuild();
        PluginLanguageAdapter lm = plugin.getLanguage();
        Locale locale = ctx.getLocale();

        List<Playlist.PlaylistTrack> entries = playlist.getTracks();
        ctx.deferReply();

        AtomicInteger loaded = new AtomicInteger();
        AtomicInteger remaining = new AtomicInteger(entries.size());
        Runnable summary = () -> {
            if (remaining.decrementAndGet() > 0) {
                return;
            }
            int success = loaded.get();
            int failed = entries.size() - success;
            if (success == 0) {
                ctx.replyError(lm.getString(locale, "music.playlist.load_failed", playlist.getName()));
                return;
            }
            EmbedBuilder embed = new EmbedBuilder()
                    .setColor(Color.GREEN)
                    .setTitle(lm.getString(locale, "music.playlist.loaded", playlist.getName()))
                    .addField(lm.getString(locale, "music.tracks"), String.valueOf(success), true);
            if (failed > 0) {
                embed.addField(lm.getString(locale, "music.playlist.unavailable"), String.valueOf(failed), true);
            }
            ctx.replyEmbed(embed);
        };

        for (Playlist.PlaylistTrack entry : entries) {
            audioPlayerManager.getPlayerManager().loadItemOrdered(player, entry.url(), new AudioLoadResultHandler() {
                @Override
                public void trackLoaded(AudioTrack track) {
                    remember(guild, track);
                    player.playTrack(track);
                    loaded.incrementAndGet();
                    summary.run();
                }

                @Override
                public void playlistLoaded(AudioPlaylist audioPlaylist) {
                    // A stored URL should resolve to a single track; keep the first match if it does not.
                    List<AudioTrack> tracks = audioPlaylist.getTracks();
                    if (tracks.isEmpty()) {
                        noMatches();
                        return;
                    }
                    trackLoaded(tracks.get(0));
                }

                @Override
                public void noMatches() {
                    logger.warn("[{}] Playlist '{}': no match for {}", guild.getName(), playlist.getName(), entry.url());
                    summary.run();
                }

                @Override
                public void loadFailed(FriendlyException exception) {
                    logger.warn("[{}] Playlist '{}': failed to load {}: {}", guild.getName(), playlist.getName(),
                            entry.url(), exception.getMessage());
                    summary.run();
                }
            });
        }
    }

    /**
     * Loads and plays a track.
     */
    public void loadTrack(CommandContext ctx, String query, boolean playNow) {
        Optional<MusicPlayer> optPlayer = preparePlayback(ctx);
        if (optPlayer.isEmpty()) {
            return;
        }
        MusicPlayer player = optPlayer.get();
        Guild guild = player.getGuild();

        // Defer reply for long loading
        ctx.deferReply();

        // Load the track
        audioPlayerManager.getPlayerManager().loadItemOrdered(player, query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                logger.info("[{}] Track loaded: {} ({})",
                        guild.getName(), track.getInfo().title, track.getInfo().uri);
                remember(guild, track);

                PluginLanguageAdapter lm = plugin.getLanguage();
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

                    PluginLanguageAdapter lm = plugin.getLanguage();
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

                    // Indexed, not indexOf inside the loop: that was quadratic, and it identified the
                    // "first" track by equality, so a playlist holding the same track twice would have
                    // jumped the queue again halfway through.
                    for (int i = 0; i < tracks.size(); i++) {
                        if (playNow && i == 0) {
                            player.playTrackNow(tracks.get(i));
                        } else {
                            player.playTrack(tracks.get(i));
                        }
                    }
                }
            }

            @Override
            public void noMatches() {
                logger.warn("[{}] No matches found for: {}", guild.getName(), query);
                PluginLanguageAdapter lm = plugin.getLanguage();
                ctx.replyError(lm.getString(ctx.getLocale(), "music.error.no_matches"));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                logger.error("[{}] Failed to load track: {}", guild.getName(), query, exception);
                PluginLanguageAdapter lm = plugin.getLanguage();
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
        java.util.Optional<?> raw = plugin.getStorage()
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
            plugin.getStorage().getGuildStorage(guildId).remove(MusicPlayer.STATE_KEY);
            plugin.getStorage().saveAll();
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