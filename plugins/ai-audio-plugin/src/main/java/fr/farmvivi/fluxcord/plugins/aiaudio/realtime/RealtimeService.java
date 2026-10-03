package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import fr.farmvivi.fluxcord.plugins.aiaudio.AiSettings;
import fr.farmvivi.fluxcord.plugins.aiaudio.conversation.ConversationPrompt;
import fr.farmvivi.fluxcord.plugins.aiaudio.conversation.ToolSource;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationMemory;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaSnapshot;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Holds one full-duplex conversation per guild, and wires it to the audio pipeline.
 *
 * <p>Thin on purpose: opening a socket, registering a receive handler, and taking them both away again. The
 * decisions are in {@link RealtimeConversation} where they can be tested, and the frames in
 * {@link RealtimeProtocol}.
 *
 * <p>The persona, the memory and the tools are the same objects the turn-based path uses. That was the point of
 * building this second: nothing about who the bot is or what it remembers depends on which way the conversation
 * is held.
 */
public class RealtimeService {

    private final AIAudioPlugin plugin;
    private final ConversationMemory memory;
    private final java.util.List<ToolSource> toolSources;
    private final Logger logger;
    private final LongSupplier clock;
    private final Map<String, RealtimeConversation> conversations = new ConcurrentHashMap<>();
    /**
     * Closes turns that silence has ended.
     *
     * <p>One scheduler for every guild, as in the transcription service: silence is an absence of packets
     * and never an event, so somebody has to look.
     */
    private final java.util.concurrent.ScheduledExecutorService ticker =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ai-audio-realtime-turns");
                thread.setDaemon(true);
                return thread;
            });
    private final java.util.function.BiFunction<AiSettings.RealtimeSettings,
            java.util.function.Consumer<RealtimeProtocol.Event>, RealtimeLink> links;

    public RealtimeService(AIAudioPlugin plugin, ConversationMemory memory,
                           java.util.List<ToolSource> toolSources) {
        this(plugin, memory, toolSources, System::currentTimeMillis);
    }

    RealtimeService(AIAudioPlugin plugin, ConversationMemory memory, java.util.List<ToolSource> toolSources,
                    LongSupplier clock) {
        this(plugin, memory, toolSources, clock,
                (settings, sink) -> RealtimeSession.open(plugin.getHttp(), dialect(settings),
                        settings.url(), settings.apiKey(), sink));
    }

    /**
     * @param links how to open the connection, given the settings and where events should go; injected so a
     *              test can drive the wiring - registering the handler, reporting a failure, releasing it all
     *              again - without a socket, which is the only part of this that cannot be exercised
     */
    RealtimeService(AIAudioPlugin plugin, ConversationMemory memory, java.util.List<ToolSource> toolSources,
                    LongSupplier clock,
                    java.util.function.BiFunction<AiSettings.RealtimeSettings,
                            java.util.function.Consumer<RealtimeProtocol.Event>, RealtimeLink> links) {
        this.plugin = plugin;
        this.memory = memory;
        this.toolSources = toolSources == null ? java.util.List.of() : java.util.List.copyOf(toolSources);
        this.logger = plugin.getLogger();
        this.clock = clock;
        this.links = links;
    }

    /**
     * The dialect the configured service speaks.
     *
     * <p>Chosen from the configuration rather than guessed from the URL: a self-hosted proxy in front of
     * either service would make the URL say nothing about the protocol behind it.
     *
     * @param settings the realtime settings
     * @return the protocol to talk
     */
    static RealtimeProtocol dialect(AiSettings.RealtimeSettings settings) {
        return switch (settings.api()) {
            case OPENAI -> new OpenAiRealtime();
            case GEMINI -> new GeminiRealtime(settings.model());
        };
    }

    /**
     * Opens a conversation in a guild, if one is not already open.
     *
     * @param guild  the guild; the bot must already be in a voice channel
     * @param output where to report a failure
     * @return false when one was already open, or when there is no voice channel
     * @throws RealtimeException if the connection could not be opened
     */
    public boolean start(Guild guild, MessageChannel output) {
        return start(guild, output, true);
    }

    /**
     * Opens a conversation, optionally without taking the receive handler.
     *
     * @param registerHandler false when somebody else owns the handler and will divert the audio here,
     *                        which is how the session is only opened once the bot has been addressed
     * @return false when one was already open, or when there is no voice channel
     */
    public boolean start(Guild guild, MessageChannel output, boolean registerHandler) {
        AudioChannel channel = guild.getAudioManager().getConnectedChannel();
        if (channel == null || conversations.containsKey(guild.getId())) {
            return false;
        }
        AiSettings settings = plugin.getSettings();
        AiSettings.RealtimeSettings realtime = settings.realtime();
        long now = clock.getAsLong();
        PersonaSnapshot snapshot = PersonaSnapshot.of(plugin.getPersonaStore(), memory,
                ConversationContext.of(channel, memory, settings.chat().historyTurns(), 0), now);

        java.util.List<String> names = plugin.botNames(guild);
        RealtimeConversation conversation = new RealtimeConversation(logger, dialect(realtime), clock,
                toolSources,
                audio -> plugin.getTextToSpeech().play(guild, audio),
                () -> plugin.getTextToSpeech().interrupt(guild),
                memory::remember,
                message -> report(output, message),
                realtime.serviceDecidesTurns(), realtime.silence().toMillis(),
                spoken -> settings.chat().isAddressedToUs(spoken, names));

        String instructions = ConversationPrompt.systemMessageFor(snapshot, now);
        // The bot's own names go in the vocabulary, because they are what wakes it: a name the transcriber
        // cannot spell is a bot that never answers.
        java.util.List<String> vocabulary = new java.util.ArrayList<>(settings.transcription().vocabulary());
        names.stream().filter(name -> !vocabulary.contains(name)).forEach(vocabulary::add);
        conversation.start(snapshot, instructions, realtime.voice(), botUserId(),
                sink -> links.apply(realtime, sink), java.util.List.copyOf(vocabulary));

        conversations.put(guild.getId(), conversation);
        if (registerHandler) {
            handlers.add(guild.getId());
        }
        if (!realtime.serviceDecidesTurns()) {
            ticker.scheduleWithFixedDelay(() -> tick(guild.getId()), TICK_MS, TICK_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        if (registerHandler) {
            plugin.getContext().getAudioService().registerReceiveHandler(guild, plugin,
                    new RealtimeReceiver(conversation, userId -> displayName(guild, userId)));
        }
        logger.info("Realtime conversation open in guild {} ({} decides turns)", guild.getId(),
                realtime.serviceDecidesTurns() ? "the service" : "the plugin");
        return true;
    }

    /** Short enough to feel immediate, cheap enough to run forever; the same figure the segmenter uses. */
    private static final long TICK_MS = 250;

    /** Guilds whose receive handler this service registered, and must therefore give back. */
    private final java.util.Set<String> handlers = ConcurrentHashMap.newKeySet();

    /**
     * Hands one packet to a guild's conversation, for a caller that owns the receive handler itself.
     *
     * @param guild  the guild
     * @param userId who is speaking
     * @param audio  the captured chunk
     */
    public void hear(Guild guild, String userId, fr.farmvivi.fluxcord.api.audio.PcmAudio audio) {
        RealtimeConversation conversation = conversations.get(guild.getId());
        if (conversation != null) {
            conversation.hear(userId, displayName(guild, userId), audio);
        }
    }

    /**
     * Hands a guild's conversation a question it could not have heard, with the turns before it.
     *
     * @param guild    the guild
     * @param question what was asked, as the local transcription wrote it
     * @param history  what was said before, oldest first
     */
    public void ask(Guild guild, String question,
                    java.util.List<fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn> history) {
        RealtimeConversation conversation = conversations.get(guild.getId());
        if (conversation != null) {
            conversation.ask(question, history);
        }
    }

    /**
     * Whether a guild's conversation still has a connection.
     *
     * <p>Different from {@link #isActive}: that one says a conversation object exists, this one says the
     * socket behind it is still there. A session that reached its age limit, or whose connection dropped, is
     * active and not alive — and that is precisely the state somebody has to notice.
     *
     * @param guild the guild
     * @return true while the session can still carry audio
     */
    public boolean isAlive(Guild guild) {
        RealtimeConversation conversation = conversations.get(guild.getId());
        return conversation != null && conversation.isOpen();
    }

    /**
     * @param guild the guild
     * @return when anything last happened on that conversation, or 0 when there is none
     */
    public long lastActivityMs(Guild guild) {
        RealtimeConversation conversation = conversations.get(guild.getId());
        return conversation == null ? 0L : conversation.lastActivityMs();
    }

    /**
     * One tick for one guild's conversation, if it is still there.
     *
     * <p>A scheduled task that throws is never run again, and this one has to survive a bad frame.
     */
    private void tick(String guildId) {
        RealtimeConversation conversation = conversations.get(guildId);
        if (conversation == null) {
            return;
        }
        try {
            conversation.tick();
        } catch (RuntimeException e) {
            logger.warn("Closing a realtime turn failed in guild {}: {}", guildId, e.getMessage());
        }
    }

    /**
     * Closes the conversation in a guild.
     *
     * @param guild the guild
     * @return false when none was open
     */
    public boolean stop(Guild guild) {
        RealtimeConversation conversation = conversations.remove(guild.getId());
        if (conversation == null) {
            return false;
        }
        // Only the handler this service registered: when the gate owns it, taking it away here would stop
        // the local transcriber from hearing anything ever again.
        if (handlers.remove(guild.getId())) {
            plugin.getContext().getAudioService().deregisterReceiveHandler(guild, plugin);
        }
        conversation.close();
        return true;
    }

    /** @return true when a full-duplex conversation is open in this guild */
    public boolean isActive(Guild guild) {
        RealtimeConversation conversation = conversations.get(guild.getId());
        return conversation != null && conversation.isOpen();
    }

    private void report(MessageChannel output, String message) {
        if (output == null) {
            return;
        }
        try {
            output.sendMessage(plugin.getLanguage().getString("errors.realtime_failed", message)).queue();
        } catch (RuntimeException e) {
            logger.warn("Could not report a realtime failure: {}", e.getMessage());
        }
    }

    /**
     * The speaker's name as the others in the server see it.
     *
     * <p>Cache only: resolving a member over REST from the audio thread would blow the 20 ms frame budget, and a
     * missing name is not worth a request — the id is what identifies the turn anyway.
     */
    private String displayName(Guild guild, String userId) {
        Member member = guild.getMemberById(userId);
        return member == null ? userId : member.getEffectiveName();
    }

    private String botUserId() {
        var jda = plugin.getContext().getDiscordAPI().getJDA();
        return jda == null || jda.getSelfUser() == null ? "bot" : jda.getSelfUser().getId();
    }

    /** Closes every conversation. Safe to call twice. */
    public void shutdown() {
        conversations.values().forEach(RealtimeConversation::close);
        conversations.clear();
    }
}
