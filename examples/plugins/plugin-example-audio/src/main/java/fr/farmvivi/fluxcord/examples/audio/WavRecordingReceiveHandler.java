package fr.farmvivi.fluxcord.examples.audio;

import fr.farmvivi.fluxcord.api.audio.PcmAudio;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.CombinedAudio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Records what the bot hears in a voice channel into a playable WAV file.
 *
 * <p>JDA hands out <em>big-endian</em> PCM while a WAV file stores little-endian samples, so every
 * frame is byte-swapped on the way in. The header is written upfront with a zero length and fixed
 * once the recording stops ({@link #cleanup()}), which is why a recording that was never stopped
 * cleanly plays as an empty file.
 */
public class WavRecordingReceiveHandler implements AudioReceiveHandler {
    static final int SAMPLE_RATE = 48000;
    static final int CHANNELS = 2;
    static final int BITS_PER_SAMPLE = 16;
    /** RIFF + fmt + data headers, i.e. where the samples start. */
    static final int HEADER_SIZE = 44;

    private static final Logger LOG = LoggerFactory.getLogger(WavRecordingReceiveHandler.class);

    private final File outputFile;
    private RandomAccessFile output;
    private long dataSize;

    /**
     * @param outputFile the file to write; its parent directories are created if needed
     */
    public WavRecordingReceiveHandler(File outputFile) {
        this.outputFile = outputFile;
        try {
            File parent = outputFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                LOG.warn("Could not create {}", parent.getAbsolutePath());
            }
            this.output = new RandomAccessFile(outputFile, "rw");
            this.output.setLength(0);
            writeHeader(output, 0);
            LOG.info("Recording to {}", outputFile.getAbsolutePath());
        } catch (IOException e) {
            LOG.error("Cannot start the recording in {}", outputFile.getAbsolutePath(), e);
            this.output = null;
        }
    }

    @Override
    public boolean canReceiveCombined() {
        return output != null;
    }

    @Override
    public boolean canReceiveUser() {
        // One mixed stream is enough here; per-user streams would mean one file per speaker.
        return false;
    }

    @Override
    public void handleCombinedAudio(CombinedAudio combinedAudio) {
        write(combinedAudio.getAudioData(1.0));
    }

    /**
     * Appends one frame, byte-swapped into the little-endian layout a WAV file uses.
     *
     * <p>The swap itself lives in {@link PcmAudio#fromBigEndian}: JDA hands out big-endian samples while
     * everything else here is little-endian, and every plugin receiving audio needs the same conversion.
     */
    void write(byte[] bigEndianPcm) {
        if (output == null) {
            return;
        }
        byte[] littleEndian = PcmAudio.fromBigEndian(bigEndianPcm,
                PcmAudio.DISCORD_SAMPLE_RATE, PcmAudio.DISCORD_CHANNELS).samples();
        try {
            output.write(littleEndian);
            dataSize += littleEndian.length;
        } catch (IOException e) {
            LOG.error("Error writing audio data to {}", outputFile.getAbsolutePath(), e);
        }
    }

    /** Finishes the header with the real length and closes the file. */
    public void cleanup() {
        if (output == null) {
            return;
        }
        try {
            writeHeader(output, dataSize);
            output.close();
            LOG.info("Recording stopped: {} ({} bytes of audio)", outputFile.getAbsolutePath(), dataSize);
        } catch (IOException e) {
            LOG.warn("Error while closing the recording {}", outputFile.getAbsolutePath(), e);
        } finally {
            output = null;
        }
    }

    /** @return the number of audio bytes written so far, header excluded */
    long getDataSize() {
        return dataSize;
    }

    private static void writeHeader(RandomAccessFile file, long dataSize) throws IOException {
        int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;

        file.seek(0);
        file.writeBytes("RIFF");
        writeIntLE(file, (int) (36 + dataSize));
        file.writeBytes("WAVE");
        file.writeBytes("fmt ");
        writeIntLE(file, 16);                       // size of the fmt chunk
        writeShortLE(file, (short) 1);              // 1 = uncompressed PCM
        writeShortLE(file, (short) CHANNELS);
        writeIntLE(file, SAMPLE_RATE);
        writeIntLE(file, byteRate);
        writeShortLE(file, (short) blockAlign);
        writeShortLE(file, (short) BITS_PER_SAMPLE);
        file.writeBytes("data");
        writeIntLE(file, (int) dataSize);
        file.seek(HEADER_SIZE + dataSize);
    }

    private static void writeIntLE(RandomAccessFile file, int value) throws IOException {
        file.writeByte(value & 0xFF);
        file.writeByte((value >> 8) & 0xFF);
        file.writeByte((value >> 16) & 0xFF);
        file.writeByte((value >> 24) & 0xFF);
    }

    private static void writeShortLE(RandomAccessFile file, short value) throws IOException {
        file.writeByte(value & 0xFF);
        file.writeByte((value >> 8) & 0xFF);
    }
}
