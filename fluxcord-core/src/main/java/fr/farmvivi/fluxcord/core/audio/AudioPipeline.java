package fr.farmvivi.fluxcord.core.audio;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.audio.events.AudioFrameMixedEvent;
import fr.farmvivi.fluxcord.api.audio.events.AudioVolumeChangedEvent;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.CombinedAudio;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pipeline audio pour une guilde spécifique.
 * Gère le mixage de plusieurs sources audio et la réception audio.
 */
public class AudioPipeline implements AudioSendHandler, AudioReceiveHandler {
    private static final Logger logger = LoggerFactory.getLogger(AudioPipeline.class);
    private static final int FRAME_SIZE_BYTES = 3840; // 20ms @ 48kHz, 2ch, 16-bit

    // Constantes pour le fondu audio
    private static final int FADE_DURATION_MS = 200;
    private static final int FRAME_DURATION_MS = 20;
    private static final int FADE_STEPS = FADE_DURATION_MS / FRAME_DURATION_MS;
    private final Guild guild;
    private final EventManager eventManager;
    // Gestionnaires pour les handlers et les priorités
    private final Map<String, SourceHandler> sendHandlers = new ConcurrentHashMap<>();
    private final Map<String, AudioReceiveHandler> receiveHandlers = new ConcurrentHashMap<>();
    private final PriorityManager priorityManager;
    // Mixeur et état
    private final AudioMixer mixer;
    private final ReentrantLock strategyLock = new ReentrantLock();
    // Cache par frame
    private final Map<String, Boolean> frameCanProvide = new ConcurrentHashMap<>();
    // Buffer BE réutilisable unique pour 20ms (simplifié)
    private final byte[] beFrameBuffer = new byte[FRAME_SIZE_BYTES];
    private int priorityThreshold = AudioService.DEFAULT_PRIORITY_THRESHOLD;
    // État cache
    private String lastActivePluginName = null;
    private String selectedBypassPluginName = null;
    private boolean bypassModeComputed = true;
    // Indique le format de sortie courant (décidé à chaque frame dans canProvide)
    private volatile boolean outputIsOpus = false;

    /**
     * Crée un nouveau pipeline audio pour une guilde.
     *
     * @param guild        la guilde
     * @param eventManager le gestionnaire d'événements
     */
    public AudioPipeline(Guild guild, EventManager eventManager) {
        this.guild = guild;
        this.eventManager = eventManager;
        this.mixer = new AudioMixer();
        this.priorityManager = new PriorityManager(FADE_STEPS);

        // Connecte ce pipeline au AudioManager de la guilde
        guild.getAudioManager().setSendingHandler(this);
        guild.getAudioManager().setReceivingHandler(this);

        logger.debug("Created audio pipeline for guild {}", guild.getName());
    }

    /**
     * Convertit une trame PCM little-endian en BigEndian 20ms (3840 octets) dans un buffer réutilisable.
     * - Swap par échantillon 16-bit (LE -> BE)
     * - Tronque/Pad pour assurer exactement 3840 octets
     * - Retourne un ByteBuffer array-backed pointant sur le buffer interne réutilisé
     */
    private ByteBuffer ensureBigEndianFrame(ByteBuffer le) {
        return ensureBigEndianFrame(le, 1.0f);
    }

    /**
     * Convertit une trame PCM little-endian en big-endian (attente JDA) dans le buffer réutilisable, en
     * appliquant {@code gain} pendant la même passe (volume × fade d'une source relayée seule). Avec un gain de
     * 1 la boucle se réduit à l'échange d'octets.
     */
    private ByteBuffer ensureBigEndianFrame(ByteBuffer le, float gain) {
        // Lecture: s'assurer d'un tableau source
        byte[] src;
        int srcOff;
        int len = le.remaining();
        if (le.hasArray()) {
            src = le.array();
            srcOff = le.arrayOffset() + le.position();
        } else {
            // Copier minimalement vers un tampon temporaire local
            src = new byte[len];
            int pos = le.position();
            le.get(src);
            le.position(pos);
            srcOff = 0;
        }

        // Utilise le buffer BE réutilisable unique
        byte[] dst = beFrameBuffer;

        int copy = Math.min(len, FRAME_SIZE_BYTES);
        int i = 0;
        // Conversion LE->BE pour la partie à copier
        if (gain == 1.0f) {
            for (; i + 1 < copy; i += 2) {
                byte lo = src[srcOff + i];
                byte hi = src[srcOff + i + 1];
                dst[i] = hi;
                dst[i + 1] = lo;
            }
        } else {
            for (; i + 1 < copy; i += 2) {
                int sample = (short) ((src[srcOff + i] & 0xFF) | (src[srcOff + i + 1] << 8));
                int scaled = (int) (sample * gain);
                if (scaled > Short.MAX_VALUE) scaled = Short.MAX_VALUE;
                if (scaled < Short.MIN_VALUE) scaled = Short.MIN_VALUE;
                dst[i] = (byte) (scaled >> 8);
                dst[i + 1] = (byte) scaled;
            }
        }
        // Si nombre impair (ne devrait pas arriver), compléter le dernier octet par 0
        if ((copy & 1) == 1) {
            dst[copy - 1] = 0;
        }
        // Padding si nécessaire
        for (; i < FRAME_SIZE_BYTES; i++) {
            dst[i] = 0;
        }
        return ByteBuffer.wrap(dst);
    }

    /**
     * Enregistre un handler d'envoi audio pour un plugin.
     *
     * @param plugin   le plugin
     * @param handler  le handler d'envoi audio
     * @param volume   le volume initial (0-100)
     * @param priority la priorité (0-100)
     */
    public void registerSendHandler(Plugin plugin, AudioSendHandler handler, int volume, int priority) {
        String pluginName = plugin.getName();
        SourceHandler sourceHandler = new SourceHandler(handler, volume, priority);
        sendHandlers.put(pluginName, sourceHandler);

        logger.debug("Registered send handler for plugin {} in guild {}", pluginName, guild.getName());
    }

    /**
     * Désenregistre un handler d'envoi audio pour un plugin.
     *
     * @param plugin le plugin
     */
    public void deregisterSendHandler(Plugin plugin) {
        String pluginName = plugin.getName();
        sendHandlers.remove(pluginName);

        // Réinitialise l'état du dernier plugin actif si nécessaire
        if (pluginName.equals(lastActivePluginName)) {
            lastActivePluginName = null;
        }

        logger.debug("Deregistered send handler for plugin {} in guild {}", pluginName, guild.getName());
    }

    /**
     * Enregistre un handler de réception audio pour un plugin.
     *
     * @param plugin  le plugin
     * @param handler le handler de réception audio
     */
    public void registerReceiveHandler(Plugin plugin, AudioReceiveHandler handler) {
        String pluginName = plugin.getName();
        receiveHandlers.put(pluginName, handler);
        logger.debug("Registered receive handler for plugin {} in guild {}", pluginName, guild.getName());
    }

    /**
     * Désenregistre un handler de réception audio pour un plugin.
     *
     * @param plugin le plugin
     */
    public void deregisterReceiveHandler(Plugin plugin) {
        String pluginName = plugin.getName();
        receiveHandlers.remove(pluginName);
        logger.debug("Deregistered receive handler for plugin {} in guild {}", pluginName, guild.getName());
    }

    /**
     * Définit le volume pour un plugin.
     *
     * @param plugin le plugin
     * @param volume le volume (0-100)
     */
    public void setVolume(Plugin plugin, int volume) {
        String pluginName = plugin.getName();
        SourceHandler sourceHandler = sendHandlers.get(pluginName);
        if (sourceHandler != null) {
            int oldVolume = sourceHandler.getBaseVolume();
            sourceHandler.setBaseVolume(volume);

            // Émet un événement de changement de volume
            AudioVolumeChangedEvent event = new AudioVolumeChangedEvent(guild, plugin, oldVolume, volume, false);
            eventManager.fireEvent(event);

            logger.debug("Set volume to {} for plugin {} in guild {}", volume, pluginName, guild.getName());
        }
    }

    /**
     * Définit le seuil de priorité pour ce pipeline.
     *
     * @param threshold le seuil de priorité (0-100)
     */
    public void setPriorityThreshold(int threshold) {
        this.priorityThreshold = threshold;
        logger.debug("Set priority threshold to {} for guild {}", threshold, guild.getName());
    }

    /**
     * Vérifie si un plugin a un handler d'envoi actif.
     *
     * @param plugin le plugin
     * @return true si le plugin a un handler d'envoi actif
     */
    public boolean hasSendHandler(Plugin plugin) {
        return sendHandlers.containsKey(plugin.getName());
    }

    /**
     * Vérifie si un plugin a un handler de réception actif.
     *
     * @param plugin le plugin
     * @return true si le plugin a un handler de réception actif
     */
    public boolean hasReceiveHandler(Plugin plugin) {
        return receiveHandlers.containsKey(plugin.getName());
    }

    /**
     * Obtient le handler d'envoi audio pour un plugin.
     *
     * @param plugin le plugin
     * @return le handler d'envoi audio, ou null s'il n'existe pas
     */
    public AudioSendHandler getSendHandler(Plugin plugin) {
        SourceHandler handler = sendHandlers.get(plugin.getName());
        return handler != null ? handler.getHandler() : null;
    }

    /**
     * Obtient le handler de réception audio pour un plugin.
     *
     * @param plugin le plugin
     * @return le handler de réception audio, ou null s'il n'existe pas
     */
    public AudioReceiveHandler getReceiveHandler(Plugin plugin) {
        return receiveHandlers.get(plugin.getName());
    }

    /**
     * Vérifie si le pipeline est vide (aucun handler actif).
     *
     * @return true si le pipeline est vide
     */
    public boolean isEmpty() {
        return sendHandlers.isEmpty() && receiveHandlers.isEmpty();
    }

    /**
     * Ferme le pipeline et libère les ressources.
     */
    public void close() {
        // Déconnecte ce pipeline du AudioManager de la guilde
        guild.getAudioManager().setSendingHandler(null);
        guild.getAudioManager().setReceivingHandler(null);
        guild.getAudioManager().closeAudioConnection();

        // Vide les collections
        sendHandlers.clear();
        receiveHandlers.clear();

        logger.debug("Closed audio pipeline for guild {}", guild.getName());
    }

    //
    // Implémentation de AudioSendHandler
    //

    @Override
    public boolean canProvide() {
        strategyLock.lock();
        try {
            // Réinitialise le cache du frame
            frameCanProvide.clear();
            selectedBypassPluginName = null;

            bypassModeComputed = true;
            outputIsOpus = false; // défaut: PCM (mixage)

            if (sendHandlers.isEmpty()) return false;

            // Évalue chaque source pour ce frame (un seul appel canProvide par frame et par source)
            int pcmActive = 0;
            int opusActive = 0;
            String singlePcmPlugin = null;
            String singleOpusPlugin = null;


            boolean highPriorityActive = false;
            String highPriorityPluginName = null;

            for (Map.Entry<String, SourceHandler> entry : sendHandlers.entrySet()) {
                String pluginName = entry.getKey();
                SourceHandler sourceHandler = entry.getValue();
                AudioSendHandler handler = sourceHandler.getHandler();

                boolean can = handler.canProvide();
                frameCanProvide.put(pluginName, can);
                if (!can) continue;

                if (!handler.isOpus()) {
                    pcmActive++;
                    singlePcmPlugin = pluginName; // si 1 seul, ce sera celui-ci
                } else {
                    opusActive++;
                    singleOpusPlugin = pluginName; // si 1 seul, ce sera celui-ci
                }

                // Détection haute priorité (sur toute source, utile pour fade)
                int priority = sourceHandler.getPriority();
                if (priority >= priorityThreshold) {
                    highPriorityActive = true;
                    highPriorityPluginName = pluginName;
                }
            }

            // Choix de stratégie dynamique
            if (pcmActive >= 2) {
                // Mixage PCM
                bypassModeComputed = false;
                outputIsOpus = false;
            } else if (pcmActive == 1) {
                // Bypass d'une unique source PCM
                selectedBypassPluginName = singlePcmPlugin;
                bypassModeComputed = true;
                outputIsOpus = false;
            } else if (pcmActive == 0) {
                // Aucune source PCM active — gérer les sources Opus
                if (opusActive == 0) {
                    // Rien à fournir
                    return false;
                }

                // Si une seule source Opus active -> bypass direct Opus
                if (opusActive == 1) {
                    selectedBypassPluginName = singleOpusPlugin;
                } else {
                    // Plusieurs sources Opus actives: choisir celle de plus haute priorité si possible
                    if (highPriorityActive && highPriorityPluginName != null &&
                            Boolean.TRUE.equals(frameCanProvide.get(highPriorityPluginName))) {
                        selectedBypassPluginName = highPriorityPluginName;
                    } else {
                        // À défaut, choisir la première qui peut fournir
                        for (Map.Entry<String, Boolean> e : frameCanProvide.entrySet()) {
                            if (Boolean.TRUE.equals(e.getValue())) {
                                selectedBypassPluginName = e.getKey();
                                break;
                            }
                        }
                    }
                }

                bypassModeComputed = true;
                outputIsOpus = true; // on relaie l'Opus tel quel
            }

            // Gestion des fades selon la haute priorité détectée
            if (highPriorityActive) {
                if (lastActivePluginName == null || !lastActivePluginName.equals(highPriorityPluginName)) {
                    startFade(highPriorityPluginName);
                }
                lastActivePluginName = highPriorityPluginName;
            } else {
                if (lastActivePluginName != null) {
                    startFadeIn();
                }
                lastActivePluginName = null;
            }

            return true;
        } finally {
            strategyLock.unlock();
        }
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        strategyLock.lock();
        try {
            ByteBuffer audio;
            int activeSourceCount = 0;
            boolean bypassMode = bypassModeComputed;
            boolean sendOpus = outputIsOpus;

            float bypassGain = 1.0f;
            if (bypassMode) {
                // Les fades avancent aussi en bypass, sinon un fade-in ne progresserait jamais
                for (String pluginName : sendHandlers.keySet()) {
                    priorityManager.updateFade(pluginName);
                }
                // Mode bypass dynamique: plugin sélectionné dans canProvide()
                if (selectedBypassPluginName == null) {
                    audio = null;
                } else {
                    SourceHandler sourceHandler = sendHandlers.get(selectedBypassPluginName);
                    AudioSendHandler handler = sourceHandler != null ? sourceHandler.getHandler() : null;
                    if (handler != null && Boolean.TRUE.equals(frameCanProvide.get(selectedBypassPluginName))) {
                        audio = handler.provide20MsAudio();
                        activeSourceCount = (audio != null ? 1 : 0);
                        // Ajuste le format de sortie effectif selon le handler (sécurité)
                        sendOpus = handler.isOpus();
                        // Volume × fade de la source relayée, appliqué pendant la conversion LE->BE (PCM seulement)
                        bypassGain = calculateEffectiveVolume(selectedBypassPluginName, sourceHandler);
                    } else {
                        audio = null;
                    }
                }
            } else {
                // Mode mixage PCM
                mixer.reset();

                for (Map.Entry<String, SourceHandler> entry : sendHandlers.entrySet()) {
                    String pluginName = entry.getKey();
                    SourceHandler sourceHandler = entry.getValue();
                    AudioSendHandler handler = sourceHandler.getHandler();

                    // Uniquement les sources PCM actives selon le cache
                    if (handler.isOpus()) {
                        priorityManager.updateFade(pluginName);
                        continue;
                    }
                    if (!Boolean.TRUE.equals(frameCanProvide.get(pluginName))) {
                        priorityManager.updateFade(pluginName);
                        continue;
                    }

                    ByteBuffer sourceAudio = handler.provide20MsAudio();
                    // Avance le fade avant de lire le multiplicateur : un fade démarré dans canProvide()
                    // est ainsi audible dès cette frame, pas 20 ms plus tard.
                    priorityManager.updateFade(pluginName);
                    if (sourceAudio != null) {
                        float effectiveVolume = calculateEffectiveVolume(pluginName, sourceHandler);
                        mixer.addSource(sourceAudio, effectiveVolume);
                        activeSourceCount++;
                    }
                }

                audio = mixer.mix();
                sendOpus = false; // mixage -> PCM
            }

            // Un événement par frame (50/s par guilde) : construit et dispatché seulement si quelqu'un écoute
            if (eventManager.hasListeners(AudioFrameMixedEvent.class)) {
                eventManager.fireEvent(new AudioFrameMixedEvent(guild, activeSourceCount, bypassMode, audio != null));
            }

            // Met à jour l'état (aucun autre état persistant requis ici)

            // Si on envoie de l'Opus (bypass), retourner directement le buffer
            if (audio != null) {
                if (sendOpus) {
                    return audio;
                } else {
                    // JDA attend du PCM BigEndian si isOpus() == false
                    return ensureBigEndianFrame(audio, bypassGain);
                }
            }
            return null;
        } finally {
            strategyLock.unlock();
        }
    }

    @Override
    public boolean isOpus() {
        // Retourne le format de sortie courant décidé dans canProvide()
        return outputIsOpus;
    }

    @Override
    public boolean canReceiveCombined() {
        // Vérifie si au moins un handler peut recevoir l'audio combiné
        for (AudioReceiveHandler handler : receiveHandlers.values()) {
            if (handler.canReceiveCombined()) {
                return true;
            }
        }
        return false;
    }

    //
    // Implémentation de AudioReceiveHandler
    //

    @Override
    public boolean canReceiveUser() {
        // Vérifie si au moins un handler peut recevoir l'audio par utilisateur
        for (AudioReceiveHandler handler : receiveHandlers.values()) {
            if (handler.canReceiveUser()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canReceiveEncoded() {
        // Vérifie si au moins un handler peut recevoir l'audio encodé
        for (AudioReceiveHandler handler : receiveHandlers.values()) {
            if (handler.canReceiveEncoded()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void handleCombinedAudio(CombinedAudio combinedAudio) {
        // Propage l'audio combiné à tous les handlers intéressés
        for (AudioReceiveHandler handler : receiveHandlers.values()) {
            if (handler.canReceiveCombined()) {
                handler.handleCombinedAudio(combinedAudio);
            }
        }
    }

    @Override
    public void handleUserAudio(UserAudio userAudio) {
        // Propage l'audio par utilisateur à tous les handlers intéressés
        for (AudioReceiveHandler handler : receiveHandlers.values()) {
            if (handler.canReceiveUser()) {
                handler.handleUserAudio(userAudio);
            }
        }
    }

    @Override
    public void handleEncodedAudio(net.dv8tion.jda.api.audio.OpusPacket opusPacket) {
        // Propage l'audio encodé à tous les handlers intéressés
        for (AudioReceiveHandler handler : receiveHandlers.values()) {
            if (handler.canReceiveEncoded()) {
                handler.handleEncodedAudio(opusPacket);
            }
        }
    }

    /**
     * Démarre un fondu sortant (fade out) pour toutes les sources sauf celle spécifiée.
     *
     * @param activePlugin le plugin qui reste à volume normal
     */
    private void startFade(String activePlugin) {
        for (String pluginName : sendHandlers.keySet()) {
            if (!pluginName.equals(activePlugin)) {
                priorityManager.startFadeOut(pluginName);
            }
        }
    }

    //
    // Méthodes de gestion des fades
    //

    /**
     * Démarre un fondu entrant (fade in) pour toutes les sources.
     */
    private void startFadeIn() {
        for (String pluginName : sendHandlers.keySet()) {
            priorityManager.startFadeIn(pluginName);
        }
    }

    /**
     * Calcule le volume effectif pour une source, en tenant compte des fades.
     *
     * @param pluginName    le nom du plugin
     * @param sourceHandler le handler de source
     * @return le volume effectif (0.0-1.0)
     */
    private float calculateEffectiveVolume(String pluginName, SourceHandler sourceHandler) {
        float baseVolume = sourceHandler.getBaseVolume() / 100.0f;
        float fadeMultiplier = priorityManager.getFadeMultiplier(pluginName);
        return baseVolume * fadeMultiplier;
    }
}