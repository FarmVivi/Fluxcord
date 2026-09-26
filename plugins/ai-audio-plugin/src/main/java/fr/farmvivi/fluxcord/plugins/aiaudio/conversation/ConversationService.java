package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import fr.farmvivi.fluxcord.plugins.aiaudio.AiSettings;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationMemory;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaSnapshot;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * The turn that makes the bot answer out loud: a transcribed sentence goes to the model, and what comes back
 * is spoken.
 *
 * <p>Sits between the two services rather than inside either, because both ends are already busy: the
 * transcription service must not block on a model call, and the speech service knows nothing about
 * conversations. What arrives here is text and what leaves is text; the audio is somebody else's problem.
 *
 * <p>The model call runs on its own worker: it is the slowest step of the chain (a second or more) and it must
 * not hold up the next transcription.
 */
public class ConversationService {

    private final AIAudioPlugin plugin;
    private final ChatModel model;
    private final ConversationMemory memory;
    private final MemoryTools memoryTools;
    private final Logger logger;
    private final LongSupplier clock;
    private final ExecutorService worker;
    private final Set<String> activeGuilds = ConcurrentHashMap.newKeySet();

    public ConversationService(AIAudioPlugin plugin, ChatModel model, ConversationMemory memory) {
        this(plugin, model, memory, System::currentTimeMillis);
    }

    /**
     * @param clock the current time in milliseconds, injected so tests need no real clock
     */
    ConversationService(AIAudioPlugin plugin, ChatModel model, ConversationMemory memory, LongSupplier clock) {
        this.plugin = plugin;
        this.model = model;
        this.memory = memory;
        this.memoryTools = new MemoryTools(memory);
        this.logger = plugin.getLogger();
        this.clock = clock;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ai-audio-conversation");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts answering in a guild.
     *
     * @param guild the guild
     * @return false when it was already answering there
     */
    public boolean start(Guild guild) {
        return activeGuilds.add(guild.getId());
    }

    /**
     * Stops answering in a guild.
     *
     * @return false when it was not answering there
     */
    public boolean stop(Guild guild) {
        return activeGuilds.remove(guild.getId());
    }

    /** @return true when the bot answers out loud in this guild */
    public boolean isActive(Guild guild) {
        return activeGuilds.contains(guild.getId());
    }

    /**
     * Called for every sentence the transcription service produced.
     *
     * <p>Returns immediately: whether this sentence deserves an answer is decided here, and the answer itself
     * is produced on the worker.
     *
     * @param guild the guild it was said in
     * @param turn  what was said, as it was recorded
     */
    public void onTranscription(Guild guild, Turn turn) {
        AiSettings.ChatSettings chat = plugin.getSettings().chat();
        if (!chat.enabled() || !isActive(guild) || turn.text() == null || turn.text().isBlank()) {
            return;
        }
        if (!chat.isAddressedToUs(turn.text())) {
            logger.debug("Not addressed to us (no '{}'): {}", chat.wakeWord(), turn.text());
            return;
        }
        AudioChannel channel = guild.getAudioManager().getConnectedChannel();
        if (channel == null) {
            return;
        }
        worker.execute(() -> answer(guild, channel, turn, chat));
    }

    /** Asks the model and speaks the answer. Runs on the worker. */
    private void answer(Guild guild, AudioChannel channel, Turn question, AiSettings.ChatSettings chat) {
        try {
            PersonaSnapshot snapshot = PersonaSnapshot.of(plugin.getPersonaStore(), memory,
                    ConversationContext.of(channel, memory, chat.historyTurns(), 0), clock.getAsLong());

            String botId = botUserId(guild);
            List<ChatModel.Message> messages = new ArrayList<>(
                    ConversationPrompt.build(snapshot, question, chat.historyTurns(), botId));
            String reply = converse(messages, snapshot, chat);
            if (reply == null || reply.isBlank()) {
                logger.debug("The model chose to stay silent");
                return;
            }

            // Remembered before it is spoken: what the bot said is part of the conversation, and the next turn
            // has to see it even if the synthesis fails.
            memory.remember(Turn.now(botId, snapshot.persona().name(), guild.getId(),
                    guild.getName(), channel.getId(), channel.getName(), reply));
            logger.debug("Answering: {}", reply);
            plugin.getTextToSpeech().speak(guild, reply, null).exceptionally(error -> {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                logger.warn("Could not speak the answer: {}", cause.getMessage());
                return null;
            });
        } catch (RuntimeException e) {
            logger.warn("Could not answer in guild {}: {}", guild.getId(), e.getMessage());
        }
    }

    /**
     * Asks until the model answers instead of asking for something.
     *
     * <p>A model that can call the memory will often do it before answering: one round to look something up,
     * then the answer. Each round is a full request, so the number of them is capped — a model that keeps asking
     * would otherwise hold the voice channel silent forever. On the last round the tools are withheld, which is
     * how it is told to answer with what it has rather than being cut off mid-thought.
     *
     * @return what to say, possibly empty when the model chose to stay silent
     */
    private String converse(List<ChatModel.Message> messages, PersonaSnapshot snapshot,
                            AiSettings.ChatSettings chat) {
        List<ChatModel.Tool> tools = chat.memoryTools() ? memoryTools.declarations() : List.of();
        for (int round = 0; round <= chat.maxToolRounds(); round++) {
            boolean lastRound = round == chat.maxToolRounds();
            ChatModel.Answer answer = model.reply(messages, lastRound ? List.of() : tools,
                    chat.maxReplyTokens());
            if (!answer.hasToolCalls()) {
                return answer.content();
            }
            messages.add(ChatModel.Message.assistantToolCalls(answer.toolCalls()));
            for (ChatModel.ToolCall call : answer.toolCalls()) {
                String result = memoryTools.execute(call, snapshot, clock.getAsLong());
                logger.debug("Tool {} answered {} character(s)", call.name(), result.length());
                messages.add(ChatModel.Message.toolResult(call.id(), result));
            }
        }
        // Reached only if the model asked for something on a round where it had been offered nothing.
        return "";
    }

    /**
     * The bot's own user id, used as the speaker of its answers.
     *
     * <p>Stored with the turn so the next prompt can tell the bot's own words from everyone else's and give
     * them the assistant role instead of quoting them back as something a user said.
     */
    private String botUserId(Guild guild) {
        var jda = plugin.getContext().getDiscordAPI().getJDA();
        return jda == null || jda.getSelfUser() == null ? "bot" : jda.getSelfUser().getId();
    }

    /** Stops answering everywhere and shuts the worker down. Safe to call twice. */
    public void shutdown() {
        activeGuilds.clear();
        worker.shutdownNow();
    }
}
