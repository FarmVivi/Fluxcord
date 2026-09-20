package fr.farmvivi.fluxcord.plugins.music.player;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import fr.farmvivi.fluxcord.api.storage.PluginGuildStorage;
import fr.farmvivi.fluxcord.plugins.music.MusicPlugin;
import fr.farmvivi.fluxcord.plugins.music.audio.AudioPlayerSendHandler;
import fr.farmvivi.fluxcord.plugins.music.state.PlaybackState;
import fr.farmvivi.fluxcord.plugins.music.state.TrackCodec;
import fr.farmvivi.fluxcord.plugins.music.ui.MusicPlayerMessage;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Represents a music player instance for a specific guild.
 * Manages audio playback, queue, and player state.
 */
public class MusicPlayer {
    public static final int DEFAULT_VOLUME = 100;
    /** Guild storage key under which the playback state is persisted. */
    public static final String STATE_KEY = "playback_state";
    private static final int STATE_SAVE_INTERVAL_SECONDS = 10;
    private static final Logger logger = LoggerFactory.getLogger(MusicPlayer.class);
    private final MusicPlugin plugin;
    private final Guild guild;
    private final AudioPlayer audioPlayer;
    private final TrackScheduler trackScheduler;
    private final AudioPlayerSendHandler sendHandler;
    private final MusicPlayerMessage playerMessage;

    private static final int DISCONNECT_GRACE_SECONDS = 5;
    private static final int MAX_DISCONNECT_CHECKS = 12; // up to ~1 min of reconnection attempts

    private ScheduledFuture<?> quitTask;
    private ScheduledFuture<?> stateSaveTask;
    private ScheduledFuture<?> disconnectCheckTask;
    private int disconnectChecks = 0;
    private volatile boolean restoring = false;
    private int volume = DEFAULT_VOLUME;

    public MusicPlayer(MusicPlugin plugin, Guild guild, AudioPlayer audioPlayer) {
        this.plugin = plugin;
        this.guild = guild;
        this.audioPlayer = audioPlayer;
        this.trackScheduler = new TrackScheduler(this, audioPlayer);
        this.sendHandler = new AudioPlayerSendHandler(audioPlayer);
        this.playerMessage = new MusicPlayerMessage(this);

        audioPlayer.addListener(trackScheduler);
        audioPlayer.setVolume(volume);
    }

    /**
     * Plays a track, adding it to the queue if something is already playing.
     */
    public void playTrack(AudioTrack track) {
        cancelQuitTask();
        boolean playing = trackScheduler.queue(track);

        if (playing) {
            // Register our send handler with the AudioService
            plugin.getContext().getAudioService().registerSendHandler(
                    guild,
                    plugin,
                    sendHandler,
                    volume,
                    50 // Normal priority
            );
        }

        playerMessage.refresh();
        ensureStateAutosave();
        saveState();
    }

    /**
     * Plays a track immediately, skipping the current track if any.
     */
    public void playTrackNow(AudioTrack track) {
        cancelQuitTask();
        trackScheduler.playNow(track);

        // Register our send handler with the AudioService
        plugin.getContext().getAudioService().registerSendHandler(
                guild,
                plugin,
                sendHandler,
                volume,
                50 // Normal priority
        );

        playerMessage.refresh();
        ensureStateAutosave();
        saveState();
    }

    /**
     * Skips the current track.
     */
    public void skipTrack() {
        // Délègue au scheduler : gère le ré-enfilage en mode loop
        trackScheduler.skip();
        playerMessage.refresh();
        saveState();

        if (audioPlayer.getPlayingTrack() == null && trackScheduler.getQueueSize() == 0) {
            scheduleQuit();
        }
    }

    /**
     * Stops playback and clears the queue but stays in the voice channel: the auto-leave timer
     * ({@code music.auto_leave_timeout}) gives users a chance to start something else.
     */
    public void stop() {
        trackScheduler.clear();
        audioPlayer.stopTrack();
        playerMessage.refresh();
        saveState();
        scheduleQuit();
    }

    /**
     * Stops playback, clears the queue and leaves the voice channel immediately.
     * Also deregisters the send handler.
     */
    public void stopAndLeave() {
        // Intentional leave: close the connection and clean up immediately (no grace period).
        guild.getAudioManager().closeAudioConnection();
        handleDisconnect();
    }

    /**
     * Sets the message channel for player messages.
     */
    public void setMessageChannel(MessageChannel channel) {
        playerMessage.setMessageChannel(channel);
    }

    /**
     * Handles voice channel disconnection.
     */
    public void handleDisconnect() {
        logger.info("[{}] Handling disconnect, stopping playback", guild.getName());
        cancelPendingDisconnect();
        stop();
        plugin.getContext().getAudioService().deregisterSendHandler(guild, plugin);
        playerMessage.delete();
        clearState();
        stopStateAutosave();
    }

    /**
     * Schedules a delayed check to confirm a voice disconnect. This tolerates transient
     * voice-server reconnects (JDA briefly drops and reopens the audio connection), which would
     * otherwise be mistaken for a permanent disconnect.
     */
    public void scheduleDisconnectCheck() {
        cancelPendingDisconnect();
        disconnectChecks = 0;
        disconnectCheckTask = plugin.getScheduler().schedule(
                this::confirmDisconnect, DISCONNECT_GRACE_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Cancels a pending disconnect confirmation (e.g. because the bot has (re)connected).
     */
    public void cancelPendingDisconnect() {
        if (disconnectCheckTask != null && !disconnectCheckTask.isDone()) {
            disconnectCheckTask.cancel(false);
        }
        disconnectCheckTask = null;
    }

    /**
     * Confirms whether the bot is genuinely disconnected. If JDA is still (re)connecting, waits and
     * re-checks; if reconnected, does nothing; otherwise performs the actual disconnect handling.
     */
    private void confirmDisconnect() {
        var audioManager = guild.getAudioManager();
        if (audioManager.isConnected()) {
            // Reconnected in the meantime: keep playing.
            cancelPendingDisconnect();
            return;
        }
        boolean reconnecting = audioManager.isAutoReconnect()
                && audioManager.getConnectionStatus().shouldReconnect();
        if (reconnecting && ++disconnectChecks < MAX_DISCONNECT_CHECKS) {
            // JDA is still trying to (re)connect after a transient failure: give it more time.
            disconnectCheckTask = plugin.getScheduler().schedule(
                    this::confirmDisconnect, DISCONNECT_GRACE_SECONDS, TimeUnit.SECONDS);
            return;
        }
        // Genuinely disconnected (kicked, channel deleted, gave up reconnecting, etc.).
        handleDisconnect();
    }

    /**
     * Schedules automatic quit after inactivity.
     */
    private void scheduleQuit() {
        cancelQuitTask();

        long timeoutMs = plugin.getAutoLeaveTimeoutMs(); // music.auto_leave_timeout
        ScheduledExecutorService scheduler = plugin.getScheduler();
        quitTask = scheduler.schedule(() -> {
            if (audioPlayer.getPlayingTrack() == null && trackScheduler.getQueueSize() == 0) {
                logger.info("[{}] Auto-leaving voice channel after {} seconds of inactivity",
                        guild.getName(), timeoutMs / 1000);

                guild.getAudioManager().closeAudioConnection();
                plugin.getContext().getAudioService().deregisterSendHandler(guild, plugin);
                playerMessage.delete();
                clearState();
                stopStateAutosave();
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Cancels the scheduled quit task.
     */
    private void cancelQuitTask() {
        if (quitTask != null && !quitTask.isDone()) {
            quitTask.cancel(false);
            quitTask = null;
        }
    }

    /**
     * Cleans up resources when the player is destroyed.
     */
    public void destroy() {
        cancelQuitTask();
        cancelPendingDisconnect();
        stopStateAutosave();
        trackScheduler.clear();
        audioPlayer.destroy();
        playerMessage.delete();
        clearState();
    }

    /**
     * Releases runtime resources on graceful shutdown WITHOUT deleting the player message or the
     * persisted playback state. This lets the bot resume seamlessly after a restart: the saved
     * state is reloaded and the existing player message is reused (edited) rather than recreated.
     */
    public void release() {
        cancelQuitTask();
        cancelPendingDisconnect();
        stopStateAutosave();
        audioPlayer.destroy();
        playerMessage.stopUpdates();
    }

    // Getters
    public Guild getGuild() {
        return guild;
    }

    public AudioPlayer getAudioPlayer() {
        return audioPlayer;
    }

    public TrackScheduler getTrackScheduler() {
        return trackScheduler;
    }

    public MusicPlayerMessage getPlayerMessage() {
        return playerMessage;
    }

    public MusicPlugin getPlugin() {
        return plugin;
    }

    public int getVolume() {
        return volume;
    }

    /**
     * Sets the playback volume.
     */
    public void setVolume(int volume) {
        this.volume = Math.max(0, Math.min(100, volume));
        // Applied once, in lavaplayer (before Opus encoding). At 100 lavaplayer passes Opus frames through
        // untouched; the core pipeline relays Opus as-is, so the core-side volume is deliberately not set
        // (it would scale the audio a second time if this handler ever provided PCM).
        audioPlayer.setVolume(this.volume);

        playerMessage.refresh();
        saveState();
    }

    public boolean isPaused() {
        return audioPlayer.isPaused();
    }

    /**
     * Pauses or resumes playback.
     */
    public void setPaused(boolean paused) {
        audioPlayer.setPaused(paused);
        playerMessage.refresh();
        saveState();
    }

    public AudioTrack getPlayingTrack() {
        return audioPlayer.getPlayingTrack();
    }

    // Convenience action methods (centralize UI refresh logic)

    /**
     * Force a UI refresh of the player message.
     */
    public void refreshUi() {
        playerMessage.refresh();
    }

    /**
     * Toggle pause/resume.
     */
    public void togglePause() {
        setPaused(!isPaused());
    }

    /**
     * Skip current track.
     */
    public void skip() {
        skipTrack();
    }

    /**
     * Clear the queue only.
     */
    public void clearQueue() {
        trackScheduler.clear();
        playerMessage.refresh();
        saveState();
    }

    /**
     * Toggle single track loop; disables loop queue if enabled.
     */
    public void toggleLoop() {
        boolean enable = !trackScheduler.isLoopMode();
        trackScheduler.setLoopMode(enable);
        if (enable) {
            trackScheduler.setLoopQueueMode(false);
        }
        playerMessage.refresh();
        saveState();
    }

    /**
     * Toggle loop queue; disables single track loop if enabled.
     */
    public void toggleLoopQueue() {
        boolean enable = !trackScheduler.isLoopQueueMode();
        trackScheduler.setLoopQueueMode(enable);
        if (enable) {
            trackScheduler.setLoopMode(false);
        }
        playerMessage.refresh();
        saveState();
    }

    /**
     * Toggle shuffle mode.
     */
    public void toggleShuffle() {
        trackScheduler.setShuffleMode(!trackScheduler.isShuffleMode());
        playerMessage.refresh();
        saveState();
    }

    /**
     * Change volume by delta (clamped 0..100).
     */
    public void changeVolume(int delta) {
        setVolume(getVolume() + delta);
    }

    /**
     * Toggle mute (0) / default volume.
     */
    public void toggleMute() {
        if (getVolume() == 0) {
            setVolume(DEFAULT_VOLUME);
        } else {
            setVolume(0);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Playback state persistence (survives process restarts, e.g. Kubernetes pod rescheduling)
    // ---------------------------------------------------------------------------------------------

    /**
     * Captures the current playback state (voice/text channels, current track + position, queue,
     * modes and volume) into a serializable snapshot.
     */
    private PlaybackState captureState() {
        PlaybackState state = new PlaybackState();

        AudioChannel connected = guild.getAudioManager().getConnectedChannel();
        if (connected != null) {
            state.setVoiceChannelId(connected.getId());
        }
        Long textChannelId = playerMessage.getChannelId();
        if (textChannelId != null) {
            state.setTextChannelId(String.valueOf(textChannelId));
        }

        AudioPlayerManager playerManager = plugin.getMusicManager().getPlayerManager();

        AudioTrack current = audioPlayer.getPlayingTrack();
        if (current != null) {
            state.setCurrentTrack(TrackCodec.encode(playerManager, current));
            state.setCurrentPosition(current.getPosition());
        }

        List<String> encodedQueue = new ArrayList<>();
        for (AudioTrack track : trackScheduler.getQueue()) {
            String encoded = TrackCodec.encode(playerManager, track);
            if (encoded != null) {
                encodedQueue.add(encoded);
            }
        }
        state.setQueue(encodedQueue);

        state.setVolume(volume);
        state.setPaused(audioPlayer.isPaused());
        state.setLoopMode(trackScheduler.isLoopMode());
        state.setLoopQueueMode(trackScheduler.isLoopQueueMode());
        state.setShuffleMode(trackScheduler.isShuffleMode());
        state.setSavedAt(System.currentTimeMillis());
        return state;
    }

    /**
     * Persists the current playback state to guild storage. If there is nothing to resume
     * (no track and empty queue, or not connected), the stored state is removed instead.
     */
    public void saveState() {
        if (restoring) {
            // Avoid clobbering the persisted state while we are in the middle of restoring it.
            return;
        }
        if (!plugin.isPersistenceEnabled()) {
            return;
        }
        try {
            PlaybackState state = captureState();
            PluginGuildStorage storage = plugin.getPluginDataStorage().getGuildStorage(guild.getId());
            if (state.hasPlayback() && state.getVoiceChannelId() != null) {
                storage.set(STATE_KEY, state.toMap());
            } else {
                storage.remove(STATE_KEY);
            }
            plugin.getPluginDataStorage().saveAll();
        } catch (Exception e) {
            logger.warn("[{}] Failed to save playback state", guild.getName(), e);
        }
    }

    /**
     * Removes any persisted playback state for this guild.
     */
    public void clearState() {
        try {
            plugin.getPluginDataStorage().getGuildStorage(guild.getId()).remove(STATE_KEY);
            plugin.getPluginDataStorage().saveAll();
        } catch (Exception e) {
            logger.debug("[{}] Failed to clear playback state", guild.getName(), e);
        }
    }

    /**
     * Restores playback from a persisted state: reconnects to the voice channel, repopulates the
     * queue, and resumes the current track at its saved position.
     *
     * @param state the persisted state to restore
     */
    public void restoreFromState(PlaybackState state) {
        restoring = true;
        int restoredQueueSize = 0;
        try {
            // Restore modes and volume before starting anything.
            trackScheduler.setLoopMode(state.isLoopMode());
            trackScheduler.setLoopQueueMode(state.isLoopQueueMode());
            trackScheduler.setShuffleMode(state.isShuffleMode());
            this.volume = Math.max(0, Math.min(100, state.getVolume()));
            audioPlayer.setVolume(this.volume);

            // Restore the text channel used for the interactive player message.
            if (state.getTextChannelId() != null) {
                MessageChannel channel = guild.getTextChannelById(state.getTextChannelId());
                if (channel != null) {
                    setMessageChannel(channel);
                }
            }

            AudioPlayerManager playerManager = plugin.getMusicManager().getPlayerManager();

            // Reconnect to the voice channel; abort if it no longer exists.
            AudioChannel voiceChannel = resolveVoiceChannel(state.getVoiceChannelId());
            if (voiceChannel == null) {
                logger.warn("[{}] Saved voice channel {} no longer exists; aborting restore",
                        guild.getName(), state.getVoiceChannelId());
                clearState();
                return;
            }
            guild.getAudioManager().openAudioConnection(voiceChannel);

            // Register the send handler so frames are delivered once connected.
            plugin.getContext().getAudioService().registerSendHandler(
                    guild, plugin, sendHandler, volume, 50);

            // Repopulate the queue (behind the current track).
            List<AudioTrack> queueTracks = new ArrayList<>();
            for (String encoded : state.getQueue()) {
                AudioTrack track = TrackCodec.decode(playerManager, encoded);
                if (track != null) {
                    queueTracks.add(track);
                }
            }
            trackScheduler.restoreQueue(queueTracks);
            restoredQueueSize = queueTracks.size();

            // Resume the current track at its saved position, or start the queue if there is none.
            AudioTrack current = TrackCodec.decode(playerManager, state.getCurrentTrack());
            if (current != null) {
                long position = state.getCurrentPosition();
                if (position > 0 && current.isSeekable()) {
                    current.setPosition(position);
                }
                audioPlayer.startTrack(current, false);
                audioPlayer.setPaused(state.isPaused());
            } else if (!queueTracks.isEmpty()) {
                trackScheduler.nextTrack();
            }

            cancelQuitTask();
            ensureStateAutosave();
            playerMessage.refresh();
            logger.info("[{}] Restored playback ({} queued track(s))",
                    guild.getName(), restoredQueueSize);
        } catch (Exception e) {
            logger.warn("[{}] Failed to restore playback state", guild.getName(), e);
        } finally {
            restoring = false;
            // Persist the normalized state (position/paused/queue after restore).
            saveState();
        }
    }

    /**
     * Resolves a voice or stage channel by ID within this guild.
     */
    private AudioChannel resolveVoiceChannel(String channelId) {
        if (channelId == null) {
            return null;
        }
        AudioChannel channel = guild.getVoiceChannelById(channelId);
        if (channel == null) {
            channel = guild.getStageChannelById(channelId);
        }
        return channel;
    }

    /**
     * Starts the periodic state autosave (captures the live playback position) if not already running.
     */
    private void ensureStateAutosave() {
        if (!plugin.isPersistenceEnabled()) {
            return;
        }
        if (stateSaveTask != null && !stateSaveTask.isDone()) {
            return;
        }
        ScheduledExecutorService scheduler = plugin.getScheduler();
        stateSaveTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (audioPlayer.getPlayingTrack() != null || trackScheduler.getQueueSize() > 0) {
                    saveState();
                }
            } catch (Exception e) {
                logger.debug("[{}] Periodic state save failed", guild.getName(), e);
            }
        }, STATE_SAVE_INTERVAL_SECONDS, STATE_SAVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Stops the periodic state autosave.
     */
    private void stopStateAutosave() {
        if (stateSaveTask != null && !stateSaveTask.isDone()) {
            stateSaveTask.cancel(false);
        }
        stateSaveTask = null;
    }
}