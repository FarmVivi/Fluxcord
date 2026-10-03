package fr.farmvivi.fluxcord.plugins.aiaudio;

import fr.farmvivi.fluxcord.plugins.aiaudio.ai.TextToSpeech;
import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.api.audio.PcmSendHandler;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Makes the bot speak in a voice channel.
 *
 * <p>Synthesis is an HTTP round trip of a few hundred milliseconds, so it never runs on the caller's
 * thread: {@link #speak} returns a future and the command defers its reply. Playback itself is one
 * {@link PcmSendHandler} per guild, kept registered until the plugin stops using the guild — one
 * handler per sentence would make the voice pipeline close the connection between two answers.
 */
public class TextToSpeechService {

    private final AIAudioPlugin plugin;
    private final TextToSpeech provider;
    private final Logger logger;
    private final ExecutorService worker;
    private final Map<String, PcmSendHandler> handlers = new ConcurrentHashMap<>();

    /**
     * @param plugin   the owning plugin, used for its logger, settings and audio service
     * @param provider what turns text into audio
     */
    public TextToSpeechService(AIAudioPlugin plugin, TextToSpeech provider) {
        this.plugin = plugin;
        this.provider = provider;
        this.logger = plugin.getLogger();
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ai-audio-tts");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Synthesises {@code text} and queues it for playback in {@code guild}.
     *
     * <p>The caller is responsible for having joined a voice channel first.
     *
     * @param guild the guild to speak in
     * @param text  what to say
     * @param voice the voice to use, or null for the configured default
     * @return the audio that was queued; the future fails with
     *         {@link fr.farmvivi.fluxcord.plugins.aiaudio.ai.AiRequestException} when the provider did
     */
    public CompletableFuture<PcmAudio> speak(Guild guild, String text, String voice) {
        AiSettings settings = plugin.getSettings();
        String chosenVoice = voice == null || voice.isBlank() ? settings.voice() : voice.trim();
        return CompletableFuture.supplyAsync(() -> {
            PcmAudio audio = provider.synthesize(text, chosenVoice).toDiscordFormat();
            handlerFor(guild, settings).enqueue(audio);
            logger.debug("Queued {} of speech for guild {}", audio.duration(), guild.getId());
            return audio;
        }, worker);
    }

    /**
     * Queues audio that is already spoken, without synthesising anything.
     *
     * <p>This is the speech-to-speech path: when the chat model answers with a voice of its own there is nothing
     * to synthesise, but the playback side is identical — same handler, same volume, same priority — so it goes
     * through the same queue rather than a second one that would fight it for the connection.
     *
     * @param guild the guild to speak in
     * @param audio what to play, in any sample rate
     * @return the audio as it was queued, converted to what Discord takes
     */
    public PcmAudio play(Guild guild, PcmAudio audio) {
        PcmAudio queued = audio.toDiscordFormat();
        handlerFor(guild, plugin.getSettings()).enqueue(queued);
        logger.debug("Queued {} of spoken answer for guild {}", queued.duration(), guild.getId());
        return queued;
    }

    /**
     * Registers the guild's send handler on first use.
     *
     * <p>Registering is idempotent on the core side, but the handler instance must be the same one
     * across calls or the queued audio would be dropped with the handler it was queued on.
     */
    private PcmSendHandler handlerFor(Guild guild, AiSettings settings) {
        return handlers.computeIfAbsent(guild.getId(), id -> {
            PcmSendHandler handler = new PcmSendHandler();
            plugin.getContext().getAudioService().registerSendHandler(
                    guild, plugin, handler, settings.volume(), settings.priority());
            logger.debug("Registered the speech send handler for guild {} (volume {}, priority {})",
                    id, settings.volume(), settings.priority());
            return handler;
        });
    }

    /**
     * Drops whatever is queued without giving up the voice.
     *
     * <p>Different from {@link #stop}, and the difference matters: {@code stop} also deregisters the handler,
     * which makes the core close the voice connection once the guild's pipeline is empty. For an interruption —
     * somebody talking over the bot — the bot has to fall silent and then keep speaking a moment later, so the
     * handler has to survive.
     *
     * @param guild the guild to cut off
     * @return true when something was actually queued
     */
    public boolean interrupt(Guild guild) {
        PcmSendHandler handler = handlers.get(guild.getId());
        if (handler == null) {
            return false;
        }
        boolean wasSpeaking = !handler.isIdle();
        handler.clear();
        return wasSpeaking;
    }

    /**
     * Stops the bot talking in a guild and releases its send handler.
     *
     * @param guild the guild to fall silent in
     * @return true when something was actually playing or registered
     */
    public boolean stop(Guild guild) {
        PcmSendHandler handler = handlers.remove(guild.getId());
        if (handler == null) {
            return false;
        }
        handler.clear();
        plugin.getContext().getAudioService().deregisterSendHandler(guild, plugin);
        return true;
    }

    /**
     * @param guild the guild to look at
     * @return true when the bot is speaking or has speech queued there
     */
    public boolean isSpeaking(Guild guild) {
        PcmSendHandler handler = handlers.get(guild.getId());
        return handler != null && !handler.isIdle();
    }

    /** Releases every handler and stops the worker thread. Safe to call twice. */
    public void shutdown() {
        handlers.values().forEach(PcmSendHandler::clear);
        handlers.clear();
        worker.shutdownNow();
        try {
            if (!worker.awaitTermination(2, TimeUnit.SECONDS)) {
                logger.warn("The speech worker did not stop within 2 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
