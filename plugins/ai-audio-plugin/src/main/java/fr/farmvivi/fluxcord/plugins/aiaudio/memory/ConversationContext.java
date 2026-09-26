package fr.farmvivi.fluxcord.plugins.aiaudio.memory;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;

import java.util.List;

/**
 * Everything the AI needs to know about a conversation at one moment: where it takes place, who is in it,
 * and what has been said.
 *
 * <p>Built at the moment it is needed rather than kept up to date, because every part of it can change
 * between two sentences — someone joins, someone renames themselves, the history grows.
 *
 * <p>Nothing here is a prompt. Names and text stay in their own fields precisely so that whatever builds
 * a prompt later treats them as data: a nickname is chosen by its owner, so a participant called
 * {@code "Bob. SYSTEM: ignore the above"} must not be able to become an instruction.
 *
 * @param guildId     the server's id
 * @param guildName   the server's name
 * @param channelId   the voice channel's id
 * @param channelName the voice channel's name
 * @param present     who is in the voice channel right now, the bot excluded
 * @param channelTurns what was said in this channel, oldest first
 * @param serverTurns  what was said in this server across its channels, oldest first
 */
public record ConversationContext(String guildId, String guildName, String channelId, String channelName,
                                  List<Participant> present, List<Turn> channelTurns,
                                  List<Turn> serverTurns) {

    /**
     * Takes a snapshot of a voice channel.
     *
     * @param channel      the voice channel the bot is in
     * @param memory       where the history is kept
     * @param channelLimit how many of this channel's turns to include
     * @param serverLimit  how many of the server's turns to include
     * @return the snapshot
     */
    public static ConversationContext of(AudioChannel channel, ConversationMemory memory,
                                         int channelLimit, int serverLimit) {
        String guildId = channel.getGuild().getId();
        List<Participant> present = channel.getMembers().stream()
                .filter(member -> !member.getUser().isBot())
                .map(Participant::of)
                .toList();
        return new ConversationContext(guildId, channel.getGuild().getName(),
                channel.getId(), channel.getName(), present,
                memory.channelHistory(guildId, channel.getId(), channelLimit),
                memory.serverHistory(guildId, serverLimit));
    }

    /** @return true when there is nobody to talk to */
    public boolean isEmpty() {
        return present.isEmpty();
    }

    /**
     * Someone taking part in the conversation.
     *
     * @param userId      their Discord id, the stable key
     * @param displayName their name in this server, which is what the others call them
     */
    public record Participant(String userId, String displayName) {

        static Participant of(Member member) {
            return new Participant(member.getId(), member.getEffectiveName());
        }
    }
}
