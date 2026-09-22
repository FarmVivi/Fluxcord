package fr.farmvivi.fluxcord.examples.audio;

import fr.farmvivi.fluxcord.api.audio.events.AudioFrameMixedEvent;
import fr.farmvivi.fluxcord.api.event.EventHandler;
import fr.farmvivi.fluxcord.api.event.EventPriority;
import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Example plugin showing how to use the audio system: it joins the voice channel someone enters,
 * plays a sample file and records what it hears, then leaves once the channel is empty.
 *
 * <p>What it demonstrates:
 * <ul>
 *   <li>registering a send and a receive handler on {@code AudioService} with a volume and a
 *       priority (60 here, so a higher-priority plugin can duck this one);</li>
 *   <li>the two event buses: Discord events need a JDA {@link ListenerAdapter}, Fluxcord events
 *       (such as {@link AudioFrameMixedEvent}) use {@code @EventHandler};</li>
 *   <li>producing PCM frames ({@link WavFileSendHandler}) and consuming mixed audio
 *       ({@link WavRecordingReceiveHandler}).</li>
 * </ul>
 */
public class AudioExamplePlugin extends AbstractPlugin {
    /** Priority of this source in the guild mix; a plugin above this one fades it out. */
    private static final int SEND_PRIORITY = 60;
    /** One frame every 20 ms, so this logs roughly every 20 seconds. */
    private static final long FRAME_LOG_INTERVAL = 1000;

    private final Map<String, WavFileSendHandler> sendHandlers = new HashMap<>();
    private final Map<String, WavRecordingReceiveHandler> receiveHandlers = new HashMap<>();
    private final AtomicLong mixedFrames = new AtomicLong();

    private boolean autoJoinEnabled;
    private boolean autoLeaveEnabled;
    private int defaultVolume;
    private String recordingFormat;

    @Override
    public void onEnable() {
        loadConfiguration();
        createDirectories();

        // Discord events: a JDA listener. The core registers it on the builder or on the live JDA,
        // and removes it when the plugin is disabled.
        addDiscordListeners(new ListenerAdapter() {
            @Override
            public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
                onSomeoneJoined(event);
                onSomeoneLeft(event);
            }
        });

        // Fluxcord events (@EventHandler methods of this class) need an explicit registration.
        eventManager.registerListener(this, this);

        logger.info("Audio Example Plugin enabled (auto-join: {}, auto-leave: {}, volume: {})",
                autoJoinEnabled, autoLeaveEnabled, defaultVolume);
    }

    @Override
    public void onDisable() {
        // AudioService already releases the handlers of a disabled plugin; closing the files is
        // this plugin's own business.
        sendHandlers.values().forEach(WavFileSendHandler::cleanup);
        sendHandlers.clear();
        receiveHandlers.values().forEach(WavRecordingReceiveHandler::cleanup);
        receiveHandlers.clear();

        logger.info("Audio Example Plugin disabled!");
    }

    private void loadConfiguration() {
        autoJoinEnabled = getConfiguration().getBoolean("voice.auto_join", true);
        autoLeaveEnabled = getConfiguration().getBoolean("voice.auto_leave", true);
        defaultVolume = getConfiguration().getInt("audio.default_volume", 50);
        recordingFormat = getConfiguration().getString("audio.recording_format", "wav");
    }

    private void createDirectories() {
        for (File directory : new File[]{recordingsDirectory(), samplesDirectory()}) {
            if (!directory.exists() && !directory.mkdirs()) {
                logger.warn("Failed to create directory: {}", directory.getPath());
            }
        }
    }

    private File recordingsDirectory() {
        return new File(getDataFolder(), getConfiguration().getString("paths.recordings_dir", "recordings/"));
    }

    private File samplesDirectory() {
        return new File(getDataFolder(), getConfiguration().getString("paths.samples_dir", "samples/"));
    }

    /**
     * Joins the channel and starts playing and recording when someone enters it.
     *
     * <p>Called from the JDA listener above: Discord events never reach {@code @EventHandler}.
     */
    void onSomeoneJoined(GuildVoiceUpdateEvent event) {
        if (!autoJoinEnabled || event.getChannelJoined() == null) {
            return;
        }

        Guild guild = event.getGuild();
        String guildId = guild.getId();
        if (sendHandlers.containsKey(guildId)) {
            return; // already playing in this guild
        }

        WavFileSendHandler sendHandler = new WavFileSendHandler(new File(samplesDirectory(), "welcome.wav"));
        WavRecordingReceiveHandler receiveHandler = new WavRecordingReceiveHandler(
                new File(recordingsDirectory(), "recording_" + guildId + "." + recordingFormat));

        AudioChannel channel = event.getChannelJoined();
        guild.getAudioManager().openAudioConnection(channel);
        getContext().getAudioService().registerSendHandler(guild, this, sendHandler, defaultVolume, SEND_PRIORITY);
        getContext().getAudioService().registerReceiveHandler(guild, this, receiveHandler);

        sendHandlers.put(guildId, sendHandler);
        receiveHandlers.put(guildId, receiveHandler);

        logger.info("Playing and recording in '{}' ({})", channel.getName(), guild.getName());
    }

    /**
     * Leaves the channel once the bot is the only one left in it.
     */
    void onSomeoneLeft(GuildVoiceUpdateEvent event) {
        if (!autoLeaveEnabled || event.getChannelLeft() == null) {
            return;
        }
        // The bot itself is still in the channel, hence "1".
        if (event.getChannelLeft().getMembers().size() > 1) {
            return;
        }

        Guild guild = event.getGuild();
        String guildId = guild.getId();

        WavFileSendHandler sendHandler = sendHandlers.remove(guildId);
        if (sendHandler != null) {
            getContext().getAudioService().deregisterSendHandler(guild, this);
            sendHandler.cleanup();
        }
        WavRecordingReceiveHandler receiveHandler = receiveHandlers.remove(guildId);
        if (receiveHandler != null) {
            getContext().getAudioService().deregisterReceiveHandler(guild, this);
            receiveHandler.cleanup(); // finishes the WAV header, otherwise the file is unplayable
        }

        guild.getAudioManager().closeAudioConnection();
        logger.info("Left the voice channel of '{}'", guild.getName());
    }

    /**
     * A Fluxcord event, dispatched by the internal bus once per mixed frame — every 20 ms per
     * guild, so anything done here must stay cheap.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAudioFrameMixed(AudioFrameMixedEvent event) {
        if (mixedFrames.incrementAndGet() % FRAME_LOG_INTERVAL == 0) {
            logger.debug("Mixed frame in '{}': {} active source(s), bypass: {}, audio: {}",
                    event.getGuild().getName(), event.getActiveSourceCount(),
                    event.isBypassMode(), event.containsAudio());
        }
    }
}
