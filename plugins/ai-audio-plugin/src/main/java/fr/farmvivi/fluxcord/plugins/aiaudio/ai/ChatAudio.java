package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import java.util.Locale;
import java.util.Set;

/**
 * Whether the model hears the voice and answers with one, instead of going through transcription and synthesis.
 *
 * <p>A speech-to-speech turn is not a different protocol: the same {@code /chat/completions} carries the
 * recorded audio as an {@code input_audio} part and answers with {@code message.audio}, so an endpoint is all
 * that separates OpenAI's audio models from a self-hosted omni server. It <em>is</em> a different model — a
 * text-only one ignores {@code modalities} at best and refuses the request at worst, which is why both halves
 * default to off.
 *
 * <p>What it buys is what a transcript throws away: how something was said. A model that hears the voice hears
 * the hesitation, the laughter and the shouting, and answers with a voice of its own rather than with a
 * synthesised reading of its text.
 *
 * <p>The two halves are independent on purpose. Hearing costs nothing but bandwidth and works on the round that
 * asks for a tool; speaking is only ever asked for on the round that answers, because synthesising a round that
 * ends in a tool call throws the audio away.
 *
 * @param hear   whether the recorded voice is sent alongside the transcribed sentence
 * @param speak  whether the model is asked to answer with audio
 * @param voice  the provider's voice name for the spoken answer
 * @param format how the answer is encoded; {@code wav} or {@code pcm16}, the two this plugin can decode
 */
public record ChatAudio(boolean hear, boolean speak, String voice, String format) {

    /** The formats {@link #decode} understands. Anything else is rejected with the name in the message. */
    public static final String WAV = "wav";
    /** Raw little-endian 16-bit samples, mono, at {@link #PCM16_SAMPLE_RATE}. */
    public static final String PCM16 = "pcm16";

    /** What OpenAI's audio models emit for {@link #PCM16}, and what an omni server is expected to match. */
    public static final int PCM16_SAMPLE_RATE = 24_000;

    private static final Set<String> DECODABLE = Set.of(WAV, PCM16);

    public ChatAudio {
        voice = voice == null ? "" : voice.strip();
        format = format == null || format.isBlank() ? WAV : format.strip().toLowerCase(Locale.ROOT);
    }

    /** @return neither hearing nor speaking, which is what a text-only model needs */
    public static ChatAudio off() {
        return new ChatAudio(false, false, "", WAV);
    }

    /** @return true when the format is one this plugin can turn back into samples */
    public boolean isDecodable() {
        return DECODABLE.contains(format);
    }
}
