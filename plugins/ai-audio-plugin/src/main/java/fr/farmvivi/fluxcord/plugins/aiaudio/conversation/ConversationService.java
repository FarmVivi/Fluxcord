package fr.farmvivi.fluxcord.plugins.aiaudio.conversation;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.plugins.aiaudio.AIAudioPlugin;
import fr.farmvivi.fluxcord.plugins.aiaudio.AiSettings;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationContext;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.ConversationMemory;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.MoodReader;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaSnapshot;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaStore;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
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
 * conversations.
 *
 * <p>Two shapes of turn go through here, and the difference is only how much of the voice survives. In the
 * text shape the model reads a transcript and its answer is synthesised. In the <strong>spoken shape</strong>
 * ({@code conversation.audio.*}) the model is handed the recording and answers with a voice of its own, so
 * neither transcription nor synthesis sits between the two people talking. What stays in both is the
 * transcript: it gates the turn - a wake word cannot be matched against samples - and it is what the memory
 * keeps.
 *
 * <p>The model call runs on its own worker: it is the slowest step of the chain (a second or more) and it must
 * not hold up the next transcription.
 */
public class ConversationService {

    private final AIAudioPlugin plugin;
    private final ChatModel model;
    private final ConversationMemory memory;
    private final List<ToolSource> toolSources;
    private final MoodReader moodReader;
    private final Logger logger;
    private final LongSupplier clock;
    private final ExecutorService worker;
    private final Set<String> activeGuilds = ConcurrentHashMap.newKeySet();
    /** Where to say that something went wrong, per guild: the channel {@code /converse start} came from. */
    private final java.util.Map<String, MessageChannel> outputs = new ConcurrentHashMap<>();
    /** Guilds already told that the bot cannot speak, so the warning is said once and not once a sentence. */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    /** Guilds where starting this conversation is also what started the transcription. */
    private final Set<String> ownsListening = ConcurrentHashMap.newKeySet();

    public ConversationService(AIAudioPlugin plugin, ChatModel model, ConversationMemory memory) {
        this(plugin, model, memory, System::currentTimeMillis, List.of());
    }

    /**
     * @param extraTools groups of tools beyond the memory, such as web search; empty when none is configured
     */
    public ConversationService(AIAudioPlugin plugin, ChatModel model, ConversationMemory memory,
                               List<ToolSource> extraTools) {
        this(plugin, model, memory, System::currentTimeMillis, extraTools);
    }

    /**
     * @param clock the current time in milliseconds, injected so tests need no real clock
     */
    ConversationService(AIAudioPlugin plugin, ChatModel model, ConversationMemory memory, LongSupplier clock) {
        this(plugin, model, memory, clock, List.of());
    }

    ConversationService(AIAudioPlugin plugin, ChatModel model, ConversationMemory memory, LongSupplier clock,
                        List<ToolSource> extraTools) {
        this.plugin = plugin;
        this.model = model;
        this.memory = memory;
        this.moodReader = new MoodReader(model);
        List<ToolSource> sources = new ArrayList<>();
        sources.add(new MemoryTools(memory));
        if (extraTools != null) {
            sources.addAll(extraTools);
        }
        this.toolSources = List.copyOf(sources);
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
        return start(guild, null);
    }

    /**
     * Starts answering in a guild, with somewhere to report a failure.
     *
     * @param guild  the guild
     * @param output where to post if the bot turns out to be unable to speak, or null to only log it
     * @return false when it was already answering there
     */
    public boolean start(Guild guild, MessageChannel output) {
        if (output != null) {
            outputs.put(guild.getId(), output);
        }
        warned.remove(guild.getId());
        return activeGuilds.add(guild.getId());
    }

    /**
     * Records that this conversation is what started the transcription in a guild.
     *
     * <p>The bot cannot answer what it does not hear, so starting a conversation starts listening too - and
     * what a command switched on, the matching command has to switch off again. Somebody who asked for the
     * written record separately keeps it: only the listening this conversation started is claimed here.
     *
     * @param guild the guild whose transcription was started along with the conversation
     */
    public void ownListening(Guild guild) {
        ownsListening.add(guild.getId());
    }

    /**
     * @param guild the guild to look at
     * @return true when stopping this conversation should stop the transcription as well
     */
    public boolean ownsListening(Guild guild) {
        return ownsListening.contains(guild.getId());
    }

    /**
     * Stops answering in a guild.
     *
     * @return false when it was not answering there
     */
    public boolean stop(Guild guild) {
        outputs.remove(guild.getId());
        warned.remove(guild.getId());
        ownsListening.remove(guild.getId());
        return activeGuilds.remove(guild.getId());
    }

    /**
     * Where a failure in a guild is reported, which is also where a command run by the model should keep
     * whatever text channel it keeps.
     *
     * @param guildId the guild
     * @return the channel {@code /converse start} came from, or empty when it was started without one
     */
    public java.util.Optional<MessageChannel> outputFor(String guildId) {
        return java.util.Optional.ofNullable(outputs.get(guildId));
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
        onTranscription(guild, turn, null);
    }

    /**
     * Called for every sentence the transcription service produced, with the recording of it.
     *
     * @param guild the guild it was said in
     * @param turn  what was said, as it was recorded
     * @param audio the voice that said it, passed on to the model when it can listen; null when it cannot
     */
    public void onTranscription(Guild guild, Turn turn, PcmAudio audio) {
        AiSettings.ChatSettings chat = plugin.getSettings().chat();
        if (!chat.enabled() || !isActive(guild) || turn.text() == null || turn.text().isBlank()) {
            return;
        }
        java.util.List<String> names = plugin.botNames(guild);
        if (!chat.isAddressedToUs(turn.text(), names)) {
            logger.debug("Not addressed to us (none of {}): {}", chat.triggers(names), turn.text());
            return;
        }
        AudioChannel channel = guild.getAudioManager().getConnectedChannel();
        if (channel == null) {
            return;
        }
        // The economical arrangement: the sentence was heard for free, and only now - because it named the
        // bot - is a hosted session worth opening. It answers from here on, so this path stops.
        WakeGate gate = plugin.getWakeGate();
        if (gate != null && plugin.getSettings().realtime().isUsable()
                && plugin.getSettings().realtime().wakeLocally()) {
            MessageChannel output = outputs.get(guild.getId());
            worker.execute(() -> {
                try {
                    gate.engage(guild, output, turn);
                } catch (RuntimeException e) {
                    logger.warn("Could not engage the hosted session in guild {}: {}",
                            guild.getId(), e.getMessage());
                }
            });
            return;
        }
        worker.execute(() -> answer(guild, channel, turn, chat, audio));
    }

    /** Asks the model and speaks the answer. Runs on the worker. */
    private void answer(Guild guild, AudioChannel channel, Turn question, AiSettings.ChatSettings chat,
                        PcmAudio questionAudio) {
        try {
            PersonaSnapshot snapshot = PersonaSnapshot.of(plugin.getPersonaStore(), memory,
                    ConversationContext.of(channel, memory, chat.historyTurns(), 0), clock.getAsLong());

            String botId = botUserId(guild);
            List<ChatModel.Message> messages = new ArrayList<>(ConversationPrompt.build(snapshot, question,
                    chat.historyTurns(), botId, chat.audio().hear() ? questionAudio : null));
            ChatModel.Answer answer = converse(messages, snapshot, chat, question);
            if (answer.isSilent()) {
                logger.debug("The model chose to stay silent");
                return;
            }

            remember(guild, channel, snapshot, botId, answer);
            say(guild, answer);
            // After speaking, never before: reading the room is worth a third of a second and nobody should
            // wait for it to hear the answer.
            readTheRoom(guild, channel.getId(), snapshot, chat);
        } catch (RuntimeException e) {
            logger.warn("Could not answer in guild {}: {}", guild.getId(), e.getMessage());
        }
    }

    /**
     * Records what the bot said, before it says it.
     *
     * <p>Before, because the next turn has to see this one even if the playback fails. A spoken answer is
     * remembered by its transcript, and a provider that sends audio without one leaves nothing to remember -
     * said out loud rather than filled in with a placeholder, which would end up quoted back as if the bot had
     * really said it.
     */
    private void remember(Guild guild, AudioChannel channel, PersonaSnapshot snapshot, String botId,
                          ChatModel.Answer answer) {
        if (answer.content().isBlank()) {
            logger.warn("The model answered with audio but no transcript; the turn cannot be remembered");
            return;
        }
        memory.remember(Turn.now(botId, snapshot.persona().name(), guild.getId(), guild.getName(),
                channel.getId(), channel.getName(), answer.content()));
    }

    /** Plays the answer: the model's own voice when it has one, a synthesised reading of its text otherwise. */
    private void say(Guild guild, ChatModel.Answer answer) {
        if (answer.hasAudio()) {
            logger.debug("Answering out loud ({}): {}", answer.audio().duration(), answer.content());
            plugin.getTextToSpeech().play(guild, answer.audio());
            return;
        }
        logger.debug("Answering: {}", answer.content());
        plugin.getTextToSpeech().speak(guild, answer.content(), null).exceptionally(error -> {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            logger.warn("Could not speak the answer: {}", cause.getMessage());
            reportOnce(guild, cause);
            return null;
        });
    }

    /**
     * Tells the channel, once, that the bot has an answer and no way to say it.
     *
     * <p>Without this the only symptom is silence: the bot hears, thinks, and nothing comes out, with the
     * reason in a log nobody in the voice channel is reading. Once per {@code /converse start}, because
     * repeating it on every sentence would be its own kind of noise.
     */
    private void reportOnce(Guild guild, Throwable cause) {
        MessageChannel output = outputs.get(guild.getId());
        if (output == null || !warned.add(guild.getId())) {
            return;
        }
        try {
            output.sendMessage(plugin.getLanguage().getString("errors.cannot_speak",
                    String.valueOf(cause.getMessage()))).queue();
        } catch (RuntimeException e) {
            logger.warn("Could not report the speech failure: {}", e.getMessage());
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
     * @param asker the turn being answered, so a group that acts does so with that person's rights
     * @return what to say, in text or in audio, possibly nothing when the model chose to stay silent
     */
    private ChatModel.Answer converse(List<ChatModel.Message> messages, PersonaSnapshot snapshot,
                                      AiSettings.ChatSettings chat, Turn asker) {
        List<ChatModel.Tool> tools = offeredTools(chat);
        for (int round = 0; round <= chat.maxToolRounds(); round++) {
            boolean lastRound = round == chat.maxToolRounds();
            List<ChatModel.Tool> offered = lastRound ? List.of() : tools;
            // A round that may still call something needs room to think first; the round that only answers
            // keeps the small budget, which is what keeps a spoken reply to a sentence or two.
            int budget = offered.isEmpty() ? chat.maxReplyTokens() : chat.maxToolTokens();
            ChatModel.Answer answer = model.reply(messages, offered, budget);
            if (!answer.hasToolCalls()) {
                return answer;
            }
            messages.add(ChatModel.Message.assistantToolCalls(answer.toolCalls()));
            for (ChatModel.ToolCall call : answer.toolCalls()) {
                String result = run(call, snapshot, asker);
                logger.debug("Tool {} answered {} character(s)", call.name(), result.length());
                messages.add(ChatModel.Message.toolResult(call.id(), result));
            }
        }
        // Reached only if the model asked for something on a round where it had been offered nothing.
        return ChatModel.Answer.spoken("");
    }

    /**
     * What the model is allowed to call this turn.
     *
     * <p>The memory is gated on {@code conversation.memory_tools} because the model fast enough for a voice
     * channel only calls a tool about one time in three; anything else configured is offered whenever it
     * exists, since a plugin does not install a search backend by accident.
     */
    private List<ChatModel.Tool> offeredTools(AiSettings.ChatSettings chat) {
        List<ChatModel.Tool> tools = new ArrayList<>();
        for (ToolSource source : toolSources) {
            if (source instanceof MemoryTools && !chat.memoryTools()) {
                continue;
            }
            tools.addAll(source.declarations());
        }
        return tools;
    }

    /**
     * Runs one call through whichever group owns it.
     *
     * <p>A name nobody owns is answered rather than thrown: the model invented it, or it is remembering a tool
     * from a round where it was offered one, and either way telling it so is something it can act on.
     */
    private String run(ChatModel.ToolCall call, PersonaSnapshot snapshot, Turn asker) {
        for (ToolSource source : toolSources) {
            if (source.handles(call.name())) {
                return source.execute(call, snapshot, clock.getAsLong(), asker);
            }
        }
        logger.warn("The model asked for a tool that is not offered: {}", call.name());
        return "There is no tool called " + call.name() + ".";
    }

    /**
     * Asks the model how the room actually feels, and moves the mood towards what it says.
     *
     * <p>This replaces adding a little energy per sentence, which measured activity rather than mood — the bot
     * grew livelier while being told bad news. The reading covers the turns including the answer just given,
     * which is why it happens here and not in the transcription service.
     *
     * @param chat the chat settings, only to know the history depth the model is shown
     */
    private void readTheRoom(Guild guild, String channelId, PersonaSnapshot snapshot,
                             AiSettings.ChatSettings chat) {
        AiSettings.PersonaSettings persona = plugin.getSettings().persona();
        PersonaStore store = plugin.getPersonaStore();
        if (!persona.moodEnabled() || !persona.moodFromModel() || store == null) {
            return;
        }
        moodReader.read(memory.channelHistory(guild.getId(), channelId,
                Math.max(MoodReader.MAX_TURNS, chat.historyTurns()))).ifPresent(reading -> {
            double[] deltas = reading.deltasFrom(snapshot.mood(), persona.moodWeight());
            store.nudgeMood(guild.getId(), channelId, deltas[0], deltas[1], clock.getAsLong());
            logger.debug("The room reads energy {} warmth {}; moving the mood by {} and {}",
                    reading.energy(), reading.warmth(), deltas[0], deltas[1]);
        });
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
        outputs.clear();
        warned.clear();
        activeGuilds.clear();
        worker.shutdownNow();
    }
}
