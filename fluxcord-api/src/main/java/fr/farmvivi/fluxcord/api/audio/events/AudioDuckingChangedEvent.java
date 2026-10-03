package fr.farmvivi.fluxcord.api.audio.events;

import net.dv8tion.jda.api.entities.Guild;

/**
 * Fired when a high-priority source takes the floor in a guild, and again when it gives it back.
 *
 * <p>The pipeline already lowers the other sources by itself; this event exists for what it cannot do for
 * them. A music player that is merely turned down keeps <em>playing</em>: the track runs on while nobody can
 * hear it, so a spoken answer costs the listener the seconds of the song it covered. Only the player can fix
 * that, by pausing, and until this event existed it had no way of knowing it should.
 *
 * <p>Deliberately says who took the floor rather than what to do about it. A plugin that wants to pause can
 * pause, one that wants to keep going can ignore it, and neither has to know which other plugin is speaking —
 * the music plugin does not depend on the AI plugin, and would behave the same for any future source that
 * outranks it.
 *
 * <p>Fired on the audio thread, once per change and not per frame, so a listener must return promptly: 20 ms
 * is the whole budget. Pausing a player is a flag, which is fine; anything that blocks is not.
 */
public class AudioDuckingChangedEvent extends AudioEvent {

    private final String duckingPlugin;
    private final boolean ducking;

    /**
     * @param guild         the guild
     * @param duckingPlugin the name of the plugin that has the floor, or the one that just gave it up
     * @param ducking       true when that source took the floor, false when the others may come back up
     */
    public AudioDuckingChangedEvent(Guild guild, String duckingPlugin, boolean ducking) {
        super(guild);
        this.duckingPlugin = duckingPlugin;
        this.ducking = ducking;
    }

    /**
     * @return the plugin whose audio is the reason the others were turned down
     */
    public String getDuckingPlugin() {
        return duckingPlugin;
    }

    /**
     * @return true when a priority source started talking, false when it stopped
     */
    public boolean isDucking() {
        return ducking;
    }
}
