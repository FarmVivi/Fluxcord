package fr.farmvivi.fluxcord.plugins.aiaudio.memory;

import java.time.Instant;

/**
 * One thing that was said, by one person, somewhere.
 *
 * <p>Both identity fields are kept on purpose: {@code userId} is the stable key — a nickname changes,
 * and the same person has a different one on every server — while {@code speaker} is the name to show
 * and to hand to a model. Same for the place: {@code guildId} identifies it, the names make it readable.
 *
 * <p>A record, and not a list of records, is what goes into storage: {@code ScopedStorage.get} takes a
 * {@code Class}, so only a non-generic type round-trips reliably through Gson.
 *
 * @param timestampMs when it was said, epoch milliseconds
 * @param userId      the Discord id of the speaker, stable across servers and renames
 * @param speaker     their display name where they said it, for reading and for prompting
 * @param guildId     the Discord id of the server
 * @param guildName   the server's name at the time
 * @param channelId   the Discord id of the voice channel
 * @param channelName the voice channel's name at the time
 * @param text        what was transcribed
 */
public record Turn(long timestampMs, String userId, String speaker,
                   String guildId, String guildName, String channelId, String channelName, String text) {

    /**
     * Builds a turn timestamped now.
     *
     * @param userId      the speaker's id
     * @param speaker     the speaker's display name
     * @param guildId     the server's id
     * @param guildName   the server's name
     * @param channelId   the voice channel's id
     * @param channelName the voice channel's name
     * @param text        what was said
     * @return the turn
     */
    public static Turn now(String userId, String speaker, String guildId, String guildName,
                           String channelId, String channelName, String text) {
        return new Turn(System.currentTimeMillis(), userId, speaker, guildId, guildName,
                channelId, channelName, text);
    }

    /**
     * The storage key of this turn.
     *
     * <p>Timestamp first so keys sort chronologically, and the speaker appended so two people talking in
     * the same millisecond do not overwrite each other. Epoch milliseconds are 13 digits until the year
     * 2286, so a plain lexicographic sort is also a chronological one.
     *
     * @return the key to store this turn under
     */
    public String storageKey() {
        return timestampMs + "-" + userId;
    }

    /** @return when it was said */
    public Instant instant() {
        return Instant.ofEpochMilli(timestampMs);
    }
}
