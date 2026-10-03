package fr.farmvivi.fluxcord.plugins.music;

import fr.farmvivi.fluxcord.api.audio.events.AudioDuckingChangedEvent;
import fr.farmvivi.fluxcord.api.event.EventHandler;
import fr.farmvivi.fluxcord.plugins.music.player.MusicPlayer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pauses the music while something more important is talking, and starts it again afterwards.
 *
 * <p>The core already turns the other sources down on its own, which is enough for anything live: a sound
 * nobody hears is a sound nobody needed. A song is not like that. Turned down, it goes on playing, so a
 * twelve-second spoken answer costs the listener twelve seconds of the track — and those seconds are gone,
 * which is exactly what Victor asked not to happen. Pausing is the only thing that gives them back, and only
 * the player can do it.
 *
 * <p><strong>A pause this class did not cause is never undone.</strong> Somebody who ran {@code /pause}
 * before the bot spoke would otherwise find the music running again when it stopped, having been overruled
 * by a side effect — so the guilds paused here are remembered, and only those are resumed.
 *
 * <p>Runs on the audio thread with a 20 ms budget for the whole frame. Everything here is a flag and a set
 * membership; nothing blocks, nothing does I/O.
 */
public class DuckingPauseListener {

    /** This plugin's id - the pipeline keys its sources by id - so its own turn to speak is ignored. */
    private final String ownId;
    private final MusicManager musicManager;
    /** Guilds paused by this class, and therefore the only ones it may resume. */
    private final Set<String> pausedByUs = ConcurrentHashMap.newKeySet();

    public DuckingPauseListener(String ownId, MusicManager musicManager) {
        this.ownId = ownId;
        this.musicManager = musicManager;
    }

    @EventHandler
    public void onDuckingChanged(AudioDuckingChangedEvent event) {
        if (ownId.equals(event.getDuckingPlugin())) {
            // The music is what has the floor; there is nothing to get out of the way of.
            return;
        }
        String guildId = event.getGuild().getId();
        // findPlayer and not getPlayer: creating a player because somebody else spoke would open a voice
        // connection for a guild that has no music at all.
        MusicPlayer player = musicManager.findPlayer(guildId).orElse(null);
        if (player == null) {
            return;
        }
        if (event.isDucking()) {
            if (!player.isPaused() && pausedByUs.add(guildId)) {
                player.setPaused(true);
            }
        } else if (pausedByUs.remove(guildId)) {
            player.setPaused(false);
        }
    }

    /** @param guildId the guild
     *  @return true while this class is the reason that guild's music is paused */
    public boolean pausedFor(String guildId) {
        return pausedByUs.contains(guildId);
    }
}
