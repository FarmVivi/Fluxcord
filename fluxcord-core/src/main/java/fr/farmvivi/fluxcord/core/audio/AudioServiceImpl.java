package fr.farmvivi.fluxcord.core.audio;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.audio.events.AudioReceiveHandlerRegisteredEvent;
import fr.farmvivi.fluxcord.api.audio.events.AudioReceiveHandlerRemovedEvent;
import fr.farmvivi.fluxcord.api.audio.events.AudioSendHandlerRegisteredEvent;
import fr.farmvivi.fluxcord.api.audio.events.AudioSendHandlerRemovedEvent;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implémentation du service audio principal.
 * Gère les pipelines audio pour chaque guilde et les événements associés.
 */
public class AudioServiceImpl implements AudioService {
    private static final Logger logger = LoggerFactory.getLogger(AudioServiceImpl.class);

    private final EventManager eventManager;
    private final AudioSettings settings;
    private final Map<String, AudioPipeline> pipelines = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> pluginGuilds = new ConcurrentHashMap<>();

    /**
     * Crée un nouveau service audio.
     *
     * @param eventManager le gestionnaire d'événements
     */
    public AudioServiceImpl(EventManager eventManager) {
        this(eventManager, AudioSettings.DEFAULTS);
    }

    public AudioServiceImpl(EventManager eventManager, AudioSettings settings) {
        this.eventManager = eventManager;
        this.settings = settings;
    }

    @Override
    public void registerSendHandler(Guild guild, Plugin plugin, AudioSendHandler handler, int initialVolume, int priority) {
        if (guild == null || plugin == null || handler == null) {
            throw new IllegalArgumentException("Guild, plugin and handler cannot be null");
        }

        if (initialVolume < MIN_VOLUME || initialVolume > MAX_VOLUME) {
            throw new IllegalArgumentException("Volume must be between " + MIN_VOLUME + " and " + MAX_VOLUME);
        }

        if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
            throw new IllegalArgumentException("Priority must be between " + MIN_PRIORITY + " and " + MAX_PRIORITY);
        }

        String guildId = guild.getId();
        AudioPipeline pipeline = getOrCreatePipeline(guild);

        // Enregistrement du handler
        pipeline.registerSendHandler(plugin, handler, initialVolume, priority);

        // Tracking des guildes par plugin
        pluginGuilds.computeIfAbsent(plugin.getName(), k -> ConcurrentHashMap.newKeySet()).add(guildId);

        // Émission de l'événement
        AudioSendHandlerRegisteredEvent event = new AudioSendHandlerRegisteredEvent(guild, plugin, handler, initialVolume, priority);
        eventManager.fireEvent(event);

        logger.debug("Registered audio send handler for plugin {} in guild {}", plugin.getName(), guild.getName());
    }

    @Override
    public void deregisterSendHandler(Guild guild, Plugin plugin) {
        if (guild == null || plugin == null) {
            return;
        }

        String guildId = guild.getId();
        AudioPipeline pipeline = pipelines.get(guildId);
        if (pipeline == null) {
            return;
        }

        AudioSendHandler handler = pipeline.getSendHandler(plugin);
        if (handler != null) {
            pipeline.deregisterSendHandler(plugin);

            // Émission de l'événement
            AudioSendHandlerRemovedEvent event = new AudioSendHandlerRemovedEvent(guild, plugin, handler);
            eventManager.fireEvent(event);

            logger.debug("Deregistered audio send handler for plugin {} in guild {}", plugin.getName(), guild.getName());

            // Nettoyage du pipeline si vide
            cleanupPipeline(guild, pipeline);
        }

        // Mise à jour du tracking des guildes par plugin
        Set<String> pluginGuildIds = pluginGuilds.get(plugin.getName());
        if (pluginGuildIds != null) {
            pluginGuildIds.remove(guildId);
            if (pluginGuildIds.isEmpty()) {
                pluginGuilds.remove(plugin.getName());
            }
        }
    }

    @Override
    public void setVolume(Guild guild, Plugin plugin, int volume) {
        if (guild == null || plugin == null) {
            return;
        }

        if (volume < MIN_VOLUME || volume > MAX_VOLUME) {
            throw new IllegalArgumentException("Volume must be between " + MIN_VOLUME + " and " + MAX_VOLUME);
        }

        AudioPipeline pipeline = pipelines.get(guild.getId());
        if (pipeline != null) {
            pipeline.setVolume(plugin, volume);
        }
    }

    @Override
    public void registerReceiveHandler(Guild guild, Plugin plugin, AudioReceiveHandler handler) {
        if (guild == null || plugin == null || handler == null) {
            throw new IllegalArgumentException("Guild, plugin and handler cannot be null");
        }

        String guildId = guild.getId();
        AudioPipeline pipeline = getOrCreatePipeline(guild);

        // Enregistrement du handler
        pipeline.registerReceiveHandler(plugin, handler);

        // Tracking des guildes par plugin
        pluginGuilds.computeIfAbsent(plugin.getName(), k -> ConcurrentHashMap.newKeySet()).add(guildId);

        // Émission de l'événement
        AudioReceiveHandlerRegisteredEvent event = new AudioReceiveHandlerRegisteredEvent(guild, plugin, handler);
        eventManager.fireEvent(event);

        logger.debug("Registered audio receive handler for plugin {} in guild {}", plugin.getName(), guild.getName());
    }

    @Override
    public void deregisterReceiveHandler(Guild guild, Plugin plugin) {
        if (guild == null || plugin == null) {
            return;
        }

        String guildId = guild.getId();
        AudioPipeline pipeline = pipelines.get(guildId);
        if (pipeline == null) {
            return;
        }

        AudioReceiveHandler handler = pipeline.getReceiveHandler(plugin);
        if (handler != null) {
            pipeline.deregisterReceiveHandler(plugin);

            // Émission de l'événement
            AudioReceiveHandlerRemovedEvent event = new AudioReceiveHandlerRemovedEvent(guild, plugin, handler);
            eventManager.fireEvent(event);

            logger.debug("Deregistered audio receive handler for plugin {} in guild {}", plugin.getName(), guild.getName());

            // Nettoyage du pipeline si vide
            cleanupPipeline(guild, pipeline);
        }

        // Mise à jour du tracking des guildes par plugin
        Set<String> pluginGuildIds = pluginGuilds.get(plugin.getName());
        if (pluginGuildIds != null) {
            pluginGuildIds.remove(guildId);
            if (pluginGuildIds.isEmpty()) {
                pluginGuilds.remove(plugin.getName());
            }
        }
    }

    @Override
    public void setPriorityThreshold(Guild guild, int threshold) {
        if (guild == null) {
            return;
        }

        if (threshold < MIN_PRIORITY || threshold > MAX_PRIORITY) {
            throw new IllegalArgumentException("Threshold must be between " + MIN_PRIORITY + " and " + MAX_PRIORITY);
        }

        AudioPipeline pipeline = pipelines.get(guild.getId());
        if (pipeline != null) {
            pipeline.setPriorityThreshold(threshold);
        }
    }

    @Override
    public boolean hasActiveSendHandler(Guild guild, Plugin plugin) {
        if (guild == null || plugin == null) {
            return false;
        }

        AudioPipeline pipeline = pipelines.get(guild.getId());
        return pipeline != null && pipeline.hasSendHandler(plugin);
    }

    @Override
    public boolean hasActiveReceiveHandler(Guild guild, Plugin plugin) {
        if (guild == null || plugin == null) {
            return false;
        }

        AudioPipeline pipeline = pipelines.get(guild.getId());
        return pipeline != null && pipeline.hasReceiveHandler(plugin);
    }

    @Override
    public void closeAudioConnection(Guild guild) {
        if (guild == null) {
            return;
        }

        String guildId = guild.getId();
        AudioPipeline pipeline = pipelines.remove(guildId);
        if (pipeline != null) {
            pipeline.close();
            logger.debug("Closed audio connection for guild {}", guild.getName());

            // Mise à jour du tracking des guildes par plugin
            for (Map.Entry<String, Set<String>> entry : pluginGuilds.entrySet()) {
                entry.getValue().remove(guildId);
                if (entry.getValue().isEmpty()) {
                    pluginGuilds.remove(entry.getKey());
                }
            }
        }
    }

    @Override
    public void closeAllConnectionsForPlugin(Plugin plugin) {
        if (plugin == null) {
            return;
        }

        Set<String> guildIds = pluginGuilds.remove(plugin.getName());
        if (guildIds != null) {
            for (String guildId : guildIds) {
                AudioPipeline pipeline = pipelines.get(guildId);
                if (pipeline != null) {
                    pipeline.deregisterSendHandler(plugin);
                    pipeline.deregisterReceiveHandler(plugin);

                    // Nettoyage du pipeline si vide
                    if (pipeline.isEmpty()) {
                        pipeline.close();
                        pipelines.remove(guildId);
                    }
                }
            }

            logger.debug("Closed all audio connections for plugin {}", plugin.getName());
        }
    }

    /**
     * Obtient le pipeline audio pour une guilde ou en crée un nouveau si nécessaire.
     *
     * @param guild la guilde
     * @return le pipeline audio
     */
    private AudioPipeline getOrCreatePipeline(Guild guild) {
        return pipelines.computeIfAbsent(guild.getId(), k -> new AudioPipeline(guild, eventManager, settings));
    }

    /**
     * Nettoie un pipeline audio si nécessaire.
     *
     * @param guild    la guilde
     * @param pipeline le pipeline audio
     */
    private void cleanupPipeline(Guild guild, AudioPipeline pipeline) {
        if (pipeline.isEmpty()) {
            pipeline.close();
            pipelines.remove(guild.getId());
            logger.debug("Removed empty audio pipeline for guild {}", guild.getName());
        }
    }
}