package fr.farmvivi.fluxcord.plugins.aiaudio;

import fr.farmvivi.fluxcord.plugins.aiaudio.ai.SpeechToText;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationMemory;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaStore;
import fr.farmvivi.fluxcord.plugins.aiaudio.transcription.SpeechSegmenter;
import fr.farmvivi.fluxcord.plugins.aiaudio.transcription.TranscriptionSession;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Listens to a voice channel and turns what is said into text.
 *
 * <p>Three threads are involved, on purpose. JDA's audio thread only appends bytes to a buffer — it must
 * return well inside 20 ms. A scheduler decides when someone has stopped talking, since silence is an
 * absence of packets and never an event. A worker then makes the transcription request, which takes
 * hundreds of milliseconds and must not hold either of the other two up.
 */
public class SpeechRecognitionService {

    /** How often to look for utterances that are over. Short enough to feel immediate, cheap to run. */
    private static final long POLL_INTERVAL_MS = 250;

    private final AIAudioPlugin plugin;
    private final SpeechToText provider;
    private final ConversationMemory memory;
    private final Logger logger;
    private final LongSupplier clock;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService worker;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * @param plugin   the owning plugin, for its logger, settings and audio service
     * @param provider what turns speech into text
     * @param memory   where transcribed turns are recorded
     */
    public SpeechRecognitionService(AIAudioPlugin plugin, SpeechToText provider, ConversationMemory memory) {
        this(plugin, provider, memory, System::currentTimeMillis);
    }

    /**
     * @param clock the current time in milliseconds; injected so tests can drive the segmenter without
     *              waiting for real silence
     */
    SpeechRecognitionService(AIAudioPlugin plugin, SpeechToText provider, ConversationMemory memory,
                             LongSupplier clock) {
        this.plugin = plugin;
        this.provider = provider;
        this.memory = memory;
        this.logger = plugin.getLogger();
        this.clock = clock;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemon("ai-audio-segmenter"));
        this.worker = Executors.newFixedThreadPool(2, daemon("ai-audio-stt"));
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Starts transcribing a guild's voice channel.
     *
     * @param guild  the guild to listen to; the bot must already be connected to a voice channel
     * @param output where transcriptions are posted
     * @return false when this guild is already being transcribed
     */
    public boolean start(Guild guild, MessageChannel output) {
        AiSettings settings = plugin.getSettings();
        AtomicBoolean started = new AtomicBoolean();
        sessions.computeIfAbsent(guild.getId(), id -> {
            SpeechSegmenter segmenter = new SpeechSegmenter(settings.silence(), settings.maxSegment(),
                    settings.minSegment());
            plugin.getContext().getAudioService()
                    .registerReceiveHandler(guild, plugin, new TranscriptionSession(segmenter, clock));
            ScheduledFuture<?> poller = scheduler.scheduleWithFixedDelay(
                    () -> drain(guild), POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            logger.info("Transcribing guild {} in {} (silence {}, max {})",
                    id, settings.transcriptionLanguage(), settings.silence(), settings.maxSegment());
            started.set(true);
            return new Session(output, segmenter, poller);
        });
        return started.get();
    }

    /**
     * @param guild the guild to look at
     * @return true when this guild's voice channel is being transcribed
     */
    public boolean isActive(Guild guild) {
        return sessions.containsKey(guild.getId());
    }

    /**
     * Stops transcribing a guild, transcribing whatever was still buffered.
     *
     * @param guild the guild to stop listening to
     * @return false when this guild was not being transcribed
     */
    public boolean stop(Guild guild) {
        Session session = sessions.remove(guild.getId());
        if (session == null) {
            return false;
        }
        session.poller.cancel(false);
        plugin.getContext().getAudioService().deregisterReceiveHandler(guild, plugin);
        // The last sentence deserves to be transcribed too.
        session.segmenter.flush().forEach(segment -> submit(guild, session, segment));
        return true;
    }

    /** Called by the scheduler: closes finished utterances and hands them to the worker. */
    private void drain(Guild guild) {
        Session session = sessions.get(guild.getId());
        if (session == null) {
            return;
        }
        try {
            session.segmenter.poll(clock.getAsLong())
                    .forEach(segment -> submit(guild, session, segment));
        } catch (RuntimeException e) {
            // A scheduled task that throws is never run again; this one must survive a bad segment.
            logger.warn("Segmenting failed for guild {}: {}", guild.getId(), e.getMessage());
        }
    }

    private void submit(Guild guild, Session session, SpeechSegmenter.Segment segment) {
        worker.execute(() -> {
            try {
                String text = provider.transcribe(segment.audio(), plugin.getSettings().transcriptionLanguage());
                if (text == null || text.isBlank()) {
                    return;
                }
                publish(guild, session, segment.userId(), text.trim());
            } catch (RuntimeException e) {
                logger.warn("Transcription failed for guild {}: {}", guild.getId(), e.getMessage());
            }
        });
    }

    /** Records the turn and posts it, with the speaker's name as seen in that server. */
    private void publish(Guild guild, Session session, String userId, String text) {
        String speaker = displayName(guild, userId);
        Optional<AudioChannel> channel = connectedChannel(guild);
        memory.remember(Turn.now(userId, speaker, guild.getId(), guild.getName(),
                channel.map(AudioChannel::getId).orElse(null),
                channel.map(AudioChannel::getName).orElse(null), text));
        liftMood(guild, channel.map(AudioChannel::getId).orElse(null));
        logger.debug("[{}] {}: {}", guild.getId(), speaker, text);
        try {
            session.output.sendMessage("**" + speaker + "** " + text).queue();
        } catch (RuntimeException e) {
            logger.warn("Could not post a transcription: {}", e.getMessage());
        }
    }

    /**
     * A sentence spoken in the channel makes the bot a little more lively.
     *
     * <p>Only the energy: whether a conversation is warm or cold cannot be told from the fact that it is
     * happening, and guessing it from keywords would be worse than not guessing. The mood fades back on its
     * own, so a channel that goes quiet calms down without anything having to run.
     */
    private void liftMood(Guild guild, String channelId) {
        AiSettings.PersonaSettings persona = plugin.getSettings().persona();
        PersonaStore store = plugin.getPersonaStore();
        if (!persona.moodEnabled() || store == null || channelId == null) {
            return;
        }
        store.nudgeMood(guild.getId(), channelId, persona.energyPerTurn(), 0, clock.getAsLong());
    }

    /**
     * The speaker's name as the others in the server see it.
     *
     * <p>Only the cache is consulted: resolving a member over REST from the audio path would block, and a
     * missing name is not worth a request — the id is what identifies the turn anyway.
     */
    private String displayName(Guild guild, String userId) {
        Member member = guild.getMemberById(userId);
        if (member != null) {
            return member.getEffectiveName();
        }
        var user = plugin.getContext().getDiscordAPI().getJDA() == null
                ? null : plugin.getContext().getDiscordAPI().getJDA().getUserById(userId);
        return user == null ? userId : user.getEffectiveName();
    }

    private Optional<AudioChannel> connectedChannel(Guild guild) {
        return Optional.ofNullable(guild.getAudioManager().getConnectedChannel());
    }

    /** Releases every session and stops the threads. Safe to call twice. */
    public void shutdown() {
        sessions.values().forEach(session -> {
            session.poller.cancel(false);
            session.segmenter.clear();
        });
        sessions.clear();
        scheduler.shutdownNow();
        worker.shutdownNow();
    }

    /** One guild being transcribed. */
    private static final class Session {
        private final MessageChannel output;
        private final SpeechSegmenter segmenter;
        private final ScheduledFuture<?> poller;

        private Session(MessageChannel output, SpeechSegmenter segmenter, ScheduledFuture<?> poller) {
            this.output = output;
            this.segmenter = segmenter;
            this.poller = poller;
        }
    }
}
