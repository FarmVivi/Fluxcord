package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;
import fr.farmvivi.fluxcord.plugins.aiaudio.conversation.ToolSource;
import fr.farmvivi.fluxcord.plugins.aiaudio.memory.Turn;
import fr.farmvivi.fluxcord.plugins.aiaudio.persona.PersonaSnapshot;
import org.slf4j.Logger;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * One full-duplex conversation: audio goes out as it is captured, audio comes back as it is generated, and
 * either side can interrupt the other.
 *
 * <p>The difference from the turn-based path is not the quality of the answer, it is who decides when a turn is
 * over. There, this plugin waits for a speaker to fall silent, transcribes, asks, synthesises — four steps, each
 * waiting for the one before. Here the service hears continuously and answers while somebody is still talking,
 * which is what makes it possible to cut the bot off mid-sentence.
 *
 * <p><strong>The problem this design had to solve.</strong> A Realtime session has a single input buffer, while
 * this plugin hears each person separately and that attribution is the point of it — the memory is per person,
 * the persona knows who it is talking to. Mixing everyone into one stream would throw that away. So the audio
 * is shared but a <em>text item names the speaker whenever the speaker changes</em>
 * ({@link RealtimeProtocol#speakerChanged}), which keeps attribution at the granularity of an utterance. That
 * is the same granularity the turn-based path works in, so nothing is lost that was previously kept.
 *
 * <p><strong>None of this has been run against the real service.</strong> Every decision here is covered by
 * tests through {@link RealtimeLink}, so what this class does with an event is known; whether OpenAI sends that
 * event under that name is not, from this repository. The turn-based path stays the default for that reason.
 */
public class RealtimeConversation {

    private final Logger logger;
    private final LongSupplier clock;
    private final List<ToolSource> toolSources;
    private final Consumer<PcmAudio> playback;
    private final Runnable stopPlayback;
    private final Consumer<Turn> remember;
    private final Consumer<String> report;

    private RealtimeLink link;
    private PersonaSnapshot snapshot;
    private String botId = "bot";
    private String speaking = "";
    private final StringBuilder transcript = new StringBuilder();
    private boolean failed;

    /**
     * @param logger       where to put the debug trail
     * @param clock        the current time in milliseconds
     * @param toolSources  the groups of tools the model may call, the same ones the turn-based path offers
     * @param playback     where a piece of the bot's voice goes
     * @param stopPlayback drops whatever is queued, for an interruption
     * @param remember     where a finished turn is recorded
     * @param report       where to tell a human that the conversation broke
     */
    public RealtimeConversation(Logger logger, LongSupplier clock, List<ToolSource> toolSources,
                                Consumer<PcmAudio> playback, Runnable stopPlayback,
                                Consumer<Turn> remember, Consumer<String> report) {
        this.logger = logger;
        this.clock = clock;
        this.toolSources = toolSources == null ? List.of() : List.copyOf(toolSources);
        this.playback = playback;
        this.stopPlayback = stopPlayback;
        this.remember = remember;
        this.report = report;
    }

    /**
     * Opens the conversation and tells the service who the bot is.
     *
     * @param snapshot     who the bot is, how it feels and who is present
     * @param instructions the system prompt, built by the same code the turn-based path uses
     * @param voice        the provider's voice name
     * @param botUserId    the bot's own id, so its turns are recognised as its own
     * @param links        how to open the link, given where events should go
     */
    public void start(PersonaSnapshot snapshot, String instructions, String voice, String botUserId,
                      Function<Consumer<RealtimeProtocol.Event>, RealtimeLink> links) {
        this.snapshot = snapshot;
        this.botId = botUserId == null ? "bot" : botUserId;
        this.failed = false;
        this.link = links.apply(this::onEvent);
        link.send(RealtimeProtocol.sessionUpdate(instructions, voice, declaredTools()));
    }

    private List<ChatModel.Tool> declaredTools() {
        return toolSources.stream().flatMap(source -> source.declarations().stream()).toList();
    }

    /**
     * Hands over what somebody is saying, as it is captured.
     *
     * <p>Called from the audio thread, so it does nothing but convert and send — the 20 ms budget there is why
     * {@link RealtimeLink#send} is forbidden to throw.
     *
     * @param userId      who is speaking
     * @param displayName their name as the server shows it
     * @param audio       the captured chunk
     */
    public void hear(String userId, String displayName, PcmAudio audio) {
        if (link == null || !link.isOpen() || audio == null || audio.isEmpty()) {
            return;
        }
        if (!userId.equals(speaking)) {
            // The one thing a single input buffer cannot carry: who is talking.
            speaking = userId;
            link.send(RealtimeProtocol.speakerChanged(displayName));
        }
        link.send(RealtimeProtocol.appendAudio(audio));
    }

    /** Called for every event the service sends. */
    void onEvent(RealtimeProtocol.Event event) {
        switch (event) {
            case RealtimeProtocol.Event.AudioDelta delta -> playback.accept(delta.audio());
            case RealtimeProtocol.Event.TranscriptDelta delta -> transcript.append(delta.text());
            case RealtimeProtocol.Event.SpeechStarted _ -> interrupt();
            case RealtimeProtocol.Event.SpeechStopped _ ->
                    logger.debug("Somebody stopped talking; the service will answer");
            case RealtimeProtocol.Event.HeardFromSomebody heard -> rememberHeard(heard.text());
            case RealtimeProtocol.Event.ToolCalled called -> run(called.call());
            case RealtimeProtocol.Event.ResponseDone _ -> finishTurn();
            case RealtimeProtocol.Event.Failure failure -> fail(failure.message());
            case RealtimeProtocol.Event.Ignored ignored ->
                    logger.debug("Realtime event not acted on: {}", ignored.type());
        }
    }

    /**
     * Somebody talked over the bot, so the bot stops.
     *
     * <p>Both halves are needed and the second is easy to forget: the service is told to stop generating, and
     * the audio already queued on this side is dropped. Cancelling without clearing would leave the bot
     * finishing a sentence the service has already abandoned.
     */
    private void interrupt() {
        if (link == null || !link.isOpen()) {
            return;
        }
        logger.debug("Interrupted: dropping what was queued");
        link.send(RealtimeProtocol.cancelResponse());
        stopPlayback.run();
        // What was being said was cut off, so what is kept is what was actually heard.
        finishTurn();
    }

    /** What the bot said, once the answer is complete, recorded by its words rather than its audio. */
    private void finishTurn() {
        String said = transcript.toString().strip();
        transcript.setLength(0);
        if (said.isEmpty() || snapshot == null) {
            return;
        }
        remember.accept(turn(botId, snapshot.persona().name(), said));
    }

    /** What somebody said, attributed to whoever the service was hearing at the time. */
    private void rememberHeard(String text) {
        if (text == null || text.isBlank() || snapshot == null) {
            return;
        }
        remember.accept(turn(speaking.isEmpty() ? "unknown" : speaking, nameOf(speaking), text.strip()));
    }

    private Turn turn(String userId, String speaker, String text) {
        return new Turn(clock.getAsLong(), userId, speaker,
                snapshot.conversation().guildId(), snapshot.conversation().guildName(),
                snapshot.conversation().channelId(), snapshot.conversation().channelName(), text);
    }

    /** The name the context showed for this person, which is what the memory should read later. */
    private String nameOf(String userId) {
        return snapshot.familiarity().stream()
                .filter(person -> person.userId().equals(userId))
                .map(person -> person.displayName())
                .findFirst()
                .orElse(userId.isEmpty() ? "somebody" : userId);
    }

    /**
     * Runs a tool and hands the result back, then asks for the answer to continue.
     *
     * <p>The groups are the same objects the turn-based path uses, which is what {@code ToolSource} was
     * extracted for: the memory and the web behave identically whichever way the conversation is held.
     */
    private void run(ChatModel.ToolCall call) {
        String result = toolSources.stream()
                .filter(source -> source.handles(call.name()))
                .findFirst()
                .map(source -> source.execute(call, snapshot, clock.getAsLong()))
                .orElseGet(() -> {
                    logger.warn("The model asked for a tool that is not offered: {}", call.name());
                    return "There is no tool called " + call.name() + ".";
                });
        logger.debug("Tool {} answered {} character(s)", call.name(), result.length());
        link.send(RealtimeProtocol.toolResult(call.id(), result));
        link.send(RealtimeProtocol.createResponse());
    }

    /** Said once: a broken connection would otherwise report itself on every frame that follows. */
    private void fail(String message) {
        if (failed) {
            return;
        }
        failed = true;
        logger.warn("The realtime conversation failed: {}", message);
        report.accept(message);
        close();
    }

    /** Closes the conversation. Safe to call twice, and safe to call when it never opened. */
    public void close() {
        if (link != null) {
            link.close();
        }
        transcript.setLength(0);
        speaking = "";
    }

    /** @return true while the conversation can carry audio */
    public boolean isOpen() {
        return link != null && link.isOpen();
    }
}
