package fr.farmvivi.fluxcord.plugins.aiaudio.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;

/**
 * A block of signed 16-bit little-endian PCM audio, with the format needed to interpret it.
 *
 * <p>This exists because the two ends of the plugin disagree on format: text-to-speech servers answer
 * at whatever rate their model runs at (24 kHz for OpenAI and Kokoro, 22.05 kHz for Piper), usually
 * mono, while Discord only accepts 48 kHz 16-bit stereo. {@link #toDiscordFormat()} is the one place
 * that bridges the two, so neither the HTTP clients nor the send handler carry format logic.
 *
 * @param samples    interleaved 16-bit little-endian samples
 * @param sampleRate frames per second
 * @param channels   1 for mono, 2 for stereo
 */
public record PcmAudio(byte[] samples, int sampleRate, int channels) {

    /** What Discord and the Fluxcord mixer work with. */
    public static final int DISCORD_SAMPLE_RATE = 48_000;
    /** Stereo. */
    public static final int DISCORD_CHANNELS = 2;
    /** Bytes in 20 ms of 48 kHz 16-bit stereo audio. */
    public static final int DISCORD_FRAME_SIZE = 3840;

    private static final int BYTES_PER_SAMPLE = 2;

    // Chunk identifiers as little-endian ints, which is how they read out of the buffer.
    private static final int RIFF = 0x46464952;
    private static final int WAVE = 0x45564157;
    private static final int FMT = 0x20746d66;
    private static final int DATA = 0x61746164;
    private static final int WAV_HEADER_SIZE = 44;
    private static final int PCM_FORMAT = 1;

    public PcmAudio {
        if (samples == null) {
            throw new IllegalArgumentException("samples is required");
        }
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive, got " + sampleRate);
        }
        if (channels != 1 && channels != 2) {
            throw new IllegalArgumentException("only mono and stereo are supported, got " + channels);
        }
    }

    /**
     * Reads a RIFF/WAVE container.
     *
     * <p>Only uncompressed 16-bit PCM is accepted, which is what every server answers when asked for
     * {@code wav}. The chunks are walked rather than assumed at fixed offsets: a {@code LIST} chunk
     * before {@code data} is common and would otherwise be read as audio — loud noise at the start of
     * the reply.
     *
     * @param wav the whole file
     * @return the audio it contains
     * @throws IllegalArgumentException if this is not a 16-bit PCM WAVE file
     */
    public static PcmAudio fromWav(byte[] wav) {
        if (wav == null || wav.length < WAV_HEADER_SIZE) {
            throw new IllegalArgumentException("not a WAV file: " + (wav == null ? "null" : wav.length + " bytes"));
        }
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != RIFF) {
            throw new IllegalArgumentException("not a WAV file: missing RIFF header");
        }
        buffer.getInt(); // total size, unreliable when the server streams its answer
        if (buffer.getInt() != WAVE) {
            throw new IllegalArgumentException("not a WAV file: missing WAVE marker");
        }

        int sampleRate = 0;
        int channels = 0;
        while (buffer.remaining() >= 8) {
            int chunkId = buffer.getInt();
            int chunkSize = buffer.getInt();
            if (chunkSize < 0) {
                throw new IllegalArgumentException("WAV chunk size overflows an int: " + chunkSize);
            }
            if (chunkId == FMT) {
                int format = Short.toUnsignedInt(buffer.getShort());
                channels = Short.toUnsignedInt(buffer.getShort());
                sampleRate = buffer.getInt();
                buffer.getInt();   // byte rate, derivable
                buffer.getShort(); // block align, derivable
                int bitsPerSample = Short.toUnsignedInt(buffer.getShort());
                if (format != PCM_FORMAT) {
                    throw new IllegalArgumentException("WAV is not uncompressed PCM (format " + format + ")");
                }
                if (bitsPerSample != 16) {
                    throw new IllegalArgumentException("WAV is not 16-bit (" + bitsPerSample + " bits)");
                }
                skip(buffer, chunkSize - 16);
            } else if (chunkId == DATA) {
                if (sampleRate == 0) {
                    throw new IllegalArgumentException("WAV data chunk comes before its fmt chunk");
                }
                // A streamed WAV can declare a size it never delivers; trust what is actually there.
                int available = Math.min(chunkSize, buffer.remaining());
                byte[] samples = new byte[available - (available % (BYTES_PER_SAMPLE * channels))];
                buffer.get(samples);
                return new PcmAudio(samples, sampleRate, channels);
            } else {
                skip(buffer, chunkSize);
            }
        }
        throw new IllegalArgumentException("WAV has no data chunk");
    }

    /**
     * Wraps audio received from JDA, which hands out big-endian samples
     * ({@link net.dv8tion.jda.api.audio.AudioReceiveHandler#OUTPUT_FORMAT} is 48 kHz 16-bit stereo,
     * big-endian) while everything here and in the Fluxcord mixer is little-endian.
     *
     * @param bigEndian  interleaved 16-bit big-endian samples
     * @param sampleRate frames per second
     * @param channels   1 or 2
     * @return the same audio, byte-swapped
     */
    public static PcmAudio fromBigEndian(byte[] bigEndian, int sampleRate, int channels) {
        byte[] swapped = new byte[bigEndian.length - (bigEndian.length % BYTES_PER_SAMPLE)];
        for (int i = 0; i + 1 < swapped.length; i += BYTES_PER_SAMPLE) {
            swapped[i] = bigEndian[i + 1];
            swapped[i + 1] = bigEndian[i];
        }
        return new PcmAudio(swapped, sampleRate, channels);
    }

    /** Chunks are word-aligned: an odd size is followed by one padding byte. */
    private static void skip(ByteBuffer buffer, int bytes) {
        int total = Math.max(0, Math.min(bytes + Math.abs(bytes % 2), buffer.remaining()));
        buffer.position(buffer.position() + total);
    }

    /**
     * Converts to 48 kHz stereo, the only format the voice pipeline accepts.
     *
     * @return this audio at 48 kHz stereo, or {@code this} when it already is
     */
    public PcmAudio toDiscordFormat() {
        return resample(DISCORD_SAMPLE_RATE, DISCORD_CHANNELS);
    }

    /**
     * Converts to another sample rate and channel count.
     *
     * <p>Resampling is linear interpolation between the two neighbouring input frames — inaudible on
     * speech at the ratios involved here, and cheap enough to run inline. Mono becomes both channels
     * rather than being panned left; stereo becomes mono by averaging, which avoids the clipping a plain
     * sum would cause.
     *
     * @param targetRate     the wanted sample rate
     * @param targetChannels 1 or 2
     * @return the converted audio, or {@code this} when it is already in that format
     */
    public PcmAudio resample(int targetRate, int targetChannels) {
        if (targetRate <= 0) {
            throw new IllegalArgumentException("targetRate must be positive, got " + targetRate);
        }
        if (targetChannels != 1 && targetChannels != 2) {
            throw new IllegalArgumentException("only mono and stereo are supported, got " + targetChannels);
        }
        if (sampleRate == targetRate && channels == targetChannels) {
            return this;
        }
        int inputFrames = samples.length / (BYTES_PER_SAMPLE * channels);
        if (inputFrames == 0) {
            return new PcmAudio(new byte[0], targetRate, targetChannels);
        }
        int outputFrames = (int) ((long) inputFrames * targetRate / sampleRate);
        ByteBuffer in = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer out = ByteBuffer.allocate(outputFrames * BYTES_PER_SAMPLE * targetChannels)
                .order(ByteOrder.LITTLE_ENDIAN);

        double step = (double) sampleRate / targetRate;
        for (int frame = 0; frame < outputFrames; frame++) {
            double source = frame * step;
            int index = (int) source;
            int next = Math.min(index + 1, inputFrames - 1);
            double fraction = source - index;
            if (targetChannels == 1) {
                out.putShort(interpolate(in, index, next, -1, fraction));
            } else {
                short left = interpolate(in, index, next, 0, fraction);
                out.putShort(left);
                out.putShort(channels == 1 ? left : interpolate(in, index, next, 1, fraction));
            }
        }
        return new PcmAudio(out.array(), targetRate, targetChannels);
    }

    /** @param channel the wanted channel, or -1 to average every channel into one */
    private short interpolate(ByteBuffer in, int frame, int nextFrame, int channel, double fraction) {
        double a = frameValue(in, frame, channel);
        double b = frameValue(in, nextFrame, channel);
        return (short) Math.round(a + (b - a) * fraction);
    }

    private double frameValue(ByteBuffer in, int frame, int channel) {
        if (channel >= 0) {
            return sampleAt(in, frame, Math.min(channel, channels - 1));
        }
        double sum = 0;
        for (int c = 0; c < channels; c++) {
            sum += sampleAt(in, frame, c);
        }
        return sum / channels;
    }

    private short sampleAt(ByteBuffer in, int frame, int channel) {
        return in.getShort((frame * channels + channel) * BYTES_PER_SAMPLE);
    }

    /**
     * Wraps these samples in a RIFF/WAVE container, which is what the transcription endpoints want:
     * they read the format from the file, not from the request.
     *
     * @return a complete WAV file holding this audio
     */
    public byte[] toWav() {
        int dataSize = samples.length;
        ByteBuffer wav = ByteBuffer.allocate(WAV_HEADER_SIZE + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        wav.putInt(RIFF);
        wav.putInt(WAV_HEADER_SIZE - 8 + dataSize);
        wav.putInt(WAVE);
        wav.putInt(FMT);
        wav.putInt(16); // size of the PCM fmt chunk
        wav.putShort((short) PCM_FORMAT);
        wav.putShort((short) channels);
        wav.putInt(sampleRate);
        wav.putInt(sampleRate * channels * BYTES_PER_SAMPLE);
        wav.putShort((short) (channels * BYTES_PER_SAMPLE));
        wav.putShort((short) (BYTES_PER_SAMPLE * 8));
        wav.putInt(DATA);
        wav.putInt(dataSize);
        wav.put(samples);
        return wav.array();
    }

    /** @return how long this audio lasts */
    public Duration duration() {
        long frames = samples.length / (long) (BYTES_PER_SAMPLE * channels);
        return Duration.ofNanos(frames * 1_000_000_000L / sampleRate);
    }

    /** @return true when there is no audio at all */
    public boolean isEmpty() {
        return samples.length == 0;
    }
}
