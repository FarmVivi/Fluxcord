package fr.farmvivi.fluxcord.plugins.music.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TimeParser}: the input accepted by {@code /seek} and the durations shown in the player UI.
 */
class TimeParserTest {

    @Test
    void plainNumbersAreSeconds() {
        assertEquals(45_000, TimeParser.parseTime("45"));
        assertEquals(0, TimeParser.parseTime("0"));
        assertEquals(3_600_000, TimeParser.parseTime("3600"));
    }

    @Test
    void unitsAreCombined() {
        assertEquals(5_400_000, TimeParser.parseTime("1h30m"));
        assertEquals(150_000, TimeParser.parseTime("2m30s"));
        assertEquals(45_000, TimeParser.parseTime("45s"));
        assertEquals(3_723_000, TimeParser.parseTime("1h2m3s"));
        assertEquals(5_400_000, TimeParser.parseTime("1H30M"), "case insensitive");
    }

    @Test
    void invalidInputIsRejected() {
        assertEquals(-1, TimeParser.parseTime(null));
        assertEquals(-1, TimeParser.parseTime(""));
        assertEquals(-1, TimeParser.parseTime("abc"));
        assertEquals(-1, TimeParser.parseTime("30m1h"), "units must be in order");
        assertEquals(-1, TimeParser.parseTime("  "), "whitespace is not a duration");
        assertEquals(-1, TimeParser.parseTime("h"), "a unit without a number matches nothing");
    }

    @Test
    void clockNotationIsAccepted() {
        // What formatTime produces and what /seek suggests must be readable back.
        assertEquals(90_000, TimeParser.parseTime("1:30"));
        assertEquals(3_723_000, TimeParser.parseTime("1:02:03"));
        assertEquals(0, TimeParser.parseTime("0:00"));
        assertEquals(TimeParser.parseTime("1:02:03"), TimeParser.parseTime(TimeParser.formatTime(3_723_000)));

        assertEquals(-1, TimeParser.parseTime("1:60"), "60 seconds is not a valid field");
        assertEquals(-1, TimeParser.parseTime("1:2:3:4"));
        assertEquals(-1, TimeParser.parseTime("1:"));
        assertEquals(-1, TimeParser.parseTime(":30"));
        assertEquals(-1, TimeParser.parseTime("a:30"));
    }

    @Test
    void zeroIsAValidPositionAndNegativesAreNot() {
        assertEquals(0, TimeParser.parseTime("0s"), "seeking back to the start of the track");
        assertEquals(0, TimeParser.parseTime("0m0s"));
        assertEquals(-1, TimeParser.parseTime("-5"), "a negative seek target is not a valid position");
    }

    @Test
    void formatTimeUsesHoursOnlyWhenNeeded() {
        assertEquals("0:00", TimeParser.formatTime(0));
        assertEquals("0:45", TimeParser.formatTime(45_000));
        assertEquals("2:30", TimeParser.formatTime(150_000));
        assertEquals("1:02:03", TimeParser.formatTime(3_723_000));
        assertEquals("∞", TimeParser.formatTime(-1), "live streams have no length");
    }

    @Test
    void formatTimeShortDropsEmptyUnits() {
        assertEquals("0s", TimeParser.formatTimeShort(0));
        assertEquals("45s", TimeParser.formatTimeShort(45_000));
        assertEquals("1h 30m", TimeParser.formatTimeShort(5_400_000));
        assertEquals("1h 2m 3s", TimeParser.formatTimeShort(3_723_000));
        assertEquals("2m", TimeParser.formatTimeShort(120_000));
        assertEquals("∞", TimeParser.formatTimeShort(-1));
    }
}
