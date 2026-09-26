package fr.farmvivi.fluxcord.plugins.aiaudio.persona;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The mood: two axes, bounded, fading back to neutral on their own.
 *
 * <p>The decay is the part that matters. Without it one heated exchange would colour every later
 * conversation in that channel for good, and since it is computed from the elapsed time on read, nothing has
 * to run while a channel is quiet — which is also why it can be tested without waiting.
 */
class MoodTest {

    private static final long NOW = 1_000_000L;

    @Test
    void aNewMoodSaysNothing() {
        Mood mood = Mood.neutral(NOW);

        assertEquals(0, mood.energy());
        assertEquals(0, mood.warmth());
        assertTrue(mood.isNeutral());
        assertEquals("neutral", mood.label());
    }

    @Test
    void aNudgeMovesTheAxisItNames() {
        Mood mood = Mood.neutral(NOW).nudged(0.3, -0.2, NOW);

        assertEquals(0.3, mood.energy(), 1e-9);
        assertEquals(-0.2, mood.warmth(), 1e-9);
        assertEquals(NOW, mood.updatedAtMs());
    }

    @Test
    void severalNudgesInTheSameDirectionAddUp() {
        Mood mood = Mood.neutral(NOW).nudged(0.2, 0, NOW).nudged(0.2, 0, NOW).nudged(0.2, 0, NOW);

        assertEquals(0.6, mood.energy(), 1e-9);
        assertFalse(mood.isNeutral());
    }

    @Test
    void theAxesCannotLeaveTheirRangeHoweverHardTheyArePushed() {
        Mood hot = Mood.neutral(NOW).nudged(5, -5, NOW);

        assertEquals(1, hot.energy());
        assertEquals(-1, hot.warmth());
    }

    @Test
    void aMoodHalvesOverItsHalfLife() {
        Mood mood = Mood.neutral(NOW).nudged(0.8, 0.4, NOW);

        Mood later = mood.at(NOW + Mood.HALF_LIFE.toMillis());

        assertEquals(0.4, later.energy(), 1e-6);
        assertEquals(0.2, later.warmth(), 1e-6);
    }

    @Test
    void aMoodIsAllButGoneAfterSeveralHalfLives() {
        Mood mood = Mood.neutral(NOW).nudged(1, 1, NOW);

        Mood muchLater = mood.at(NOW + Mood.HALF_LIFE.multipliedBy(6).toMillis());

        assertTrue(muchLater.isNeutral(), "a quiet channel calms down without anything running");
    }

    @Test
    void readingAMoodInThePastChangesNothing() {
        // Clocks go backwards (NTP, a restart); that must not amplify a mood.
        Mood mood = Mood.neutral(NOW).nudged(0.5, 0, NOW);

        assertEquals(mood, mood.at(NOW - 60_000));
        assertEquals(mood, mood.at(NOW));
    }

    @Test
    void aNudgeDecaysFirstSoAnOldMoodDoesNotCompound() {
        // Nudging a stale mood without fading it would let yesterday's excitement add to today's.
        Mood old = Mood.neutral(NOW).nudged(0.8, 0, NOW);

        Mood nudgedMuchLater = old.nudged(0.1, 0, NOW + Mood.HALF_LIFE.toMillis());

        assertEquals(0.5, nudgedMuchLater.energy(), 1e-6, "half of 0.8, plus 0.1");
        assertEquals(NOW + Mood.HALF_LIFE.toMillis(), nudgedMuchLater.updatedAtMs());
    }

    @Test
    void decayKeepsTheOriginalTimestampSoItIsNotResetByReading() {
        Mood mood = Mood.neutral(NOW).nudged(0.8, 0, NOW);

        assertEquals(NOW, mood.at(NOW + 60_000).updatedAtMs(),
                "reading is not touching: the fade must keep progressing");
    }

    @Test
    void theLabelNamesTheQuadrant() {
        assertEquals("neutral", Mood.neutral(NOW).label());
        assertEquals("enthusiastic", Mood.neutral(NOW).nudged(0.7, 0.1, NOW).label());
        assertEquals("subdued", Mood.neutral(NOW).nudged(-0.7, 0, NOW).label());
        assertEquals("tense", Mood.neutral(NOW).nudged(0.7, -0.6, NOW).label());
        assertEquals("warm", Mood.neutral(NOW).nudged(0.1, 0.7, NOW).label());
        assertEquals("distant", Mood.neutral(NOW).nudged(0, -0.7, NOW).label());
    }

    @Test
    void aBarelyMovedMoodIsStillNeutral() {
        // Otherwise every single sentence would be reported as a change of mood.
        Mood mood = Mood.neutral(NOW).nudged(0.08, 0.08, NOW);

        assertTrue(mood.isNeutral());
        assertEquals("neutral", mood.label());
    }

    @Test
    void aBrokenValueIsTreatedAsNeutralRatherThanPropagated() {
        // A NaN would poison every later computation, silently.
        Mood mood = new Mood(Double.NaN, Double.NaN, NOW);

        assertEquals(0, mood.energy());
        assertEquals(0, mood.warmth());
    }

    @Test
    void theHalfLifeIsStatedOnceAndInMinutes() {
        assertEquals(Duration.ofMinutes(20), Mood.HALF_LIFE);
    }
}
