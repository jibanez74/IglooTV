package com.igloo.blindpenguincoder.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFormattingTest {

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
        assertEquals("1 minute 12 seconds", formatSpokenTime(72.4))
        assertEquals("2 minutes", formatSpokenTime(120.0))
        assertEquals("1 hour 1 minute 15 seconds", formatSpokenTime(3675.0))
        assertEquals("1 second", formatSpokenTime(1.0))
        assertEquals("0 seconds", formatSpokenTime(0.0))
        // The whole-minute singular: this is the case the details screen's own spoken runtime
        // used to get wrong ("1 minutes"), and it now formats through here.
        assertEquals("1 minute", formatSpokenTime(60.0))
        assertEquals("1 hour", formatSpokenTime(3600.0))
    }

    @Test
    fun `progress fraction clamps and guards a zero duration`() {
        assertEquals(0.25f, progressFraction(1800.0, 7200.0), 0.0001f)
        assertEquals(1f, progressFraction(9000.0, 7200.0), 0.0f)
        assertEquals(0f, progressFraction(1800.0, 0.0), 0.0f)
    }

    @Test
    fun `progress label rounds up and floors at one minute`() {
        assertEquals("90 min left", progressLabel(1800.0, 7200.0))
        // 59.5 minutes left rounds up, not down.
        assertEquals("60 min left", progressLabel(3630.0, 7200.0))
        assertEquals("1 min left", progressLabel(7199.0, 7200.0))
        assertEquals("In progress", progressLabel(10.0, 0.0))
    }
}
