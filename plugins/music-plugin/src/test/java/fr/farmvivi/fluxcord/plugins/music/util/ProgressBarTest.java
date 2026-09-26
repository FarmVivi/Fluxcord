package fr.farmvivi.fluxcord.plugins.music.util;

import fr.farmvivi.fluxcord.plugins.music.ui.MusicPlayerMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The progress bar, and the two defects that lived in the player message's copy of it until this class
 * existed: an unguarded division by the duration — which throws from inside the refresh loop on a live
 * stream, where LavaPlayer reports no usable duration — and a knob that could fall off the end of the bar.
 */
class ProgressBarTest {

    @Test
    void theBarIsAlwaysExactlyTwentyCharactersWide() {
        // Codepoints, not chars: the knob is outside the BMP and counting chars would give 21.
        assertEquals(ProgressBar.LENGTH, ProgressBar.of(0, 1000).codePointCount(0,
                ProgressBar.of(0, 1000).length()));
        assertEquals(ProgressBar.LENGTH, ProgressBar.of(500, 1000).codePointCount(0,
                ProgressBar.of(500, 1000).length()));
    }

    @Test
    void theBarHasExactlyOneKnob() {
        String bar = ProgressBar.of(500, 1000);

        assertEquals(1, bar.split("🔘", -1).length - 1);
    }

    @Test
    void theKnobFollowsThePosition() {
        assertEquals(0, ProgressBar.knobIndex(0, 1000));
        assertEquals(5, ProgressBar.knobIndex(250, 1000));
        assertEquals(10, ProgressBar.knobIndex(500, 1000));
        assertEquals(19, ProgressBar.knobIndex(950, 1000));
    }

    @Test
    void aFinishedTrackKeepsItsKnobOnTheBar() {
        // (position * 20) / duration is 20 at the end, which is past the last index: the old code drew a
        // bar with no knob at all.
        assertEquals(ProgressBar.LENGTH - 1, ProgressBar.knobIndex(1000, 1000));
        assertEquals(ProgressBar.LENGTH - 1, ProgressBar.knobIndex(5000, 1000), "and past the end too");
        assertTrue(ProgressBar.of(1000, 1000).endsWith("🔘"));
    }

    @Test
    void aStreamWithNoKnownDurationKeepsTheKnobAtTheStartInsteadOfThrowing() {
        // This is the bug: duration 0 divided straight through, from inside the refresh loop.
        assertDoesNotThrow(() -> ProgressBar.of(12_345, 0));
        assertEquals(0, ProgressBar.knobIndex(12_345, 0));
        assertEquals(0, ProgressBar.knobIndex(12_345, -1));
        assertTrue(ProgressBar.of(12_345, 0).startsWith("🔘"));
    }

    @Test
    void anUnknownDurationReportedAsMaxValueDoesNotOverflow() {
        // LavaPlayer reports Long.MAX_VALUE for an unknown length; position * 20 then overflows.
        assertDoesNotThrow(() -> ProgressBar.of(60_000, Long.MAX_VALUE));
        assertEquals(0, ProgressBar.knobIndex(60_000, Long.MAX_VALUE),
                "a position is a negligible fraction of an unknown duration, so the knob stays at the start");
    }

    @Test
    void aNegativePositionIsTreatedAsTheStart() {
        assertEquals(0, ProgressBar.knobIndex(-1, 1000));
    }

    @Test
    void theBarIsNotAllKnobsOrAllTrack() {
        // The old implementation drew the played and remaining halves with the same character, so the bar
        // conveyed nothing but the knob's position. That is now the explicit design, stated once.
        String bar = ProgressBar.of(500, 1000);

        assertTrue(bar.contains("▬"));
        assertTrue(bar.contains("🔘"));
    }

    @Test
    void theButtonIdsOfThePlayerMessageRoundTrip() {
        // Not the bar, but the other pure function of the player UI: a button id must parse back into the
        // guild and action it was built from, and anything else must be rejected rather than half-read.
        MusicPlayerMessage.ButtonInfo info = MusicPlayerMessage.parseButtonId("music:g1:volume:+10");

        assertNotNull(info);
        assertEquals("g1", info.guildId());
        assertEquals("volume:+10", info.action(), "the action keeps its own colon-separated argument");
    }

    @Test
    void anIdFromAnotherPluginIsNotParsed() {
        assertNull(MusicPlayerMessage.parseButtonId("other:g1:action"));
        assertNull(MusicPlayerMessage.parseButtonId("music:g1"), "no action");
        assertNull(MusicPlayerMessage.parseButtonId("music:"), "nothing at all");
        assertNull(MusicPlayerMessage.parseButtonId(""));
    }
}
