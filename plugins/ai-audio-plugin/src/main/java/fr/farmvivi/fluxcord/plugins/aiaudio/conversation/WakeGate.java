package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationMemory;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;

/**
 * Decides when the bot starts paying to listen.
 *
 * <p>A full-duplex session bills every second of speech it is given. Left open in a channel where eight
 * people are talking, it is paid to overhear a conversation it is not part of — measured at about $0.36 an
 * hour of speech on the cheaper OpenAI model. This class is the answer, and it is the same shape a smart
 * speaker uses: <strong>the free local pipeline listens, and the hosted one is only opened once the bot has
 * been addressed.</strong>
 *
 * <p>So there are two states per guild, and the switch between them is a diversion of the audio at the one
 * receive handler the core allows:
 * <ul>
 *   <li><strong>listening</strong> — the local transcriber hears everything, for nothing, on the operator's
 *       own hardware. Every sentence is still written down and remembered; what the bot's name decides is
 *       only whether a hosted session is opened.
 *   <li><strong>engaged</strong> — the packets go to the hosted session instead, which is what makes it
 *       full duplex: it hears while it talks and can be cut off. The local transcriber sees nothing, because
 *       transcribing the same sentence twice would cost twice and remember it twice.
 * </ul>
 *
 * <p><strong>The session is told what it missed.</strong> The sentence containing the name was heard before
 * the session existed, so it is handed over as text along with the last few turns — otherwise the bot would
 * open a connection and wait for a question that had already been asked. This is the documented way to
 * restore context on these APIs: replay the conversation as items, capped, rather than hope the service
 * kept something it never had.
 *
 * <p>Engagement ends after a quiet window rather than at the end of the answer, so a follow-up needs no
 * name — which is the whole difference between a bot you talk to and a bot you summon.
 *
 * <p><strong>It also ends when the service hangs up</strong>, which both of them do on their own schedule.
 * That used to leave a conversation dead until somebody ran the command again; here it is a pause, because
 * reopening is the thing this class already does and the context it hands over is the same.
 */
public class WakeGate {

    /** How many earlier turns a freshly opened session is given. Enough to follow, cheap enough to send. */
    static final int HISTORY_TURNS = 8;

    private final Logger logger;
    private final LongSupplier clock;
    private final ConversationMemory memory;
    private final Engagement engagement;
    private final long windowMs;
    /** Guilds whose audio is currently going to the hosted session. */
    private final Map<String, Guild> engaged = new ConcurrentHashMap<>();

    /**
     * What the gate needs from the two services, as functions rather than as the services themselves.
     *
     * <p>Not for purity: {@code AIAudioPlugin} builds the transcription service last, so a gate holding
     * either one directly would have to be built after both and before neither. Functions resolve when
     * called.
     *
     * @param open          opens a hosted session for a guild without registering a receive handler, and
     *                      answers false when one is already open or there is no voice channel
     * @param close         closes it again
     * @param divert        sends a guild's audio somewhere other than the local transcriber, or back
     * @param hear          hands one packet to the hosted session
     * @param ask           hands it a question it could not have heard, with the turns before it
     * @param lastActivity  when anything last happened on that session, in milliseconds
     * @param alive         whether the session still has a connection, which it stops having when the
     *                      service hangs up on its own schedule
     */
    public record Engagement(java.util.function.BiPredicate<Guild, MessageChannel> open,
                             java.util.function.Consumer<Guild> close,
                             java.util.function.BiFunction<Guild, BiConsumer<String, PcmAudio>, Boolean> divert,
                             TriConsumer hear,
                             AskSession ask,
                             java.util.function.ToLongFunction<Guild> lastActivity,
                             java.util.function.Predicate<Guild> alive) {

        /** One audio packet, attributed. */
        public interface TriConsumer {
            void accept(Guild guild, String userId, PcmAudio audio);
        }

        /**
         * A question the session could not have heard, with what was said before it.
         *
         * <p>The question travels as a {@link Turn} and not as its text, because it carries who asked it.
         * A session is opened by somebody saying the bot's name, and that somebody is then the only person
         * whose permissions a command could run under; handing over the words alone left the session with
         * no attributed speaker until the next audio packet arrived, so the very question that woke the bot
         * was the one question that could never run a command.
         */
        public interface AskSession {
            void ask(Guild guild, Turn question, List<Turn> history);
        }
    }

    /**
     * @param logger   where to put the trail of what was paid for and when
     * @param clock    the current time in milliseconds
     * @param memory   where the history handed to a new session comes from
     * @param windowMs how long a session stays open after the last thing said or answered
     */
    public WakeGate(Logger logger, LongSupplier clock, ConversationMemory memory, Engagement engagement,
                    long windowMs) {
        this.logger = logger;
        this.clock = clock;
        this.memory = memory;
        this.engagement = engagement;
        this.windowMs = windowMs;
    }

    /**
     * Something was said, locally transcribed, and addressed to the bot.
     *
     * @param guild    where
     * @param output   where to report a failure to open the session
     * @param question the sentence, which the session will be handed because it did not hear it
     * @return true when this opened a session that was not already open
     */
    public boolean engage(Guild guild, MessageChannel output, Turn question) {
        if (engaged.containsKey(guild.getId())) {
            // Already listening at full price; the audio is flowing there and this sentence went with it.
            return false;
        }
        if (!engagement.open().test(guild, output)) {
            logger.debug("Nothing to engage in guild {}", guild.getId());
            return false;
        }
        engaged.put(guild.getId(), guild);
        engagement.divert().apply(guild, (userId, audio) -> engagement.hear().accept(guild, userId, audio));
        logger.info("Engaged in guild {}: the hosted session is now hearing the channel", guild.getId());
        engagement.ask().ask(guild, question, historyBefore(question));
        return true;
    }

    /**
     * The turns before the question, so a session opened mid-conversation can follow it.
     *
     * <p>The question itself is left out: it is sent separately as the thing to answer, and sending it
     * twice makes the model answer it twice.
     */
    private List<Turn> historyBefore(Turn question) {
        if (question == null || question.guildId() == null) {
            return List.of();
        }
        List<Turn> recent = memory.channelHistory(question.guildId(), question.channelId(), HISTORY_TURNS);
        return recent.stream().filter(turn -> !turn.equals(question)).toList();
    }

    /**
     * Closes any session that has gone quiet.
     *
     * <p>Called on a timer, because going quiet is an absence of events. Idleness is counted from the last
     * thing said <em>or</em> answered, so the bot is not cut off mid-sentence by its own window.
     */
    public void tick() {
        long now = clock.getAsLong();
        for (Guild guild : List.copyOf(engaged.values())) {
            // A session that ended of old age is the reason this check is here and not only the window.
            // Both services close one on their own schedule - sixty minutes for OpenAI, about fifteen for
            // an audio-only Google session - and noticing it is what turns that from a dead conversation
            // into a pause: the local transcriber takes the audio back, and the next time somebody says the
            // bot's name a fresh session opens, with the question and the recent turns handed to it.
            if (!engagement.alive().test(guild)) {
                disengage(guild, "the service closed the session");
                continue;
            }
            long idleFor = now - engagement.lastActivity().applyAsLong(guild);
            if (idleFor >= windowMs) {
                disengage(guild, "idle for " + idleFor + " ms");
            }
        }
    }

    /**
     * Stops paying, and goes back to listening locally.
     *
     * @param guild  the guild
     * @param reason for the log line, which is the only place the cost of this is visible
     * @return false when it was not engaged
     */
    public boolean disengage(Guild guild, String reason) {
        if (engaged.remove(guild.getId()) == null) {
            return false;
        }
        engagement.divert().apply(guild, null);
        engagement.close().accept(guild);
        logger.info("Disengaged in guild {} ({}); listening locally again", guild.getId(), reason);
        return true;
    }

    /** @param guild the guild
     *  @return true while this guild's audio is being sent to the hosted session */
    public boolean isEngaged(Guild guild) {
        return engaged.containsKey(guild.getId());
    }

    /** Closes everything, for a plugin being disabled. */
    public void shutdown() {
        for (Guild guild : List.copyOf(engaged.values())) {
            disengage(guild, "shutting down");
        }
    }
}
