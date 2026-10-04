package fr.farmvivi.fluxcord.plugins.music.player;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages the track queue and playback behavior for a music player.
 *
 * <p>This scheduler handles different playback modes including:
 * <ul>
 *   <li>Normal mode: plays tracks sequentially and removes them after playing</li>
 *   <li>Loop mode: repeats the current track indefinitely</li>
 *   <li>Loop queue mode: repeats the entire queue when reaching the end</li>
 *   <li>Shuffle mode: plays tracks in random order</li>
 * </ul>
 *
 * <p>The scheduler maintains a single synchronized queue for all operations to ensure
 * thread safety and consistency.
 */
public class TrackScheduler extends AudioEventAdapter {
    private static final Logger logger = LoggerFactory.getLogger(TrackScheduler.class);

    private final MusicPlayer musicPlayer;
    private final AudioPlayer player;

    // Single synchronized list for queue management
    private final List<AudioTrack> queue;

    // Track history for loop queue mode
    private final List<AudioTrack> playedTracks;
    // Random instance for shuffle mode
    private final Random random = new Random();
    // Playback modes
    private boolean loopMode = false;
    private boolean loopQueueMode = false;
    /** The last track given a second chance, so one retry never becomes an endless one. */
    private String lastRetried;
    private boolean shuffleMode = false;

    /**
     * Creates a new TrackScheduler for the given music player.
     *
     * @param musicPlayer the music player that owns this scheduler
     * @param player      the LavaPlayer audio player instance
     */
    public TrackScheduler(MusicPlayer musicPlayer, AudioPlayer player) {
        this.musicPlayer = musicPlayer;
        this.player = player;
        this.queue = Collections.synchronizedList(new LinkedList<>());
        this.playedTracks = Collections.synchronizedList(new ArrayList<>());
    }

    /**
     * Adds a track to the queue or plays it immediately if nothing is playing.
     *
     * @param track the audio track to queue
     * @return true if the track started playing immediately, false if it was queued
     */
    public boolean queue(AudioTrack track) {
        // Attempt to play immediately if nothing is playing
        if (!player.startTrack(track, true)) {
            // If something is already playing, add to queue
            queue.add(track);
            logger.debug("[{}] Added track to queue: {}", getGuildName(), track.getInfo().title);
            return false;
        }
        logger.debug("[{}] Started playing track immediately: {}", getGuildName(), track.getInfo().title);
        return true;
    }

    /**
     * Plays a track immediately, interrupting current playback.
     * If a track is currently playing, it will be added to the front of the queue.
     *
     * @param track the audio track to play immediately
     */
    public void playNow(AudioTrack track) {
        AudioTrack currentTrack = player.getPlayingTrack();
        if (currentTrack != null) {
            // Add current track back to the front of the queue
            queue.add(0, currentTrack.makeClone());
            logger.debug("[{}] Moved current track to queue front: {}", getGuildName(), currentTrack.getInfo().title);
        }
        player.startTrack(track, false);
    }

    /**
     * Adds a track at the end of the queue.
     * This method is primarily used internally for loop functionality.
     *
     * @param track the audio track to add to the queue
     */
    private void addToQueueEnd(AudioTrack track) {
        if (track == null) {
            return;
        }
        queue.add(track);
        logger.debug("[{}] Added track to queue end: {}", getGuildName(), track.getInfo().title);
    }

    /**
     * Starts the next track in the queue.
     * Handles different playback modes appropriately.
     */
    public void nextTrack() {
        AudioTrack next = null;

        if (!queue.isEmpty()) {
            if (shuffleMode) {
                // Pick a random track from the queue
                int randomIndex = random.nextInt(queue.size());
                next = queue.remove(randomIndex);
                logger.debug("[{}] Picked random track from queue at index {}", getGuildName(), randomIndex);
            } else {
                // Normal mode: take the first track
                next = queue.remove(0);
            }
        }

        if (next != null) {
            player.startTrack(next, false);
            logger.debug("[{}] Started next track: {}", getGuildName(), next.getInfo().title);
        } else if (loopQueueMode && hasPlayedTracks()) {
            // Loop queue mode: restart from the beginning
            logger.debug("[{}] Loop queue mode: restarting queue", getGuildName());
            restartQueue();
        } else {
            logger.debug("[{}] No more tracks to play", getGuildName());
        }
    }

    /**
     * Skips the current track.
     *
     * <p>In loop mode, the current track is added back to the end of the queue
     * instead of being discarded. This allows the track to be played again later
     * in the queue cycle.
     */
    public void skip() {
        AudioTrack current = player.getPlayingTrack();

        if (current != null) {
            logger.debug("[{}] Skipping track: {}", getGuildName(), current.getInfo().title);

            if (loopMode || loopQueueMode) {
                // Either loop keeps the track: skipping is "not this one now", not "never again". With the
                // queue loop on, relying on the played-tracks history instead meant a skipped track only
                // came back once the whole queue had drained, which is not what looping a playlist looks
                // like to somebody listening.
                addToQueueEnd(current.makeClone());
                logger.debug("[{}] A loop is active: added the skipped track back to the queue",
                        getGuildName());
            }
        }

        // Stop the current track and play the next one
        player.stopTrack();
        nextTrack();
    }

    /**
     * Restores tracks into the queue without starting playback.
     *
     * <p>Used when reloading a persisted state: the current track is started separately by
     * {@link MusicPlayer}, while the remaining queue is repopulated here in order.
     *
     * @param tracks the tracks to append to the queue
     */
    public void restoreQueue(List<AudioTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        for (AudioTrack track : tracks) {
            if (track != null) {
                queue.add(track);
            }
        }
        logger.debug("[{}] Restored {} tracks into the queue", getGuildName(), tracks.size());
    }

    /**
     * Clears the queue and played tracks history.
     */
    public void clear() {
        queue.clear();
        playedTracks.clear();
        logger.debug("[{}] Queue and history cleared", getGuildName());
    }

    /**
     * Gets the current queue size.
     *
     * @return the number of tracks in the queue
     */
    public int getQueueSize() {
        return queue.size();
    }

    /**
     * Gets a copy of the current queue for display purposes.
     *
     * @return an immutable copy of the queue
     */
    public List<AudioTrack> getQueue() {
        return new ArrayList<>(queue);
    }

    /**
     * Removes a track at the specified index.
     *
     * @param index the zero-based index of the track to remove
     * @return true if the track was successfully removed, false if the index was invalid
     */
    public boolean removeTrack(int index) {
        if (index < 0 || index >= queue.size()) {
            return false;
        }

        AudioTrack removed = queue.remove(index);
        logger.debug("[{}] Removed track at index {}: {}", getGuildName(), index, removed.getInfo().title);
        return true;
    }

    /**
     * Moves a track from one position to another in the queue.
     *
     * @param from the current index of the track
     * @param to   the target index for the track
     * @return true if the track was successfully moved, false if either index was invalid
     */
    public boolean moveTrack(int from, int to) {
        if (from < 0 || from >= queue.size() || to < 0 || to >= queue.size() || from == to) {
            return false;
        }

        AudioTrack track = queue.remove(from);
        queue.add(to, track);
        logger.debug("[{}] Moved track from index {} to {}: {}", getGuildName(), from, to, track.getInfo().title);
        return true;
    }

    // Event handlers

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        logger.debug("[{}] Track ended: {} - Reason: {}", getGuildName(), track.getInfo().title, endReason);

        // Only start the next track if the end reason is suitable for it
        if (endReason.mayStartNext) {
            if (loopMode && endReason != AudioTrackEndReason.STOPPED) {
                // Loop mode: replay the same track
                player.startTrack(track.makeClone(), false);
                logger.debug("[{}] Loop mode: replaying track", getGuildName());
            } else {
                // Normal end: play next track
                nextTrack();
            }
        }

        musicPlayer.refreshUi();
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        logger.error("[{}] Track exception for {}: {}", getGuildName(), track.getInfo().title,
                exception.getMessage());

        // Tried again once before giving up on it.
        //
        // MEASURED, 2026-10-04: about one stream request in ten dies on YouTube's signature cipher, and the
        // same track plays on the next attempt - so the failure is transient and dropping the track threw
        // away something that worked. It showed up worst on a restart: resuming a session re-resolves the
        // stream of whatever was playing, so the current track is the one most exposed, and "the bot skips
        // the music when it reconnects" is this, not the restore. Victor reported it twice.
        //
        // Once, not repeatedly: a track that is genuinely gone would otherwise loop forever, and the second
        // attempt is a fresh request that either works or says the same thing again.
        String identifier = track.getInfo().identifier;
        if (identifier != null && !identifier.equals(lastRetried)) {
            lastRetried = identifier;
            logger.info("[{}] Trying {} once more from {} ms in; that failure is usually transient",
                    getGuildName(), track.getInfo().title, track.getPosition());
            AudioTrack again = track.makeClone();
            // A clone starts at zero, so the position has to be carried over: without this the retry
            // restarted the song from the beginning, which Victor spotted immediately - the right track,
            // the wrong place in it.
            long position = track.getPosition();
            if (position > 0 && again.isSeekable()) {
                again.setPosition(position);
            }
            player.startTrack(again, false);
            musicPlayer.refreshUi();
            return;
        }
        logger.warn("[{}] Giving up on {} after a second failure", getGuildName(), track.getInfo().title);
        nextTrack();
        musicPlayer.refreshUi();
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        logger.info("[{}] Started playing: {}", getGuildName(), track.getInfo().title);

        // Freed only when a DIFFERENT track starts. Clearing it here for the same one would have been an
        // endless loop: the retry itself starts the track, so the budget would be handed back before the
        // second failure could ever use it up.
        if (lastRetried != null && !lastRetried.equals(track.getInfo().identifier)) {
            lastRetried = null;
        }

        // Add to played tracks history for loop queue mode
        if (loopQueueMode && !playedTracks.contains(track)) {
            playedTracks.add(track.makeClone());
            logger.debug("[{}] Added track to history for loop queue mode", getGuildName());
        }

        musicPlayer.refreshUi();
    }

    // Playback mode getters and setters

    /**
     * Checks if loop mode is enabled.
     * In loop mode, the current track repeats indefinitely.
     *
     * @return true if loop mode is enabled
     */
    public boolean isLoopMode() {
        return loopMode;
    }

    /**
     * Sets the loop mode state.
     *
     * @param loopMode true to enable loop mode, false to disable
     */
    public void setLoopMode(boolean loopMode) {
        this.loopMode = loopMode;
        logger.debug("[{}] Loop mode set to: {}", getGuildName(), loopMode);
    }

    /**
     * Checks if loop queue mode is enabled.
     * In loop queue mode, the entire queue repeats when reaching the end.
     *
     * @return true if loop queue mode is enabled
     */
    public boolean isLoopQueueMode() {
        return loopQueueMode;
    }

    /**
     * Sets the loop queue mode state.
     *
     * @param loopQueueMode true to enable loop queue mode, false to disable
     */
    public void setLoopQueueMode(boolean loopQueueMode) {
        this.loopQueueMode = loopQueueMode;
        if (!loopQueueMode) {
            // Clear history when disabling loop queue mode
            playedTracks.clear();
        }
        logger.debug("[{}] Loop queue mode set to: {}", getGuildName(), loopQueueMode);
    }

    /**
     * Checks if shuffle mode is enabled.
     * In shuffle mode, tracks are played in random order.
     *
     * @return true if shuffle mode is enabled
     */
    public boolean isShuffleMode() {
        return shuffleMode;
    }

    /**
     * Sets the shuffle mode state.
     *
     * @param shuffleMode true to enable shuffle mode, false to disable
     */
    public void setShuffleMode(boolean shuffleMode) {
        this.shuffleMode = shuffleMode;
        logger.debug("[{}] Shuffle mode set to: {}", getGuildName(), shuffleMode);
    }

    // Private utility methods

    /**
     * Gets the guild name for logging purposes.
     *
     * @return the name of the guild
     */
    private String getGuildName() {
        return musicPlayer.getGuild().getName();
    }

    /**
     * Checks if any tracks have been played in this session.
     * Used for loop queue mode to determine if we should restart.
     *
     * @return true if at least one track has been played
     */
    private boolean hasPlayedTracks() {
        return !playedTracks.isEmpty();
    }

    /**
     * Restarts the queue for loop queue mode.
     * This method repopulates the queue with previously played tracks.
     */
    private void restartQueue() {
        if (playedTracks.isEmpty()) {
            logger.warn("[{}] No tracks in history to restart queue", getGuildName());
            return;
        }

        // Clone all played tracks and add them back to the queue
        for (AudioTrack track : playedTracks) {
            queue.add(track.makeClone());
        }

        logger.info("[{}] Restarted queue with {} tracks", getGuildName(), playedTracks.size());

        // Clear history to start fresh for the next cycle
        playedTracks.clear();

        // Start playing the first track
        nextTrack();
    }
}