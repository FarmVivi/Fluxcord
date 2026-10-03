package fr.farmvivi.fluxcord.plugins.aiaudio;

import fr.farmvivi.fluxcord.api.storage.PluginDataStorageAdapter;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Remembers which voice channels the bot was listening to, so a restart does not silently end them.
 *
 * <p>The music plugin has done this since the beginning: it rejoins the channel and picks the track back up,
 * because a pod rescheduled by Kubernetes is not a decision anybody made. Listening was not treated the same
 * way, so the same restart left {@code /converse} and {@code /transcribe} dead with nothing said about it —
 * the bot sat in the channel, or not even that, and answered nothing. Somebody had to notice and run the
 * command again, which is exactly the kind of failure nobody notices in a channel that happens to be quiet.
 *
 * <p>What is restored is the <em>intent</em>: be in that voice channel, listen, and answer if you were
 * answering. Deliberately not restored is the hosted realtime session — it reopens by itself the next time
 * the bot is addressed, and reopening it into an empty room would be paying for silence. The whole point of
 * {@code WakeGate} is that nobody pays until the bot is spoken to, and a restart is no reason to break that.
 *
 * <p>State older than the time-to-live is dropped rather than acted on, for the same reason the music plugin
 * drops it: rejoining a voice channel somebody left hours ago is not resuming, it is haunting.
 */
public class SessionPersistence {

    /** Where a guild's listening state is kept, under the plugin's own namespace. */
    static final String STATE_KEY = "session.state";

    private static final String VOICE_CHANNEL = "voiceChannelId";
    private static final String OUTPUT_CHANNEL = "outputChannelId";
    private static final String CONVERSING = "conversing";
    private static final String SAVED_AT = "savedAtMs";

    private final AIAudioPlugin plugin;
    private final PluginDataStorageAdapter storage;
    private final Logger logger;
    private final LongSupplier clock;

    public SessionPersistence(AIAudioPlugin plugin, PluginDataStorageAdapter storage, Logger logger,
                              LongSupplier clock) {
        this.plugin = plugin;
        this.storage = storage;
        this.logger = logger;
        this.clock = clock;
    }

    /**
     * Writes down every guild the bot is currently listening to, and clears the rest.
     *
     * <p>Called on the way down. A guild that is <em>not</em> listening has its entry removed rather than
     * left alone: somebody who ran {@code /converse stop} before the restart meant it, and a stale entry
     * would bring the bot back into a channel it was dismissed from.
     *
     * @param jda the connected JDA, or null when the bot never got that far
     */
    public void save(JDA jda) {
        if (jda == null || plugin.getSpeechRecognition() == null) {
            return;
        }
        int kept = 0;
        for (Guild guild : jda.getGuilds()) {
            if (write(guild)) {
                kept++;
            }
        }
        storage.saveAll();
        logger.info("Saved listening state for {} guild(s)", kept);
    }

    /** @return true when this guild was listening and its state was written */
    private boolean write(Guild guild) {
        var guildStorage = storage.getGuildStorage(guild.getId());
        AudioChannel voice = guild.getAudioManager().getConnectedChannel();
        boolean listening = plugin.getSpeechRecognition().isActive(guild);
        if (!listening || voice == null) {
            guildStorage.remove(STATE_KEY);
            return false;
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(VOICE_CHANNEL, voice.getId());
        // Stored as an id and resolved on the way back: a MessageChannel is a live JDA object that cannot
        // outlive the session it came from, and the channel may well have been deleted meanwhile.
        state.put(OUTPUT_CHANNEL, plugin.getSpeechRecognition().outputFor(guild)
                .map(MessageChannel::getId).orElse(null));
        state.put(CONVERSING, plugin.getConversation() != null && plugin.getConversation().isActive(guild));
        state.put(SAVED_AT, (double) clock.getAsLong());
        guildStorage.set(STATE_KEY, state);
        return true;
    }

    /**
     * Puts the bot back where it was listening.
     *
     * <p>Must run once JDA is connected and its guilds are available, which is what {@code onPostEnable} is
     * for.
     *
     * @param jda     the connected JDA
     * @param ttlMs   how old a state may be and still be acted on; zero or less means no limit
     */
    public void restore(JDA jda, long ttlMs) {
        if (jda == null) {
            return;
        }
        int restored = 0;
        for (Guild guild : jda.getGuilds()) {
            try {
                if (restoreOne(guild, ttlMs)) {
                    restored++;
                }
            } catch (RuntimeException e) {
                // One guild that cannot be rejoined must not cost the others theirs.
                logger.warn("Could not restore listening in guild {}: {}", guild.getId(), e.getMessage());
            }
        }
        if (restored > 0) {
            logger.info("Resumed listening in {} guild(s)", restored);
        }
    }

    private boolean restoreOne(Guild guild, long ttlMs) {
        var guildStorage = storage.getGuildStorage(guild.getId());
        Optional<Map> raw = guildStorage.get(STATE_KEY, Map.class);
        if (raw.isEmpty()) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) raw.get();

        long savedAt = state.get(SAVED_AT) instanceof Number number ? number.longValue() : 0L;
        if (ttlMs > 0 && clock.getAsLong() - savedAt > ttlMs) {
            logger.info("Discarding listening state for guild {}, saved {} ms ago",
                    guild.getId(), clock.getAsLong() - savedAt);
            guildStorage.remove(STATE_KEY);
            storage.saveAll();
            return false;
        }

        AudioChannel voice = channel(guild, state.get(VOICE_CHANNEL));
        if (voice == null) {
            guildStorage.remove(STATE_KEY);
            storage.saveAll();
            return false;
        }
        MessageChannel output = output(guild, state.get(OUTPUT_CHANNEL));

        guild.getAudioManager().openAudioConnection(voice);
        plugin.getSpeechRecognition().start(guild, output);
        if (Boolean.TRUE.equals(state.get(CONVERSING)) && plugin.getConversation() != null) {
            plugin.getConversation().start(guild, output);
        }
        logger.info("Resumed listening in guild {} on {} ({})", guild.getId(), voice.getName(),
                Boolean.TRUE.equals(state.get(CONVERSING)) ? "answering out loud" : "transcribing only");
        return true;
    }

    /** @return the voice channel, or null when it is gone or nobody is in it any more */
    private AudioChannel channel(Guild guild, Object id) {
        if (!(id instanceof String channelId)) {
            return null;
        }
        AudioChannel voice = guild.getChannelById(AudioChannel.class, channelId);
        if (voice == null) {
            logger.info("The voice channel the bot was listening to is gone in guild {}", guild.getId());
            return null;
        }
        // Rejoining a channel everybody has left means listening to nothing, at the cost of a voice
        // connection and a poll every 250 ms, until somebody notices the bot sitting there alone.
        if (voice.getMembers().stream().noneMatch(member -> !member.getUser().isBot())) {
            logger.info("Nobody is left in {} in guild {}; not rejoining", voice.getName(), guild.getId());
            return null;
        }
        return voice;
    }

    /** @return where transcriptions should be posted again, or null to post nowhere */
    private MessageChannel output(Guild guild, Object id) {
        if (!(id instanceof String channelId)) {
            return null;
        }
        // GuildMessageChannel and not MessageChannel: only the former is a channel of a guild, which is
        // what there is to look up here.
        return guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class,
                channelId);
    }
}
