package fr.farmvivi.fluxcord.plugins.aiaudio.memory;

import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import fr.farmvivi.fluxcord.api.storage.ScopedStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What was said, kept so the AI can refer back to it.
 *
 * <p>Three independent histories, because they answer three different questions:
 * <ul>
 *   <li><strong>channel</strong>: the conversation in progress — what was said in <em>this</em> voice
 *       channel, by everyone, a moment ago;
 *   <li><strong>server</strong>: the same, but across every channel of the server — the longer-running
 *       memory of a community;
 *   <li><strong>person</strong>: someone's own history, followed <strong>across servers</strong>. Each of
 *       their turns keeps the server and channel it was said in, so the AI can remember what somebody
 *       told it elsewhere without mixing the two places up.
 * </ul>
 * Each is sized on its own and a limit of zero turns it off, so a bot can keep a live conversation
 * without building any long-term record of its users.
 *
 * <p>One storage key per turn rather than one list per scope: {@link ScopedStorage#get} takes a
 * {@code Class}, not a type token, so a {@code List<Turn>} would come back as raw maps. A {@link Turn}
 * is not generic, so it round-trips through Gson as itself, and trimming a history becomes deleting the
 * oldest keys.
 *
 * <p>Speaker names are stored as data and never as instructions: a Discord nickname is chosen by its
 * owner, so whatever is built for a model must keep names in their own fields rather than splicing them
 * into a prompt.
 */
public class ConversationMemory {

    /** Sub-namespace of the guild scope holding one channel's conversation, plus the channel id. */
    static final String CHANNEL_NAMESPACE = "conversation";
    /** Sub-namespace of the guild scope holding the whole server's history. */
    static final String SERVER_NAMESPACE = "server";
    /** Sub-namespace of the user scope holding someone's cross-server history. */
    static final String USER_NAMESPACE = "memory";

    private static final Logger logger = LoggerFactory.getLogger(ConversationMemory.class);

    private final PluginDataStorageAdapter storage;
    private final int channelTurns;
    private final int serverTurns;
    private final int userTurns;

    /**
     * @param storage      the plugin's namespaced storage
     * @param channelTurns how many turns to keep per voice channel, 0 to keep no channel history
     * @param serverTurns  how many turns to keep per server across its channels, 0 to keep none
     * @param userTurns    how many turns to keep per person across every server, 0 to keep none
     */
    public ConversationMemory(PluginDataStorageAdapter storage, int channelTurns, int serverTurns,
                              int userTurns) {
        this.storage = storage;
        this.channelTurns = Math.max(0, channelTurns);
        this.serverTurns = Math.max(0, serverTurns);
        this.userTurns = Math.max(0, userTurns);
    }

    /** @return true when nothing is recorded at all */
    public boolean isDisabled() {
        return channelTurns == 0 && serverTurns == 0 && userTurns == 0;
    }

    /**
     * Records one thing that was said, in each history that is enabled.
     *
     * @param turn what was said, by whom and where
     */
    public void remember(Turn turn) {
        if (turn.text() == null || turn.text().isBlank()) {
            return;
        }
        if (channelTurns > 0 && turn.guildId() != null && turn.channelId() != null) {
            write(channelScope(turn.guildId(), turn.channelId()), turn, channelTurns);
        }
        if (serverTurns > 0 && turn.guildId() != null) {
            write(serverScope(turn.guildId()), turn, serverTurns);
        }
        if (userTurns > 0 && turn.userId() != null) {
            write(userScope(turn.userId()), turn, userTurns);
        }
    }

    /**
     * The conversation of one voice channel, oldest first.
     *
     * @param guildId   the server
     * @param channelId the voice channel
     * @param limit     how many turns at most, the most recent ones
     * @return the turns, oldest first
     */
    public List<Turn> channelHistory(String guildId, String channelId, int limit) {
        return read(channelScope(guildId, channelId), limit);
    }

    /**
     * Everything said in one server, across its channels, oldest first.
     *
     * @param guildId the server
     * @param limit   how many turns at most, the most recent ones
     * @return the turns, oldest first
     */
    public List<Turn> serverHistory(String guildId, int limit) {
        return read(serverScope(guildId), limit);
    }

    /**
     * Everything one person said, in any server, oldest first.
     *
     * @param userId the person
     * @param limit  how many turns at most, the most recent ones
     * @return the turns, oldest first
     */
    public List<Turn> personHistory(String userId, int limit) {
        return read(userScope(userId), limit);
    }

    /**
     * Forgets a person's own history. Their turns stay in the channel and server histories, which belong
     * to the conversation rather than to them — {@link #forgetChannel} and {@link #forgetServer} clear
     * those.
     *
     * @param userId the person to forget
     * @return true when something was deleted
     */
    public boolean forgetPerson(String userId) {
        return userScope(userId).clear();
    }

    /**
     * Forgets one voice channel's conversation.
     *
     * @param guildId   the server
     * @param channelId the voice channel
     * @return true when something was deleted
     */
    public boolean forgetChannel(String guildId, String channelId) {
        return channelScope(guildId, channelId).clear();
    }

    /**
     * Forgets a whole server's history, every channel included.
     *
     * @param guildId the server
     * @return true when something was deleted
     */
    public boolean forgetServer(String guildId) {
        return serverScope(guildId).clear();
    }

    private ScopedStorage channelScope(String guildId, String channelId) {
        return storage.getGuildStorage(guildId).namespaced(CHANNEL_NAMESPACE + "." + channelId);
    }

    private ScopedStorage serverScope(String guildId) {
        return storage.getGuildStorage(guildId).namespaced(SERVER_NAMESPACE);
    }

    private ScopedStorage userScope(String userId) {
        return storage.getUserStorage(userId).namespaced(USER_NAMESPACE);
    }

    private void write(ScopedStorage scope, Turn turn, int keep) {
        try {
            scope.set(turn.storageKey(), turn);
            trim(scope, keep);
        } catch (RuntimeException e) {
            // A full disk or an unreachable database must not stop the bot from talking.
            logger.warn("Could not record a conversation turn: {}", e.getMessage());
        }
    }

    /** Keeps the newest {@code keep} keys. Keys sort chronologically by construction. */
    private void trim(ScopedStorage scope, int keep) {
        List<String> keys = new ArrayList<>(scope.getKeys());
        if (keys.size() <= keep) {
            return;
        }
        keys.sort(Comparator.naturalOrder());
        keys.subList(0, keys.size() - keep).forEach(scope::remove);
    }

    private List<Turn> read(ScopedStorage scope, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<Turn> turns = new ArrayList<>();
        try {
            for (String key : scope.getKeys()) {
                Optional<Turn> turn = scope.get(key, Turn.class);
                turn.ifPresent(turns::add);
            }
        } catch (RuntimeException e) {
            logger.warn("Could not read the conversation history: {}", e.getMessage());
            return List.of();
        }
        turns.sort(Comparator.comparingLong(Turn::timestampMs));
        return turns.size() <= limit ? turns : new ArrayList<>(turns.subList(turns.size() - limit, turns.size()));
    }
}
