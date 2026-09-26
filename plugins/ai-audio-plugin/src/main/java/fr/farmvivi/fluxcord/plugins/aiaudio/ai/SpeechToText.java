package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;

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
    String transcribe(PcmAudio audio, String language);
}
