package fr.farmvivi.fluxcord.core.audio;

import fr.farmvivi.fluxcord.api.audio.AudioService;
import fr.farmvivi.fluxcord.api.audio.events.AudioFrameMixedEvent;
import fr.farmvivi.fluxcord.api.audio.events.AudioVolumeChangedEvent;
import fr.farmvivi.fluxcord.api.event.EventManager;
import fr.farmvivi.fluxcord.api.plugin.Plugin;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.managers.AudioManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Send-side strategy of the per-guild pipeline, driven like JDA does: {@code canProvide()} then
 * {@code provide20MsAudio()} every frame. Sources hand PCM in little-endian (the documented internal
 * convention); the pipeline converts to big-endian at the JDA boundary.
 */
class AudioPipelineTest {

    private static final int FRAME_BYTES = 3840;
    private static final int FADE_FRAMES = 10; // 200 ms / 20 ms

    /** Scripted source: constant-amplitude PCM (little-endian) or an opaque "Opus" payload. */
    static class FakeSource implements AudioSendHandler {
        final boolean opus;
        short amplitude;
        boolean active = true;
        int canProvideCalls, provideCalls;

        FakeSource(boolean opus, short amplitude) {
            this.opus = opus;
            this.amplitude = amplitude;
        }

        @Override public boolean canProvide() { canProvideCalls++; return active; }
        @Override public boolean isOpus() { return opus; }

        @Override public ByteBuffer provide20MsAudio() {
            provideCalls++;
            if (opus) {
                return ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
            }
            ByteBuffer pcm = ByteBuffer.allocate(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            while (pcm.hasRemaining()) {
                pcm.putShort(amplitude);
            }
            return pcm.flip();
        }
    }

    private final EventManager events = mock(EventManager.class);
    private final Guild guild = mock(Guild.class);
    private final AudioManager audioManager = mock(AudioManager.class);
    private AudioPipeline pipeline;

    private static Plugin plugin(String name) {
        Plugin p = mock(Plugin.class);
        when(p.getName()).thenReturn(name);
        return p;
    }

    private final Plugin music = plugin("music");
    private final Plugin tts = plugin("tts");

    @BeforeEach
    void setUp() {
        when(guild.getName()).thenReturn("g");
        when(guild.getAudioManager()).thenReturn(audioManager);
        when(events.hasListeners(any())).thenReturn(true); // frame events are opt-in
        pipeline = new AudioPipeline(guild, events, new AudioSettings(200, 0)); // full mute: levels are easy to assert
    }

    @Test
    void frameEventIsNotBuiltWhenNobodyListens() {
        when(events.hasListeners(AudioFrameMixedEvent.class)).thenReturn(false);
        pipeline.registerSendHandler(music, new FakeSource(false, (short) 1), 100, 50);

        frame();

        verify(events, never()).fireEvent(any(AudioFrameMixedEvent.class));
    }

    /** First sample of the frame as JDA will read it (big-endian). */
    private static short firstSampleBigEndian(ByteBuffer frame) {
        return frame.duplicate().order(ByteOrder.BIG_ENDIAN).getShort(0);
    }

    private ByteBuffer frame() {
        assertTrue(pipeline.canProvide());
        return pipeline.provide20MsAudio();
    }

    // --- wiring ---------------------------------------------------------------------------------

    @Test
    void attachesToTheGuildAudioManagerAndDetachesOnClose() {
        verify(audioManager).setSendingHandler(pipeline);
        verify(audioManager).setReceivingHandler(pipeline);

        pipeline.registerSendHandler(music, new FakeSource(false, (short) 1), 100, 50);
        pipeline.close();

        verify(audioManager).setSendingHandler(null);
        verify(audioManager).setReceivingHandler(null);
        verify(audioManager).closeAudioConnection();
        assertTrue(pipeline.isEmpty());
        assertFalse(pipeline.canProvide());
    }

    @Test
    void nothingToProvideWithoutActiveSources() {
        assertFalse(pipeline.canProvide());

        FakeSource idle = new FakeSource(false, (short) 1);
        idle.active = false;
        pipeline.registerSendHandler(music, idle, 100, 50);

        assertFalse(pipeline.canProvide());
        assertEquals(1, idle.canProvideCalls);
        assertTrue(pipeline.hasSendHandler(music));
        assertSame(idle, pipeline.getSendHandler(music));
    }

    // --- bypass ---------------------------------------------------------------------------------

    @Test
    void singlePcmSourceIsBypassedAsBigEndian() {
        FakeSource source = new FakeSource(false, (short) 1000);
        pipeline.registerSendHandler(music, source, 100, 50);

        ByteBuffer out = frame();

        assertFalse(pipeline.isOpus());
        assertEquals(FRAME_BYTES, out.remaining());
        assertEquals(1000, firstSampleBigEndian(out));
        assertEquals(1, source.provideCalls);

        ArgumentCaptor<AudioFrameMixedEvent> captor = ArgumentCaptor.forClass(AudioFrameMixedEvent.class);
        verify(events).fireEvent(captor.capture());
        assertTrue(captor.getValue().isBypassMode());
        assertEquals(1, captor.getValue().getActiveSourceCount());
    }

    @Test
    void singleOpusSourceIsRelayedUntouched() {
        pipeline.registerSendHandler(music, new FakeSource(true, (short) 0), 100, 50);

        ByteBuffer out = frame();

        assertTrue(pipeline.isOpus());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, new byte[]{out.get(0), out.get(1), out.get(2), out.get(3)});
        assertEquals(4, out.remaining());
    }

    @Test
    void inactiveSourcesAreNotAskedForAudio() {
        FakeSource playing = new FakeSource(false, (short) 100);
        FakeSource silent = new FakeSource(false, (short) 100);
        silent.active = false;
        pipeline.registerSendHandler(music, playing, 100, 50);
        pipeline.registerSendHandler(tts, silent, 100, 50);

        assertEquals(100, firstSampleBigEndian(frame()), "single active PCM source: bypass");
        assertEquals(1, playing.provideCalls);
        assertEquals(0, silent.provideCalls);
    }

    // --- mixing ---------------------------------------------------------------------------------

    @Test
    void twoPcmSourcesAreMixedWithTheirVolumes() {
        pipeline.registerSendHandler(music, new FakeSource(false, (short) 1000), 100, 50);
        pipeline.registerSendHandler(tts, new FakeSource(false, (short) 1000), 50, 50);

        ByteBuffer out = frame();

        assertFalse(pipeline.isOpus());
        assertEquals(1500, firstSampleBigEndian(out));

        ArgumentCaptor<AudioFrameMixedEvent> captor = ArgumentCaptor.forClass(AudioFrameMixedEvent.class);
        verify(events).fireEvent(captor.capture());
        assertFalse(captor.getValue().isBypassMode());
        assertEquals(2, captor.getValue().getActiveSourceCount());
    }

    @Test
    void setVolumeAppliesToTheNextMixedFrameAndFiresAnEvent() {
        pipeline.registerSendHandler(music, new FakeSource(false, (short) 1000), 100, 50);
        pipeline.registerSendHandler(tts, new FakeSource(false, (short) 1000), 100, 50);
        assertEquals(2000, firstSampleBigEndian(frame()));

        pipeline.setVolume(tts, 20);
        assertEquals(1200, firstSampleBigEndian(frame()));

        ArgumentCaptor<AudioVolumeChangedEvent> captor = ArgumentCaptor.forClass(AudioVolumeChangedEvent.class);
        verify(events, atLeastOnce()).fireEvent(captor.capture());
        AudioVolumeChangedEvent event = captor.getAllValues().stream()
                .filter(AudioVolumeChangedEvent.class::isInstance).findFirst().orElseThrow();
        assertEquals(100, event.getOldVolume());
        assertEquals(20, event.getNewVolume());
        assertSame(tts, event.getPlugin());
    }

    @Test
    void mixingClipsToSixteenBits() {
        pipeline.registerSendHandler(music, new FakeSource(false, (short) 30000), 100, 50);
        pipeline.registerSendHandler(tts, new FakeSource(false, (short) 30000), 100, 50);

        assertEquals(Short.MAX_VALUE, firstSampleBigEndian(frame()));
    }

    @Test
    void pcmWinsOverOpusWhenBothAreActive() {
        // Characterization: one PCM + one Opus source → the PCM one is bypassed, the Opus one is dropped
        // for that frame (Opus cannot be mixed).
        FakeSource pcm = new FakeSource(false, (short) 100);
        FakeSource opus = new FakeSource(true, (short) 0);
        pipeline.registerSendHandler(music, opus, 100, 50);
        pipeline.registerSendHandler(tts, pcm, 100, 50);

        ByteBuffer out = frame();

        assertFalse(pipeline.isOpus());
        assertEquals(100, firstSampleBigEndian(out));
        assertEquals(0, opus.provideCalls);
    }

    @Test
    void twoOpusSourcesRelayTheHighPriorityOne() {
        FakeSource low = new FakeSource(true, (short) 0);
        FakeSource high = new FakeSource(true, (short) 0);
        pipeline.registerSendHandler(music, low, 100, 10);
        pipeline.registerSendHandler(tts, high, 100, AudioService.DEFAULT_PRIORITY_THRESHOLD);

        frame();

        assertTrue(pipeline.isOpus());
        assertEquals(1, high.provideCalls);
        assertEquals(0, low.provideCalls);
    }

    // --- priority fades -------------------------------------------------------------------------

    @Test
    void highPrioritySourceFadesTheOthersOutThenBackIn() {
        FakeSource musicSource = new FakeSource(false, (short) 1000);
        FakeSource announcement = new FakeSource(false, (short) 0); // silent, only its priority matters
        announcement.active = false;
        pipeline.registerSendHandler(music, musicSource, 100, 10);
        pipeline.registerSendHandler(tts, announcement, 100, AudioService.DEFAULT_PRIORITY_THRESHOLD);

        assertEquals(1000, firstSampleBigEndian(frame()), "alone: bypass, full level");

        announcement.active = true;
        short[] levels = new short[FADE_FRAMES + 1];
        for (int i = 0; i <= FADE_FRAMES; i++) {
            levels[i] = firstSampleBigEndian(frame());
        }
        assertTrue(levels[0] < 1000, "fade is audible from the first frame: " + levels[0]);
        for (int i = 1; i <= FADE_FRAMES; i++) {
            assertTrue(levels[i] <= levels[i - 1], "monotonic fade-out: " + levels[i - 1] + " -> " + levels[i]);
        }
        assertEquals(0, levels[FADE_FRAMES - 1], "fully ducked after 200 ms");

        // Announcement over: back alone (bypass) but the fade-in still ramps up over 200 ms.
        announcement.active = false;
        short[] ramp = new short[FADE_FRAMES];
        for (int i = 0; i < FADE_FRAMES; i++) {
            ramp[i] = firstSampleBigEndian(frame());
        }
        assertTrue(ramp[0] > 0 && ramp[0] < 1000, "fade-in starts on the first frame: " + ramp[0]);
        for (int i = 1; i < FADE_FRAMES; i++) {
            assertTrue(ramp[i] >= ramp[i - 1], "monotonic fade-in: " + ramp[i - 1] + " -> " + ramp[i]);
        }
        assertEquals(1000, ramp[FADE_FRAMES - 1], "back to full level after 200 ms");
        assertEquals(1000, firstSampleBigEndian(frame()));
    }

    @Test
    void singlePcmSourceVolumeIsAppliedInBypass() {
        FakeSource source = new FakeSource(false, (short) 1000);
        pipeline.registerSendHandler(music, source, 50, 50);

        assertEquals(500, firstSampleBigEndian(frame()));
        pipeline.setVolume(music, 100);
        assertEquals(1000, firstSampleBigEndian(frame()));
        pipeline.setVolume(music, 0);
        assertEquals(0, firstSampleBigEndian(frame()));
    }

    @Test
    void bypassGainClipsLikeTheMixer() {
        FakeSource source = new FakeSource(false, (short) -30000);
        pipeline.registerSendHandler(music, source, 100, 50);
        assertEquals(-30000, firstSampleBigEndian(frame()), "gain 1: pure byte swap, no clipping needed");

        // A fade multiplier cannot exceed 1 and volume is capped at 100, so clipping can only come
        // from rounding; check the sign path and the exact swap on a negative sample.
        pipeline.setVolume(music, 50);
        assertEquals(-15000, firstSampleBigEndian(frame()));
    }

    @Test
    void partialDuckingAndFadeDurationComeFromSettings() {
        // 40 ms fade (2 frames) down to 25 % instead of silence
        AudioPipeline configured = new AudioPipeline(guild, events, new AudioSettings(40, 25));
        FakeSource musicSource = new FakeSource(false, (short) 1000);
        FakeSource announcement = new FakeSource(false, (short) 0);
        configured.registerSendHandler(music, musicSource, 100, 10);
        configured.registerSendHandler(tts, announcement, 100, AudioService.DEFAULT_PRIORITY_THRESHOLD);

        assertTrue(configured.canProvide());
        short first = firstSampleBigEndian(configured.provide20MsAudio());
        assertTrue(configured.canProvide());
        short second = firstSampleBigEndian(configured.provide20MsAudio());
        assertTrue(configured.canProvide());
        short third = firstSampleBigEndian(configured.provide20MsAudio());

        assertTrue(first < 1000 && first > 250, "half way after one frame: " + first);
        assertEquals(250, second, "floor reached after 2 frames");
        assertEquals(250, third, "and held there");
    }

    @Test
    void thresholdIsConfigurablePerGuild() {
        FakeSource musicSource = new FakeSource(false, (short) 1000);
        FakeSource other = new FakeSource(false, (short) 0);
        pipeline.registerSendHandler(music, musicSource, 100, 10);
        pipeline.registerSendHandler(tts, other, 100, 30);

        assertEquals(1000, firstSampleBigEndian(frame()), "30 < default threshold: no ducking");

        pipeline.setPriorityThreshold(30);
        assertTrue(firstSampleBigEndian(frame()) < 1000, "now 30 ducks the music");
    }

    @Test
    void deregisteringASourceDropsItFromTheNextFrame() {
        FakeSource a = new FakeSource(false, (short) 1000);
        FakeSource b = new FakeSource(false, (short) 1000);
        pipeline.registerSendHandler(music, a, 100, 50);
        pipeline.registerSendHandler(tts, b, 100, 50);
        assertEquals(2000, firstSampleBigEndian(frame()));

        pipeline.deregisterSendHandler(tts);

        assertEquals(1000, firstSampleBigEndian(frame()));
        assertFalse(pipeline.hasSendHandler(tts));
        assertNull(pipeline.getSendHandler(tts));
        assertEquals(1, b.provideCalls);
    }
}
