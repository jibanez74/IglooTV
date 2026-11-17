package com.igloo.blindpenguincoder.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackModeTest {

    @Test
    fun `every mode reads back from its contract value`() {
        PlaybackMode.entries.forEach { mode -> assertEquals(mode, playbackModeOf(mode.wireId)) }
        assertEquals("direct", PlaybackMode.Direct.wireId)
        assertEquals("1080p_8mbps", PlaybackMode.P1080Mbps8.wireId)
    }

    @Test
    fun `a value this build does not know reads as no mode`() {
        assertNull(playbackModeOf("future_server_profile"))
    }
}
