package fr.farmvivi.fluxcord.core.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gère les priorités et les fondus (fades) pour l'audio.
 */
public class PriorityManager {
    private static final float FADE_MAX = 1.0f;

    // Niveau plancher d'un fade-out (0 = muet, 0.2 = ducking partiel à 20 %)
    private final float fadeMin;

    // Cartes de l'état des fondus
    private final Map<String, Float> fadeMultipliers = new ConcurrentHashMap<>();
    private final Map<String, Float> fadeIncrements = new ConcurrentHashMap<>();

    // Nombre de pas pour un fondu complet
    private final int fadeSteps;
    private final float fadeStepSize;

    /**
     * Crée un nouveau gestionnaire de priorités.
     *
     * @param fadeSteps nombre de pas pour un fondu complet
     */
    public PriorityManager(int fadeSteps) {
        this(fadeSteps, 0.0f);
    }

    /**
     * @param fadeSteps nombre de frames d'un fondu complet (>= 1)
     * @param fadeMin   multiplicateur atteint en fin de fade-out, entre 0 (muet) et 1
     */
    public PriorityManager(int fadeSteps, float fadeMin) {
        if (fadeSteps < 1) {
            throw new IllegalArgumentException("fadeSteps must be >= 1");
        }
        if (fadeMin < 0.0f || fadeMin > FADE_MAX) {
            throw new IllegalArgumentException("fadeMin must be between 0 and 1");
        }
        this.fadeSteps = fadeSteps;
        this.fadeMin = fadeMin;
        this.fadeStepSize = (FADE_MAX - fadeMin) / fadeSteps;
    }

    /**
     * Démarre un fondu sortant (fade out) pour une source.
     *
     * @param sourceName le nom de la source
     */
    public void startFadeOut(String sourceName) {
        // Initialise le multiplicateur de fondu s'il n'existe pas
        fadeMultipliers.putIfAbsent(sourceName, FADE_MAX);

        // Calcule le pas de diminution pour atteindre 0 en fadeSteps pas
        float increment = -fadeStepSize;
        fadeIncrements.put(sourceName, increment);
    }

    /**
     * Démarre un fondu entrant (fade in) pour une source.
     *
     * @param sourceName le nom de la source
     */
    public void startFadeIn(String sourceName) {
        // Initialise le multiplicateur de fondu s'il n'existe pas
        fadeMultipliers.putIfAbsent(sourceName, fadeMin);

        // Calcule le pas d'augmentation pour atteindre 1 en fadeSteps pas
        float increment = fadeStepSize;
        fadeIncrements.put(sourceName, increment);
    }

    /**
     * Met à jour l'état de fondu pour une source.
     *
     * @param sourceName le nom de la source
     */
    public void updateFade(String sourceName) {
        // Si pas d'incrément, rien à faire
        Float increment = fadeIncrements.get(sourceName);
        if (increment == null) {
            return;
        }

        // Obtient le multiplicateur actuel
        float multiplier = fadeMultipliers.getOrDefault(sourceName, FADE_MAX);

        // Applique l'incrément
        multiplier += increment;

        // Limite le multiplicateur
        if (multiplier <= fadeMin) {
            multiplier = fadeMin;
            fadeIncrements.remove(sourceName);  // Arrête le fondu
        } else if (multiplier >= FADE_MAX) {
            multiplier = FADE_MAX;
            fadeIncrements.remove(sourceName);  // Arrête le fondu
        }

        // Stocke le nouveau multiplicateur
        fadeMultipliers.put(sourceName, multiplier);
    }

    /**
     * Obtient le multiplicateur de fondu pour une source.
     *
     * @param sourceName le nom de la source
     * @return le multiplicateur de fondu (0.0-1.0)
     */
    public float getFadeMultiplier(String sourceName) {
        return fadeMultipliers.getOrDefault(sourceName, FADE_MAX);
    }

    /**
     * Réinitialise l'état de fondu pour une source.
     *
     * @param sourceName le nom de la source
     */
    public void resetFade(String sourceName) {
        fadeMultipliers.put(sourceName, FADE_MAX);
        fadeIncrements.remove(sourceName);
    }

    /**
     * Réinitialise l'état de fondu pour toutes les sources.
     */
    public void resetAllFades() {
        fadeMultipliers.clear();
        fadeIncrements.clear();
    }
}