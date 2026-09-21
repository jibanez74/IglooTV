package com.igloo.blindpenguincoder.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFormattingTest {

    @Test
    fun `chooses the noun by count, with an irregular plural when given`() {
        assertEquals("album", countNoun(1, "album"))
        assertEquals("albums", countNoun(0, "album"))
        assertEquals("albums", countNoun(2, "album"))
        assertEquals("movies", movieNoun(3))
        assertEquals("people", countNoun(2, "person", "people"))
    }

    @Test
    fun `formats runtimes, dropping the empty part`() {
        assertEquals("2h 50m", formatRuntime(170))
        assertEquals("2h", formatRuntime(120))
        assertEquals("45m", formatRuntime(45))
    }

    @Test
    fun `formats contract dates and rejects junk`() {
        assertEquals("December 15, 1995", formatReleaseDate("1995-12-15"))
        assertEquals("October 19, 2006", formatReleaseDate("2006-10-19"))
        assertNull(formatReleaseDate("not-a-date"))
        assertNull(formatReleaseDate(""))
    }

    @Test
    fun `timecodes show hours only when present and never go negative`() {
        assertEquals("1:12", formatTimecode(72.4))
        assertEquals("0:05", formatTimecode(5.0))
        assertEquals("1:01:15", formatTimecode(3675.0))
        assertEquals("0:00", formatTimecode(-3.0))
    }

    @Test
    fun `spoken time drops empty parts and pluralizes`() {
        assertEquals("1 minute and 12 seconds", formatSpokenTime(72.4))
        assertEquals("2 minutes", formatSpokenTime(120.0))
        assertEquals("1 hour, 1 minute, and 15 seconds", formatSpokenTime(3675.0))
        assertEquals("1 second", formatSpokenTime(1.0))
        assertEquals("0 seconds", formatSpokenTime(0.0))
        // The whole-minute singular: this is the case the details screen's own spoken runtime
        // used to get wrong ("1 minutes"), and it now formats through here.
        assertEquals("1 minute", formatSpokenTime(60.0))
        assertEquals("1 hour", formatSpokenTime(3600.0))
        assertEquals("1 hour and 5 minutes", formatSpokenTime(3900.0))
        assertEquals("1 hour, 30 minutes, and 2 seconds", formatSpokenTime(5402.0))
    }

    @Test
    fun `exact spoken time includes every unit through completed seconds`() {
        assertEquals("17 seconds", formatSpokenTimeThroughSeconds(17.9))
        assertEquals("1 minute and 0 seconds", formatSpokenTimeThroughSeconds(60.9))
        assertEquals(
            "1 hour, 3 minutes, and 17 seconds",
            formatSpokenTimeThroughSeconds(3797.9),
        )
        assertEquals(
            "1 hour, 0 minutes, and 2 seconds",
            formatSpokenTimeThroughSeconds(3602.0),
        )
    }

    @Test
    fun `exact spoken time pluralizes and clamps negative input`() {
        assertEquals(
            "1 hour, 1 minute, and 1 second",
            formatSpokenTimeThroughSeconds(3661.0),
        )
        assertEquals(
            "2 hours, 0 minutes, and 2 seconds",
            formatSpokenTimeThroughSeconds(7202.0),
        )
        assertEquals("0 seconds", formatSpokenTimeThroughSeconds(-3.8))
    }

    @Test
    fun `progress fraction preserves valid values and clamps overshoot`() {
        assertEquals(0.25f, progressFraction(1800.0, 7200.0), 0.0001f)
        assertEquals(1f, progressFraction(9000.0, 7200.0), 0.0f)
        assertEquals(0f, progressFraction(-30.0, 7200.0), 0.0f)
    }

    @Test
    fun `progress fraction rejects invalid and non-finite values`() {
        assertEquals(0f, progressFraction(1800.0, 0.0), 0.0f)
        assertEquals(0f, progressFraction(1800.0, -1.0), 0.0f)
        assertEquals(0f, progressFraction(Double.NaN, 7200.0), 0.0f)
        assertEquals(0f, progressFraction(Double.POSITIVE_INFINITY, 7200.0), 0.0f)
        assertEquals(0f, progressFraction(Double.NEGATIVE_INFINITY, 7200.0), 0.0f)
        assertEquals(0f, progressFraction(1800.0, Double.NaN), 0.0f)
        assertEquals(0f, progressFraction(1800.0, Double.POSITIVE_INFINITY), 0.0f)
        assertEquals(0f, progressFraction(1800.0, Double.NEGATIVE_INFINITY), 0.0f)
    }

    @Test
    fun `remaining time uses compact hours and rounds partial minutes up`() {
        assertEquals("2h 20m left", formatRemainingTime(1800.0, 10200.0))
        assertEquals("35m left", formatRemainingTime(0.0, 2100.0))
        assertEquals("2h left", formatRemainingTime(30.0, 7230.0))
        // 59.5 minutes left rounds up to the exact-hour form.
        assertEquals("1h left", formatRemainingTime(3630.0, 7200.0))
        assertEquals("2m left", formatRemainingTime(7080.1, 7200.0))
        assertEquals("1m left", formatRemainingTime(7140.0, 7200.0))
    }

    @Test
    fun `remaining time handles sub-minute overshot and invalid values`() {
        assertEquals("Less than 1m left", formatRemainingTime(7199.0, 7200.0))
        assertEquals("Less than 1m left", formatRemainingTime(7300.0, 7200.0))
        assertEquals("In progress", formatRemainingTime(10.0, 0.0))
        assertEquals("In progress", formatRemainingTime(10.0, Double.NaN))
    }

    @Test
    fun `spoken remaining time uses unabbreviated pluralized units`() {
        assertEquals(
            "2 hours and 20 minutes remaining",
            formatSpokenRemainingTime(1800.0, 10200.0),
        )
        assertEquals("35 minutes remaining", formatSpokenRemainingTime(0.0, 2100.0))
        assertEquals("2 hours remaining", formatSpokenRemainingTime(30.0, 7230.0))
        assertEquals("1 hour and 1 minute remaining", formatSpokenRemainingTime(0.0, 3660.0))
        assertEquals("1 minute remaining", formatSpokenRemainingTime(7140.0, 7200.0))
        assertEquals("Less than 1 minute remaining", formatSpokenRemainingTime(7300.0, 7200.0))
        assertEquals("In progress", formatSpokenRemainingTime(10.0, 0.0))
    }
}
