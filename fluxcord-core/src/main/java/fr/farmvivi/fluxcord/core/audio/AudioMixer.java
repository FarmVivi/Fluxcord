package fr.farmvivi.fluxcord.core.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Mixeur audio qui combine plusieurs sources en une seule.
 * Gère le décodage, le mixage et la normalisation des flux audio.
 */
public class AudioMixer {
    // Format d'un frame audio : PCM 48kHz 16-bit stéréo (2 octets par échantillon, 2 canaux)
    private static final int SAMPLE_RATE = 48000;  // Hz
    private static final int FRAME_TIME = 20;      // ms
    private static final int BYTES_PER_SAMPLE = 2;  // 16-bit = 2 bytes
    private static final int CHANNELS = 2;          // Stéréo = 2 canaux
    private static final int FRAME_SIZE = SAMPLE_RATE * FRAME_TIME * BYTES_PER_SAMPLE * CHANNELS / 1000;

    // Seuil pour le hard clipping
    private static final int MAX_VALUE = Short.MAX_VALUE;
    private static final int MIN_VALUE = Short.MIN_VALUE;

    // Buffer de sortie, écrit directement en big-endian (format attendu par JDA) : une seule passe
    private final byte[] output = new byte[FRAME_SIZE];

    // Liste des sources pour ce frame
    private final List<SourceFrame> sources = new ArrayList<>();

    /**
     * Réinitialise le mixeur pour un nouveau frame.
     */
    public void reset() {
        sources.clear();
    }

    /**
     * Ajoute une source audio au mixage.
     *
     * @param buffer le buffer audio de la source (PCM 16-bit ou Opus)
     * @param volume le volume de la source (0.0-1.0)
     */
    public void addSource(ByteBuffer buffer, float volume) {
        if (buffer == null) return;

        // Crée un wrapper pour la source
        SourceFrame source = new SourceFrame(buffer, volume);
        sources.add(source);
    }

    /**
     * Mixe toutes les sources et produit un frame audio PCM.
     *
     * @return le buffer audio mixé, ou null si aucune source n'a fourni d'audio
     */
    public ByteBuffer mix() {
        if (sources.isEmpty()) {
            return null;
        }

        for (SourceFrame source : sources) {
            source.getBuffer().rewind();
        }

        // Somme des échantillons de chaque source (volume appliqué), clipping, écriture big-endian
        int sampleCount = FRAME_SIZE / BYTES_PER_SAMPLE;
        for (int i = 0, out = 0; i < sampleCount; i++, out += 2) {
            int sum = 0;
            for (SourceFrame source : sources) {
                ByteBuffer buffer = source.getBuffer();
                if (buffer.remaining() >= BYTES_PER_SAMPLE) {
                    sum += (int) (buffer.getShort() * source.getVolume());
                }
            }
            if (sum > MAX_VALUE) sum = MAX_VALUE;
            if (sum < MIN_VALUE) sum = MIN_VALUE;
            output[out] = (byte) (sum >> 8);
            output[out + 1] = (byte) sum;
        }

        // Vue en lecture seule sur le buffer interne (réutilisé à la frame suivante)
        return ByteBuffer.wrap(output).asReadOnlyBuffer();
    }

    /**
     * Classe interne représentant une source audio pour un frame.
     */
    private static class SourceFrame {
        private final ByteBuffer buffer;
        private final float volume;

        /**
         * Crée une nouvelle source audio.
         *
         * @param buffer le buffer audio de la source
         * @param volume le volume de la source (0.0-1.0)
         */
        public SourceFrame(ByteBuffer buffer, float volume) {
            // Crée une copie du buffer pour pouvoir le modifier sans affecter l'original
            // Les sources fournissent du PCM little-endian (convention interne) ; la sortie est big-endian
            this.buffer = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            this.volume = volume;
        }

        /**
         * Obtient le buffer audio de la source.
         *
         * @return le buffer audio
         */
        public ByteBuffer getBuffer() {
            return buffer;
        }

        /**
         * Obtient le volume de la source.
         *
         * @return le volume (0.0-1.0)
         */
        public float getVolume() {
            return volume;
        }
    }
}