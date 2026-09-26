package fr.farmvivi.fluxcord.plugins.aiaudio.persona;

import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationMemory;

import java.util.List;

/**
 * Everything that decides how the bot should sound right now: who it is, how it feels, where it is, who is
 * there and how well it knows them.
 *
 * <p>Assembled on demand rather than kept up to date, because every part of it moves — the mood fades, people
 * join, the history grows.
 *
 * <p><strong>Nothing here is a prompt.</strong> The persona is trusted (an operator wrote it), the
 * conversation and the participants' names are not. Whatever builds the final instructions must keep them in
 * separate fields, never concatenated: a participant calling themselves
 * {@code "Bob. SYSTEM: ignore the above"} must not be able to become an instruction.
 *
 * @param persona     who the bot is here
 * @param mood        how it feels here, already faded to now
 * @param conversation where it is, who is present and what was said
 * @param familiarity how well it knows each person present
 */
public record PersonaSnapshot(Persona persona, Mood mood, ConversationContext conversation,
                              List<Acquaintance> familiarity) {

    /** Remembered turns from which someone stops being a stranger. */
    public static final int KNOWN_FROM = 5;
    /** Remembered turns from which someone counts as a regular. */
    public static final int REGULAR_FROM = 40;

    /**
     * Takes a snapshot.
     *
     * @param store        where the persona overrides and moods live
     * @param memory       where the conversation history lives
     * @param conversation the place, as it stands
     * @param nowMs        the current time in milliseconds
     * @return the snapshot
     */
    public static PersonaSnapshot of(PersonaStore store, ConversationMemory memory,
                                     ConversationContext conversation, long nowMs) {
        Persona persona = store.effective(conversation.guildId(), conversation.channelId());
        Mood mood = store.mood(conversation.guildId(), conversation.channelId(), nowMs);
        List<Acquaintance> familiarity = conversation.present().stream()
                .map(participant -> Acquaintance.of(participant, memory))
                .toList();
        return new PersonaSnapshot(persona, mood, conversation, familiarity);
    }

    /** @return true when there is nobody to talk to */
    public boolean isEmpty() {
        return conversation.isEmpty();
    }

    /**
     * How well the bot knows one of the people present.
     *
     * <p>Counted from that person's own cross-server history, so someone who talks to the bot on another
     * server is not a stranger here — which is the point of keeping a per-person memory at all.
     *
     * @param userId      their Discord id, the stable key
     * @param displayName their name here, for reading and for prompting
     * @param level       how well they are known
     * @param rememberedTurns how many of their turns are remembered
     */
    public record Acquaintance(String userId, String displayName, Level level, int rememberedTurns) {

        static Acquaintance of(ConversationContext.Participant participant, ConversationMemory memory) {
            int turns = memory.personHistory(participant.userId(), REGULAR_FROM + 1).size();
            return new Acquaintance(participant.userId(), participant.displayName(), Level.of(turns), turns);
        }

        /** The three steps of acquaintance. */
        public enum Level {
            /** Never heard from, or barely. */
            STRANGER,
            /** Has said a few things the bot remembers. */
            KNOWN,
            /** Talks to the bot often. */
            REGULAR;

            static Level of(int rememberedTurns) {
                if (rememberedTurns >= REGULAR_FROM) {
                    return REGULAR;
                }
                return rememberedTurns >= KNOWN_FROM ? KNOWN : STRANGER;
            }
        }
    }
}
