package fr.farmvivi.fluxcord.api.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What JDA sees every 20 ms. The contract is unforgiving: frames must be exactly
 * {@link PcmAudio#DISCORD_FRAME_SIZE} bytes, and {@code canProvide} returning true must be followed by a
 * frame.
 */
class PcmSendHandlerTest {

    private final PcmSendHandler handler = new PcmSendHandler();

    /** One clip of 48 kHz stereo audio whose every sample is {@code value}. */
    private static PcmAudio clip(int frames, short value) {
        ByteBuffer buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames * 2; i++) {
            buffer.putShort(value);
        }
        return new PcmAudio(buffer.array(), PcmAudio.DISCORD_SAMPLE_RATE, PcmAudio.DISCORD_CHANNELS);
    }

    @Test
    void anIdleHandlerProvidesNothing() {
        assertTrue(handler.isIdle());
        assertFalse(handler.canProvide());
        assertFalse(handler.isOpus(), "PCM, so the core can mix and duck it");
    }

    @Test
    void aClipIsHandedOutInTwentyMillisecondFrames() {
        handler.enqueue(clip(960 * 2, (short) 1)); // exactly two frames

        assertTrue(handler.canProvide());
        assertEquals(PcmAudio.DISCORD_FRAME_SIZE, handler.provide20MsAudio().remaining());
        assertTrue(handler.canProvide());
        assertEquals(PcmAudio.DISCORD_FRAME_SIZE, handler.provide20MsAudio().remaining());

        assertFalse(handler.canProvide());
        assertTrue(handler.isIdle());
    }

    @Test
    void theTailOfAClipIsPaddedSoEveryFrameIsFullSize() {
        handler.enqueue(clip(10, (short) 0x0101)); // far less than one frame

        assertTrue(handler.canProvide());
        ByteBuffer frame = handler.provide20MsAudio();
        assertEquals(PcmAudio.DISCORD_FRAME_SIZE, frame.remaining());
        assertEquals(0x0101, frame.order(ByteOrder.LITTLE_ENDIAN).getShort(0));
        assertEquals(0, frame.getShort(PcmAudio.DISCORD_FRAME_SIZE - 2), "padded with silence");
        assertFalse(handler.canProvide());
    }

    @Test
    void twoClipsQueuedInARowAreBothPlayed() {
        handler.enqueue(clip(960, (short) 1));
        handler.enqueue(clip(960, (short) 2));
        assertEquals(2, handler.queuedClips(), "nothing is playing yet, both are waiting");

        assertTrue(handler.canProvide());
        assertEquals(1, handler.queuedClips(), "the first one is now playing");
        assertEquals(1, handler.provide20MsAudio().order(ByteOrder.LITTLE_ENDIAN).getShort(0));
        assertTrue(handler.canProvide());
        assertEquals(2, handler.provide20MsAudio().order(ByteOrder.LITTLE_ENDIAN).getShort(0));
        assertFalse(handler.canProvide());
    }

    @Test
    void audioAtAnotherRateIsConvertedOnTheWayIn() {
        // A provider answering 24 kHz mono must not play at half speed in one ear.
        handler.enqueue(new PcmAudio(new byte[24_000 * 2], 24_000, 1)); // one second

        int frames = 0;
        while (handler.canProvide()) {
            handler.provide20MsAudio();
            frames++;
        }
        assertEquals(50, frames, "one second is fifty 20 ms frames");
    }

    @Test
    void clearingDropsWhatWasPlayingAndWhatWasWaiting() {
        handler.enqueue(clip(960, (short) 1));
        handler.enqueue(clip(960, (short) 2));
        handler.canProvide();

        handler.clear();

        assertTrue(handler.isIdle());
        assertFalse(handler.canProvide());
        assertEquals(0, handler.queuedClips());
    }

    @Test
    void emptyAudioIsNotQueuedAtAll() {
        handler.enqueue(new PcmAudio(new byte[0], 24_000, 1));

        assertTrue(handler.isIdle());
        assertEquals(0, handler.queuedClips());
    }

    @Test
    void enqueueingNothingIsAProgrammingErrorAndSaysSo() {
        assertThrows(NullPointerException.class, () -> handler.enqueue(null));
    }
}
