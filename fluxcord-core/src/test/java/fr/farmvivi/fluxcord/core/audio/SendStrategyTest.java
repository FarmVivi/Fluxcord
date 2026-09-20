package fr.farmvivi.fluxcord.core.audio;

import fr.farmvivi.fluxcord.core.audio.SendStrategy.Decision;
import fr.farmvivi.fluxcord.core.audio.SendStrategy.Mode;
import fr.farmvivi.fluxcord.core.audio.SendStrategy.SourceState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SendStrategyTest {

    private static final int THRESHOLD = 50;

    private static SourceState pcm(String name, boolean active, int priority) {
        return new SourceState(name, active, false, priority);
    }

    private static SourceState opus(String name, boolean active, int priority) {
        return new SourceState(name, active, true, priority);
    }

    @Test
    void noSourceOrNoActiveSourceIsSilent() {
        assertSame(Decision.SILENT, SendStrategy.decide(List.of(), THRESHOLD));
        Decision d = SendStrategy.decide(List.of(pcm("a", false, 10), opus("b", false, 90)), THRESHOLD);
        assertEquals(Mode.SILENT, d.mode());
        assertNull(d.duckingSource(), "inactive sources never duck");
    }

    @Test
    void singleActivePcmIsBypassedAsPcm() {
        Decision d = SendStrategy.decide(List.of(pcm("a", true, 10), pcm("b", false, 10)), THRESHOLD);
        assertEquals(Mode.BYPASS, d.mode());
        assertEquals("a", d.bypassSource());
        assertFalse(d.opusOutput());
        assertTrue(d.mixSources().isEmpty());
    }

    @Test
    void twoActivePcmAreMixedInOrderAndOpusIsDropped() {
        Decision d = SendStrategy.decide(List.of(pcm("a", true, 10), opus("o", true, 10), pcm("b", true, 10)), THRESHOLD);
        assertEquals(Mode.MIX, d.mode());
        assertEquals(List.of("a", "b"), d.mixSources());
        assertNull(d.bypassSource());
        assertFalse(d.opusOutput());
    }

    @Test
    void pcmWinsOverOpus() {
        Decision d = SendStrategy.decide(List.of(opus("o", true, 90), pcm("p", true, 10)), THRESHOLD);
        assertEquals(Mode.BYPASS, d.mode());
        assertEquals("p", d.bypassSource());
        assertFalse(d.opusOutput());
        assertEquals("o", d.duckingSource(), "the dropped Opus source still ducks the PCM one");
    }

    @Test
    void onlyOpusRelaysTheHighestPriorityOne() {
        Decision d = SendStrategy.decide(List.of(opus("low", true, 10), opus("high", true, 40), opus("mid", true, 20)), THRESHOLD);
        assertEquals(Mode.BYPASS, d.mode());
        assertEquals("high", d.bypassSource());
        assertTrue(d.opusOutput());
        assertNull(d.duckingSource(), "40 < threshold");
    }

    @Test
    void duckingSourceIsTheHighestActivePriorityAtOrAboveThreshold() {
        Decision d = SendStrategy.decide(List.of(pcm("a", true, 50), pcm("b", true, 70), pcm("c", false, 99)), THRESHOLD);
        assertEquals("b", d.duckingSource());
        assertEquals(Mode.MIX, d.mode());

        assertNull(SendStrategy.decide(List.of(pcm("a", true, 49)), THRESHOLD).duckingSource());
        assertEquals("a", SendStrategy.decide(List.of(pcm("a", true, 49)), 49).duckingSource(), "threshold is inclusive");
    }

    @Test
    void decisionsAreImmutable() {
        Decision d = SendStrategy.decide(List.of(pcm("a", true, 1), pcm("b", true, 1)), THRESHOLD);
        assertThrows(UnsupportedOperationException.class, () -> d.mixSources().add("x"));
    }
}
