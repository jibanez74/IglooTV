package com.igloo.blindpenguincoder.feature.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackRowMappingTest {

    /** The server's bucket rule, byte for byte: no trimming, no locale folding. */
    @Test
    fun `letter buckets follow the server's first-character rule`() {
        assertEquals("A", letterBucket("abbey road"))
        assertEquals("Y", letterBucket("Yesterday"))
        assertEquals("#", letterBucket("1999"))
        assertEquals("#", letterBucket(" Leading space"))
        assertEquals("#", letterBucket("Élan"))
        assertEquals("#", letterBucket(""))
    }

    @Test
    fun `the folded header names the letter or the symbol bucket`() {
        assertEquals("Tracks starting with A", spokenLetterHeader("A"))
        assertEquals("Tracks starting with a number or symbol", spokenLetterHeader("#"))
    }

    @Test
    fun `the subtitle joins artist and album and drops what is missing`() {
        assertEquals("The Beatles · Help!", trackSubtitle("The Beatles", "Help!"))
        assertEquals("The Beatles", trackSubtitle("The Beatles", null))
        assertEquals("Help!", trackSubtitle("  ", "Help!"))
        assertNull(trackSubtitle(null, ""))
    }

    @Test
    fun `the spoken sentence folds a prefix, skips absent parts, and speaks the duration in words`() {
        assertEquals(
            "Tracks starting with Y. Yesterday. The Beatles · Help!. 2 minutes and 5 seconds.",
            trackSpokenInfo("Tracks starting with Y", "Yesterday", "The Beatles · Help!", 125.0),
        )
        assertEquals("Yesterday.", trackSpokenInfo(null, "Yesterday", null, 0.0))
    }
}
