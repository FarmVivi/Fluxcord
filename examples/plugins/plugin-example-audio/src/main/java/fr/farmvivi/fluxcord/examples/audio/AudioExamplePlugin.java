package fr.farmvivi.fluxcord.examples.audio;

import fr.farmvivi.fluxcord.api.audio.events.AudioFrameMixedEvent;
import fr.farmvivi.fluxcord.api.event.EventHandler;
import fr.farmvivi.fluxcord.api.event.EventPriority;
import fr.farmvivi.fluxcord.api.plugin.AbstractPlugin;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.CombinedAudio;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Exemple de plugin audio qui montre comment utiliser le système audio.
 * Ce plugin joue un fichier audio quand un utilisateur rejoint un salon vocal
 * et enregistre l'audio reçu dans un fichier.
 */
public class AudioExamplePlugin extends AbstractPlugin {
    private final Map<String, MySendHandler> sendHandlers = new HashMap<>();
    private final Map<String, MyReceiveHandler> receiveHandlers = new HashMap<>();

    private ListenerAdapter jdaListener;

    private boolean autoJoinEnabled;
    private boolean autoLeaveEnabled;
    private int autoLeaveTimeout;
    private int defaultVolume;
    private String recordingFormat;
    private int maxRecordingDuration;

    @Override
    public void onEnable() {
        // Load configuration settings
        loadConfiguration();

        // Log startup message
        logger.info("Audio Example Plugin enabled!");

        // Create necessary directories
        createDirectories();

        // Register JDA listener for voice updates
        jdaListener = new ListenerAdapter() {
            @Override
            public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
                // Delegate to existing handlers
                onGuildVoiceJoin(event);
                onGuildVoiceLeave(event);
            }
        };
        // Works before and after the Discord connection; removed by the core when the plugin is disabled
        addDiscordListeners(jdaListener);
        logger.debug("Registered JDA voice listener for AudioExamplePlugin");

        // Fluxcord events (@EventHandler methods of this class, e.g. AudioFrameMixedEvent) need an explicit registration
        eventManager.registerListener(this, this);
    }

    @Override
    public void onDisable() {
        // Le service AudioService gère automatiquement le nettoyage des connexions audio
        // lorsqu'un plugin est désactivé, mais on peut aussi le faire manuellement ici

        // Ferme manuellement les handlers d'envoi
        for (MySendHandler handler : sendHandlers.values()) {
            handler.cleanup();
        }
        sendHandlers.clear();

        // Ferme manuellement les handlers de réception
        for (MyReceiveHandler handler : receiveHandlers.values()) {
            handler.cleanup();
        }
        receiveHandlers.clear();

        // Unregister JDA listener
        jdaListener = null; // the core removed it from JDA when disabling the plugin

        logger.info("Audio Example Plugin disabled!");
    }

    /**
     * Load configuration values with defaults
     */
    private void loadConfiguration() {
        // Read new key first, fallback to legacy key; default enabled
        autoJoinEnabled = getConfiguration().getBoolean(
                "voice.auto_join",
                getConfiguration().getBoolean("audio.auto_join", true)
        );
        autoLeaveEnabled = getConfiguration().getBoolean("voice.auto_leave", true);
        autoLeaveTimeout = getConfiguration().getInt("voice.auto_leave_timeout", 30);
        defaultVolume = getConfiguration().getInt("audio.default_volume", 50);
        recordingFormat = getConfiguration().getString("audio.recording_format", "wav");
        maxRecordingDuration = getConfiguration().getInt("audio.max_recording_duration", 300);
        // Persist defaults if not present
        getConfiguration().set("voice.auto_join", autoJoinEnabled);
        getConfiguration().set("voice.auto_leave", autoLeaveEnabled);
        getConfiguration().set("voice.auto_leave_timeout", autoLeaveTimeout);
        getConfiguration().set("audio.default_volume", defaultVolume);
        getConfiguration().set("audio.recording_format", recordingFormat);
        getConfiguration().set("audio.max_recording_duration", maxRecordingDuration);

        logger.info("Loaded configuration - Auto Join: {}, Auto Leave: {}, Volume: {}",
                autoJoinEnabled, autoLeaveEnabled, defaultVolume);
    }

    /**
     * Create necessary directories from configuration
     */
    private void createDirectories() {
        String recordingsDir = getConfiguration().getString("paths.recordings_dir", "recordings/");
        String samplesDir = getConfiguration().getString("paths.samples_dir", "samples/");
        File recordings = new File(getDataFolder(), recordingsDir);
        File samples = new File(getDataFolder(), samplesDir);

        // Persist paths
        getConfiguration().set("paths.recordings_dir", recordingsDir);
        getConfiguration().set("paths.samples_dir", samplesDir);

        if (!recordings.exists() && !recordings.mkdirs()) {
            logger.warn("Failed to create recordings directory: {}", recordings.getPath());
        }

        if (!samples.exists() && !samples.mkdirs()) {
            logger.warn("Failed to create samples directory: {}", samples.getPath());
        }
    }

    /**
     * Gère l'événement lorsqu'un utilisateur rejoint un salon vocal.
     */
    // Called by the JDA listener above (Discord events are not dispatched through @EventHandler)
    public void onGuildVoiceJoin(GuildVoiceUpdateEvent event) {
        // Only auto-join if enabled in configuration
        if (!autoJoinEnabled) return;

        // Vérifie si l'utilisateur a rejoint un salon vocal (channelJoined != null && channelLeft == null)
        if (event.getChannelJoined() == null) return;

        Guild guild = event.getGuild();
        String guildId = guild.getId();

        // Si on n'a pas déjà un handler pour cette guilde, on en crée un
        if (!sendHandlers.containsKey(guildId)) {
            // Use configured paths (hardcoded for now)
            // TODO: String samplesDir = getConfiguration().getString("paths.samples_dir", "samples/");
            // TODO: String recordingsDir = getConfiguration().getString("paths.recordings_dir", "recordings/");

            // Crée le handler d'envoi audio
            String samplesDir = getConfiguration().getString("paths.samples_dir", "samples/");
            File audioFile = new File(getDataFolder(), samplesDir + "/welcome.wav");
            MySendHandler sendHandler = new MySendHandler(audioFile);

            // Crée le handler de réception audio with configured format
            String recordingsDir = getConfiguration().getString("paths.recordings_dir", "recordings/");
            File outputDir = new File(getDataFolder(), recordingsDir);
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            String filename = "recording_" + guildId + "." + recordingFormat;
            MyReceiveHandler receiveHandler = new MyReceiveHandler(new File(outputDir, filename));

            // Connecte au salon vocal
            VoiceChannel voiceChannel = event.getChannelJoined().asVoiceChannel();
            guild.getAudioManager().openAudioConnection(voiceChannel);

            // Enregistre les handlers avec le service audio using configured volume
            getContext().getAudioService().registerSendHandler(guild, this, sendHandler, defaultVolume, 60);
            getContext().getAudioService().registerReceiveHandler(guild, this, receiveHandler);

            // Stocke les handlers pour plus tard
            sendHandlers.put(guildId, sendHandler);
            receiveHandlers.put(guildId, receiveHandler);

            // Log activity
            logger.info("Started audio playback and recording in voice channel: " + voiceChannel.getName());

            logger.info("Connecté au salon vocal {} dans la guilde {}", voiceChannel.getName(), guild.getName());
        }
    }

    /**
     * Gère l'événement lorsqu'un utilisateur quitte un salon vocal.
     */
    public void onGuildVoiceLeave(GuildVoiceUpdateEvent event) {
        // Vérifie si l'utilisateur a quitté un salon vocal (channelLeft != null)
        if (event.getChannelLeft() == null) return;

        Guild guild = event.getGuild();
        String guildId = guild.getId();

        // Si tous les utilisateurs sont partis (sauf le bot), on ferme la connexion
        // Vérifie si le salon vocal est maintenant vide (à part le bot)
        if (event.getChannelLeft().getMembers().size() <= 1) {
            // Désenregistre les handlers
            if (sendHandlers.containsKey(guildId)) {
                getContext().getAudioService().deregisterSendHandler(guild, this);
                MySendHandler handler = sendHandlers.remove(guildId);
                handler.cleanup();
            }

            if (receiveHandlers.containsKey(guildId)) {
                getContext().getAudioService().deregisterReceiveHandler(guild, this);
                MyReceiveHandler handler = receiveHandlers.remove(guildId);
                handler.cleanup();
            }

            // Ferme la connexion audio
            guild.getAudioManager().closeAudioConnection();
            logger.info("Déconnecté du salon vocal dans la guilde {}", guild.getName());
        }
    }

    /**
     * Gère l'événement lorsqu'un frame audio est mixé.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAudioFrameMixed(AudioFrameMixedEvent event) {
        // On peut utiliser cet événement pour suivre l'activité audio
        // Par exemple, compter le nombre de sources actives ou surveiller les performances

        // Affiche les informations toutes les 100 frames (environ 2 secondes)
        // pour éviter de spammer les logs
        if (Math.random() < 0.01) {
            logger.debug("Frame audio mixé dans la guilde {}: {} sources actives, mode bypass: {}, contient de l'audio: {}",
                    event.getGuild().getName(),
                    event.getActiveSourceCount(),
                    event.isBypassMode() ? "oui" : "non",
                    event.containsAudio() ? "oui" : "non");
        }
    }

    /**
     * Exemple d'un handler d'envoi audio qui lit un fichier audio.
     */
    private static class MySendHandler implements AudioSendHandler {
        private static final Logger LOG = LoggerFactory.getLogger(MySendHandler.class);
        private static final int FRAME_SIZE = 3840; // 20ms @ 48kHz, 2 canaux, 16 bits
        private final byte[] frameBuffer = new byte[FRAME_SIZE];
        private AudioInputStream pcmStream;
        private ByteBuffer lastBuffer;
        private boolean done = false;

        public MySendHandler(File audioFile) {
            try {
                AudioInputStream source = AudioSystem.getAudioInputStream(audioFile);
                AudioFormat target = new AudioFormat(48000f, 16, 2, true, false);
                this.pcmStream = AudioSystem.getAudioInputStream(target, source);
                LOG.info("Opened '{}' -> converted to {} Hz, {}-bit, {} channels, little-endian",
                        audioFile.getName(), (int) target.getSampleRate(), target.getSampleSizeInBits(), target.getChannels());
            } catch (UnsupportedAudioFileException | IOException e) {
                LOG.error("Failed to open audio file for playback: {}", audioFile.getAbsolutePath(), e);
                done = true;
            }
        }

        private static int readFully(java.io.InputStream in, byte[] b, int off, int len) throws IOException {
            int total = 0;
            while (total < len) {
                int r = in.read(b, off + total, len - total);
                if (r < 0) break;
                total += r;
            }
            return total == 0 ? -1 : total;
        }

        @Override
        public boolean canProvide() {
            if (done || pcmStream == null) return false;
            try {
                int read = readFully(pcmStream, frameBuffer, 0, FRAME_SIZE);
                if (read < 0) {
                    done = true;
                    return false;
                }
                if (read < FRAME_SIZE) {
                    for (int i = read; i < FRAME_SIZE; i++) frameBuffer[i] = 0;
                    done = true;
                }
                // clone since we reuse the array, and ensure little-endian order
                lastBuffer = ByteBuffer.wrap(frameBuffer.clone()).order(ByteOrder.LITTLE_ENDIAN);
                return true;
            } catch (IOException e) {
                LOG.error("I/O error while reading audio data", e);
                done = true;
                return false;
            }
        }

        @Override
        public ByteBuffer provide20MsAudio() {
            ByteBuffer buf = lastBuffer;
            lastBuffer = null;
            return buf;
        }

        @Override
        public boolean isOpus() {
            // Notre exemple utilise du PCM, pas de l'Opus
            return false;
        }

        public void cleanup() {
            try {
                if (pcmStream != null) {
                    pcmStream.close();
                }
            } catch (IOException e) {
                LOG.warn("Error while closing audio stream", e);
            }
        }
    }

    /**
     * Exemple d'un handler de réception audio qui enregistre l'audio dans un fichier.
     */
    private static class MyReceiveHandler implements AudioReceiveHandler {
        private static final Logger LOG = LoggerFactory.getLogger(MyReceiveHandler.class);
        private static final int SAMPLE_RATE = 48000;
        private static final int CHANNELS = 2;
        private static final int BITS_PER_SAMPLE = 16;

        private final File outputFile;
        private RandomAccessFile raf;
        private long dataSize = 0;

        public MyReceiveHandler(File outputFile) {
            this.outputFile = outputFile;
            try {
                File parent = outputFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                this.raf = new RandomAccessFile(outputFile, "rw");
                this.raf.setLength(0);
                writeWavHeader(raf, 0, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE);
                LOG.info("Démarrage de l'enregistrement dans {}", outputFile.getAbsolutePath());
            } catch (IOException e) {
                LOG.error("Impossible de démarrer l'enregistrement dans {}", outputFile.getAbsolutePath(), e);
                this.raf = null;
            }
        }

        private static void writeWavHeader(RandomAccessFile raf, long dataSize, int sampleRate, int channels, int bitsPerSample) throws IOException {
            int byteRate = sampleRate * channels * bitsPerSample / 8;
            int blockAlign = channels * bitsPerSample / 8;

            raf.seek(0);
            // RIFF header
            raf.writeBytes("RIFF");
            writeLEInt(raf, (int) (36 + dataSize));
            raf.writeBytes("WAVE");
            // fmt chunk
            raf.writeBytes("fmt ");
            writeLEInt(raf, 16); // PCM
            writeLEShort(raf, (short) 1); // Audio format = PCM
            writeLEShort(raf, (short) channels);
            writeLEInt(raf, sampleRate);
            writeLEInt(raf, byteRate);
            writeLEShort(raf, (short) blockAlign);
            writeLEShort(raf, (short) bitsPerSample);
            // data chunk
            raf.writeBytes("data");
            writeLEInt(raf, (int) dataSize);
        }

        private static void finalizeWavHeader(RandomAccessFile raf, long dataSize) throws IOException {
            raf.seek(4);
            writeLEInt(raf, (int) (36 + dataSize));
            raf.seek(40);
            writeLEInt(raf, (int) dataSize);
        }

        private static void writeLEInt(RandomAccessFile raf, int value) throws IOException {
            raf.writeByte(value & 0xFF);
            raf.writeByte((value >> 8) & 0xFF);
            raf.writeByte((value >> 16) & 0xFF);
            raf.writeByte((value >> 24) & 0xFF);
        }

        private static void writeLEShort(RandomAccessFile raf, short value) throws IOException {
            raf.writeByte(value & 0xFF);
            raf.writeByte((value >> 8) & 0xFF);
        }

        @Override
        public boolean canReceiveCombined() {
            return raf != null;
        }

        @Override
        public boolean canReceiveUser() {
            return false;
        }

        @Override
        public void handleCombinedAudio(CombinedAudio combinedAudio) {
            if (raf == null) return;
            try {
                byte[] be = combinedAudio.getAudioData(1.0); // BigEndian PCM per JDA docs
                // Convertit en little-endian pour WAV
                for (int i = 0; i + 1 < be.length; i += 2) {
                    byte hi = be[i];
                    be[i] = be[i + 1];
                    be[i + 1] = hi;
                }
                raf.write(be);
                dataSize += be.length;
            } catch (IOException e) {
                LOG.error("Erreur d'écriture des données audio dans {}", outputFile.getAbsolutePath(), e);
            }
        }

        public void cleanup() {
            if (raf == null) return;
            try {
                finalizeWavHeader(raf, dataSize);
                raf.close();
                LOG.info("Arrêt de l'enregistrement dans {}", outputFile.getAbsolutePath());
            } catch (IOException e) {
                LOG.warn("Erreur lors de la fermeture de l'enregistrement {}", outputFile.getAbsolutePath(), e);
            }
        }
    }
}
