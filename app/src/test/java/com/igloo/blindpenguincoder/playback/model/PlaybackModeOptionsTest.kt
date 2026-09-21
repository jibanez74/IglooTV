package com.igloo.blindpenguincoder.playback.model

import com.igloo.blindpenguincoder.data.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackModeOptionsTest {

    @Test
    fun `the normative ladder always contains all seven modes in enum order`() {
        assertEquals(PlaybackMode.entries.toList(), availablePlaybackModes())
    }
}
