package fr.farmvivi.fluxcord.plugins.aiaudio.ai;

import fr.farmvivi.fluxcord.plugins.aiaudio.audio.PcmAudio;

/**
 * Turns text into speech.
 *
 * <p>An interface so the plugin never depends on one provider: the shipped implementation speaks the
 * OpenAI HTTP API, which OpenAI itself and the usual self-hosted servers all understand.
 */
public interface TextToSpeech {

    /**
     * Synthesises one utterance.
     *
     * @param text  what to say; must not be blank
     * @param voice the provider's voice name
     * @return the spoken audio, in whatever format the provider answered with
     * @throws AiRequestException if the provider could not be reached or refused the request
     */
    PcmAudio synthesize(String text, String voice);
}
