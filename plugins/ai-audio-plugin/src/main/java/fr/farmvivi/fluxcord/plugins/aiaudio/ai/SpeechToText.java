package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;

import java.util.List;

/**
 * Turns speech into text.
 *
 * <p>An interface so the plugin never depends on one provider: the shipped implementation speaks the
 * OpenAI HTTP API, which OpenAI itself and the usual self-hosted servers all understand.
 */
public interface SpeechToText {

    /**
     * Transcribes one segment of speech.
     *
     * @param audio    the speech to transcribe
     * @param language the expected language as a BCP 47 tag ({@code fr-FR}); providers want the bare
     *                 language part, which the implementation extracts
     * @return what was said, trimmed, or an empty string when the provider heard nothing
     * @throws AiRequestException if the provider could not be reached or refused the request
     */
    default String transcribe(PcmAudio audio, String language) {
        return transcribe(audio, language, List.of());
    }

    /**
     * Transcribes one segment of speech, naming words the provider cannot be expected to guess.
     *
     * <p>A speech model only ever writes down something it has a reason to believe exists, so a product
     * name, a nickname or a piece of jargon comes out as the nearest ordinary word — {@code Fluxcord}
     * becomes {@code flux cord}, and no amount of articulation fixes it. Naming the words beforehand is
     * the only lever there is: it is what Whisper's {@code prompt} field is for, and a chat model
     * transcribing audio can simply be told them.
     *
     * @param audio      the speech to transcribe
     * @param language   the expected language as a BCP 47 tag, or {@code auto}
     * @param vocabulary words that may occur and that are likely to be misheard; may be empty
     * @return what was said, trimmed, or an empty string when the provider heard nothing
     * @throws AiRequestException if the provider could not be reached or refused the request
     */
    String transcribe(PcmAudio audio, String language, List<String> vocabulary);
}
