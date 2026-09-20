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
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
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

    private final Guild guild;
    private final EventManager eventManager;
    // Gestionnaires pour les handlers et les priorités
    private final Map<String, SourceHandler> sendHandlers = new ConcurrentHashMap<>();
    private final Map<String, AudioReceiveHandler> receiveHandlers = new ConcurrentHashMap<>();
    private final PriorityManager priorityManager;
    // Mixeur et état
    private final AudioMixer mixer;
    private final ReentrantLock strategyLock = new ReentrantLock();
    // Décision de la frame en cours (calculée dans canProvide, consommée par provide20MsAudio/isOpus)
    private final AtomicReference<SendStrategy.Decision> decision = new AtomicReference<>(SendStrategy.Decision.SILENT);
    // Buffer BE réutilisable unique pour 20ms (simplifié)
    private final byte[] beFrameBuffer = new byte[FRAME_SIZE_BYTES];
    private int priorityThreshold = AudioService.DEFAULT_PRIORITY_THRESHOLD;
    // Source prioritaire de la frame précédente (pour ne démarrer les fades qu'"'"'aux transitions)
    private String lastActivePluginName = null;

    /**
     * Crée un nouveau pipeline audio pour une guilde.
     *
     * @param guild        la guilde
     * @param eventManager le gestionnaire d'événements
     */
    public AudioPipeline(Guild guild, EventManager eventManager) {
        this(guild, eventManager, AudioSettings.DEFAULTS);
    }

    public AudioPipeline(Guild guild, EventManager eventManager, AudioSettings settings) {
        this.guild = guild;
        this.eventManager = eventManager;
        this.mixer = new AudioMixer();
        this.priorityManager = new PriorityManager(settings.fadeSteps(), settings.duckingFloor());

        // Connecte ce pipeline au AudioManager de la guilde
        guild.getAudioManager().setSendingHandler(this);
        guild.getAudioManager().setReceivingHandler(this);

        logger.debug("Created audio pipeline for guild {}", guild.getName());
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
        String pluginName = plugin.getId(); // keyed by id like the rest of the core (P2)
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
        String pluginName = plugin.getId(); // keyed by id like the rest of the core (P2)
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
        String pluginName = plugin.getId(); // keyed by id like the rest of the core (P2)
        receiveHandlers.put(pluginName, handler);
        logger.debug("Registered receive handler for plugin {} in guild {}", pluginName, guild.getName());
    }

    /**
     * Désenregistre un handler de réception audio pour un plugin.
     *
     * @param plugin le plugin
     */
    public void deregisterReceiveHandler(Plugin plugin) {
        String pluginName = plugin.getId(); // keyed by id like the rest of the core (P2)
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
        String pluginName = plugin.getId(); // keyed by id like the rest of the core (P2)
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
        return sendHandlers.containsKey(plugin.getId());
    }

    /**
     * Vérifie si un plugin a un handler de réception actif.
     *
     * @param plugin le plugin
     * @return true si le plugin a un handler de réception actif
     */
    public boolean hasReceiveHandler(Plugin plugin) {
        return receiveHandlers.containsKey(plugin.getId());
    }

    /**
     * Obtient le handler d'envoi audio pour un plugin.
     *
     * @param plugin le plugin
     * @return le handler d'envoi audio, ou null s'il n'existe pas
     */
    public AudioSendHandler getSendHandler(Plugin plugin) {
        SourceHandler handler = sendHandlers.get(plugin.getId());
        return handler != null ? handler.getHandler() : null;
    }

    /**
     * Obtient le handler de réception audio pour un plugin.
     *
     * @param plugin le plugin
     * @return le handler de réception audio, ou null s'il n'existe pas
     */
    public AudioReceiveHandler getReceiveHandler(Plugin plugin) {
        return receiveHandlers.get(plugin.getId());
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
            if (sendHandlers.isEmpty()) {
                decision.set(SendStrategy.Decision.SILENT);
                return false;
            }

            // Un seul appel canProvide() par source et par frame ; la décision est réutilisée par provide20MsAudio()
            List<SendStrategy.SourceState> states = new ArrayList<>(sendHandlers.size());
            for (Map.Entry<String, SourceHandler> entry : sendHandlers.entrySet()) {
                SourceHandler source = entry.getValue();
                AudioSendHandler handler = source.getHandler();
                states.add(new SendStrategy.SourceState(entry.getKey(), handler.canProvide(), handler.isOpus(), source.getPriority()));
            }
            decision.set(SendStrategy.decide(states, priorityThreshold));

            // Ducking : une source prioritaire active fait descendre les autres, sa disparition les fait remonter
            String ducking = decision.get().duckingSource();
            if (ducking != null) {
                if (!ducking.equals(lastActivePluginName)) {
                    startFade(ducking);
                }
            } else if (lastActivePluginName != null) {
                startFadeIn();
            }
            lastActivePluginName = ducking;

            return decision.get().mode() != SendStrategy.Mode.SILENT;
        } finally {
            strategyLock.unlock();
        }
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        strategyLock.lock();
        try {
            SendStrategy.Decision frame = decision.get();
            if (frame.mode() == SendStrategy.Mode.SILENT) {
                return null;
            }

            // Les fades avancent d'un pas par frame pour toutes les sources, quel que soit le mode
            for (String pluginName : sendHandlers.keySet()) {
                priorityManager.updateFade(pluginName);
            }

            ByteBuffer audio;
            int activeSourceCount = 0;
            boolean bypass = frame.mode() == SendStrategy.Mode.BYPASS;

            if (bypass) {
                SourceHandler source = sendHandlers.get(frame.bypassSource());
                audio = source != null ? source.getHandler().provide20MsAudio() : null;
                if (audio != null) {
                    activeSourceCount = 1;
                    // Opus : relais tel quel. PCM : volume × fade appliqués pendant la conversion LE->BE.
                    if (!frame.opusOutput()) {
                        audio = ensureBigEndianFrame(audio, calculateEffectiveVolume(frame.bypassSource(), source));
                    }
                }
            } else {
                mixer.reset();
                for (String pluginName : frame.mixSources()) {
                    SourceHandler source = sendHandlers.get(pluginName);
                    if (source == null) {
                        continue; // désenregistrée entre canProvide() et provide20MsAudio()
                    }
                    ByteBuffer sourceAudio = source.getHandler().provide20MsAudio();
                    if (sourceAudio != null) {
                        mixer.addSource(sourceAudio, calculateEffectiveVolume(pluginName, source));
                        activeSourceCount++;
                    }
                }
                audio = mixer.mix(); // déjà big-endian : aucune passe supplémentaire
            }

            // Un événement par frame (50/s par guilde) : construit et dispatché seulement si quelqu'un écoute
            if (eventManager.hasListeners(AudioFrameMixedEvent.class)) {
                eventManager.fireEvent(new AudioFrameMixedEvent(guild, activeSourceCount, bypass, audio != null));
            }
            return audio;
        } finally {
            strategyLock.unlock();
        }
    }

    @Override
    public boolean isOpus() {
        // Format de sortie de la frame en cours, décidé dans canProvide()
        return decision.get().opusOutput();
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