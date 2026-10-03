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
        AudioChannel channel = guild.getAudioManager().getConnectedChannel();
        if (channel == null || conversations.containsKey(guild.getId())) {
            return false;
        }
        AiSettings settings = plugin.getSettings();
        AiSettings.RealtimeSettings realtime = settings.realtime();
        long now = clock.getAsLong();
        PersonaSnapshot snapshot = PersonaSnapshot.of(plugin.getPersonaStore(), memory,
                ConversationContext.of(channel, memory, settings.chat().historyTurns(), 0), now);

        RealtimeConversation conversation = new RealtimeConversation(logger, dialect(realtime), clock,
                toolSources,
                audio -> plugin.getTextToSpeech().play(guild, audio),
                () -> plugin.getTextToSpeech().interrupt(guild),
                memory::remember,
                message -> report(output, message));

        String instructions = ConversationPrompt.systemMessageFor(snapshot, now);
        conversation.start(snapshot, instructions, realtime.voice(), botUserId(),
                sink -> links.apply(realtime, sink));

        conversations.put(guild.getId(), conversation);
        plugin.getContext().getAudioService().registerReceiveHandler(guild, plugin,
                new RealtimeReceiver(conversation, userId -> displayName(guild, userId)));
        logger.info("Realtime conversation open in guild {}", guild.getId());
        return true;
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
        plugin.getContext().getAudioService().deregisterReceiveHandler(guild, plugin);
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
