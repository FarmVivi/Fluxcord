package fr.farmvivi.fluxcord.plugins.aiaudio.realtime;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import fr.farmvivi.fluxcord.plugins.aiaudio.ai.ChatModel;

import java.util.List;
import java.util.Map;

/**
 * What a full-duplex service understands, as pure functions: frames in, frames out, nothing else.
 *
 * <p>Separated from the socket on purpose. A WebSocket conversation with a hosted service cannot be exercised
 * from a test, but <em>what we send</em> and <em>what we do with what arrives</em> can be, completely — which
 * is the same split the rest of this plugin uses to keep the untestable part down to a few lines of plumbing
 * ({@link RealtimeSession}).
 *
 * <p><strong>Why this is an interface rather than one class.</strong> There are two hosted services worth
 * talking to and they share no wire format at all: OpenAI names an event in a {@code "type"} field and takes
 * its model in the URL, Google puts the model in the first frame and tells you what happened by which
 * top-level key is present. The vocabulary above the wire is the same, though — audio arrives, somebody
 * spoke, the model wants a tool — so {@link Event} is shared and only the spelling differs. That is the
 * whole reason {@link RealtimeConversation} did not have to change to gain a second provider.
 *
 * <p>A dialect that has no frame for something returns an <strong>empty string</strong>, and the caller sends
 * nothing. This is not a shortcut: on Google's API interruption and the decision to answer are the server's
 * alone, so there is genuinely nothing to send, and an exception or a dummy frame would both be worse than
 * saying so.
 *
 * <p><strong>Neither dialect has been run against its real service from this repository.</strong> Both are
 * built from the published protocol. Everything below the socket is covered by tests, so what this plugin
 * does with a frame is known; whether the service sends that frame under that name is not.
 */
public interface RealtimeProtocol {

    /** Mono, which every one of these services wants. */
    int CHANNELS = 1;

    /**
     * @return the sample rate this service wants to be fed, in hertz; Discord's 48 kHz stereo is resampled to
     *         it on the way in
     */
    int inputSampleRate();

    /**
     * The opening frame: who the bot is, which voice it uses, and what it may call.
     *
     * <p>Server-side turn detection is asked for where it is optional, because that is what makes the
     * conversation full duplex — the service decides when somebody has stopped talking, instead of this
     * plugin polling for silence as the turn-based path has to.
     *
     * @param instructions the system prompt; operator-built, exactly as in the turn-based path
     * @param voice        the provider's voice name
     * @param tools        what the model may call, possibly empty
     * @return the frame to send first
     */
    String session(String instructions, String voice, List<ChatModel.Tool> tools);

    /**
     * One chunk of what somebody is saying.
     *
     * @param audio the captured audio, in any rate this plugin handles
     * @return the frame to send
     */
    String appendAudio(PcmAudio audio);

    /**
     * @return the frame that says the speaker has finished, for a service that does not decide by itself, or
     *         an empty string for one that does
     */
    String commitAudio();

    /**
     * @return the frame that asks for an answer now, or an empty string for a service that answers on its own
     *         as soon as it has what it needs
     */
    String createResponse();

    /**
     * Stops the bot mid-sentence.
     *
     * <p>Half of what full duplex means: somebody starts talking over the bot and it stops, rather than
     * finishing its paragraph into the noise. The queued audio has to be dropped on this side as well, which
     * is {@code PcmSendHandler.clear()} and is done whether or not there is a frame to send.
     *
     * @return the frame to send, or an empty string where the service cancels by itself
     */
    String cancelResponse();

    /**
     * Says who is talking now.
     *
     * <p><strong>This is the answer to the one problem a full-duplex session creates for this plugin.</strong>
     * A session has a single input buffer, while the whole design hears each person separately — so
     * attribution would be lost the moment two people share a session. Naming the speaker as a text item
     * before their audio keeps it, at utterance granularity, which is the granularity the segmenter already
     * works in.
     *
     * <p>The name goes in a <em>user</em> item, never the instructions: a display name is chosen by its owner,
     * and the trust boundary is the same here as everywhere else in this plugin.
     *
     * @param displayName the speaker, as the others in the server see them
     * @return the frame to send
     */
    String speakerChanged(String displayName);

    /**
     * What a tool answered.
     *
     * <p>Takes the whole call rather than its id because the two services disagree about what identifies a
     * result: OpenAI matches on the call id, Google wants the function's name as well.
     *
     * @param call   what the model asked for
     * @param output what the tool found, as text
     * @return the frame to send
     */
    String toolResult(ChatModel.ToolCall call, String output);

    /**
     * @param apiKey the configured key
     * @return the headers the handshake needs; empty for a service that authenticates in the URL
     */
    Map<String, String> headers(String apiKey);

    /**
     * @param url    the configured URL
     * @param apiKey the configured key
     * @return the URL to open, which for a service that authenticates by query parameter is not the one that
     *         was configured
     */
    String endpoint(String url, String apiKey);

    /**
     * Reads one frame from the service.
     *
     * <p>Anything not recognised becomes {@link Event.Ignored} rather than a failure: these services have
     * dozens of server events and this plugin acts on six of them, so treating the rest as errors would mean
     * a protocol that breaks every time the service gains a feature.
     *
     * @param frame the text frame as received
     * @return what happened
     */
    Event parse(String frame);

    /**
     * Reads one frame that may say several things at once.
     *
     * <p>Needed because one dialect packs a turn's audio, its transcription and the fact that the turn is
     * over into a single frame, and returning only the first of those would mean a turn that is never
     * recorded. A dialect whose frames say one thing each keeps the default.
     *
     * @param frame the text frame as received
     * @return everything that frame said, in the order it should be acted on; never empty
     */
    default List<Event> parseAll(String frame) {
        return List.of(parse(frame));
    }

    /** Something the service said, reduced to what this plugin acts on. */
    sealed interface Event {

        /** A piece of the bot's voice, ready to play. */
        record AudioDelta(PcmAudio audio) implements Event {
        }

        /** A piece of what the bot is saying, in words, which is what the memory keeps. */
        record TranscriptDelta(String text) implements Event {
        }

        /** What somebody in the channel said, as the service transcribed it. */
        record HeardFromSomebody(String text) implements Event {
        }

        /** Somebody started talking: if the bot is speaking, it should stop. */
        record SpeechStarted() implements Event {
        }

        /** Somebody stopped talking; the service will answer on its own. */
        record SpeechStopped() implements Event {
        }

        /** The model wants something looked up before it answers. */
        record ToolCalled(ChatModel.ToolCall call) implements Event {
        }

        /** The answer is finished. */
        record ResponseDone() implements Event {
        }

        /**
         * Something went wrong, in words a user could be shown.
         *
         * @param message what happened
         */
        record Failure(String message) implements Event {
        }

        /**
         * A frame this plugin does not act on.
         *
         * @param type the event type, for a debug line
         */
        record Ignored(String type) implements Event {
        }
    }
}
