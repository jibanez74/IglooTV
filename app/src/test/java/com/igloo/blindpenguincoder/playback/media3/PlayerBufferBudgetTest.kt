package com.igloo.blindpenguincoder.playback.media3

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerBufferBudgetTest {

    private val mib = 1024L * 1024L

    @Test
    fun aStandardHeapKeepsTwoThirdsForTheRestOfTheApp() {
        // A Shield app that does not ask for a large heap: 192 MB.
        assertEquals((192 * mib / 3).toInt(), videoBufferBudgetBytes(192 * mib))
    }

    @Test
    fun aLargeHeapGetsMedia3sDefaultAndNoMore() {
        // A Shield app with largeHeap: 512 MB, whose third is above the default.
        assertEquals(MEDIA3_DEFAULT_BUFFER_BYTES, videoBufferBudgetBytes(512 * mib))
        assertEquals(MEDIA3_DEFAULT_BUFFER_BYTES, videoBufferBudgetBytes(Long.MAX_VALUE))
    }

    @Test
    fun theDefaultIsMedia3sVideoAndAudioBuffers() {
        assertEquals(2000 * 64 * 1024 + 200 * 64 * 1024, MEDIA3_DEFAULT_BUFFER_BYTES)
    }
}
