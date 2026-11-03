package com.igloo.blindpenguincoder.playback.media3

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFailureRecoveryTest {
    @Test
    fun `late wrapped failure from a discarded subtitle stream does not end its replacement`() {
        val discarded = IOException("subtitle 404")
        val latePlayerError = RuntimeException("source error", IOException("loader", discarded))
        assertTrue(isReportedSubtitleFailure(latePlayerError, discarded))
    }

    @Test
    fun `another load with the same error still fails normally`() {
        val discarded = IOException("404")
        val currentSegmentFailure = IOException("404")
        assertFalse(isReportedSubtitleFailure(RuntimeException(currentSegmentFailure), discarded))
    }

    @Test
    fun `without a discarded subtitle no player failure is ignored`() {
        assertFalse(isReportedSubtitleFailure(IOException("failed"), null))
    }
}
