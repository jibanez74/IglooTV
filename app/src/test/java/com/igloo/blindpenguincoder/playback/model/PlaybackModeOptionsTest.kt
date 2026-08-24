package com.igloo.blindpenguincoder.playback.model

import com.igloo.blindpenguincoder.data.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackModeOptionsTest {

    @Test
    fun `a 4K source is offered the whole ladder`() {
        assertEquals(
            listOf(
                PlaybackMode.Direct,
                PlaybackMode.Remux,
                PlaybackMode.P2160Mbps16,
                PlaybackMode.P1080Mbps8,
                PlaybackMode.P1080Mbps6,
                PlaybackMode.P1080Mbps4,
                PlaybackMode.P720Mbps3,
            ),
            availablePlaybackModes(videoHeight = 2160),
        )
    }

    @Test
    fun `a 1080p source is never offered an upscale`() {
        assertEquals(
            listOf(
                PlaybackMode.Direct,
                PlaybackMode.Remux,
                PlaybackMode.P1080Mbps8,
                PlaybackMode.P1080Mbps6,
                PlaybackMode.P1080Mbps4,
                PlaybackMode.P720Mbps3,
            ),
            availablePlaybackModes(videoHeight = 1080),
        )
    }

    @Test
    fun `a source below every profile keeps the 720p floor`() {
        assertEquals(
            listOf(PlaybackMode.Direct, PlaybackMode.Remux, PlaybackMode.P720Mbps3),
            availablePlaybackModes(videoHeight = 480),
        )
    }

    @Test
    fun `an unknown height offers everything rather than guessing`() {
        assertEquals(PlaybackMode.entries.toList(), availablePlaybackModes(videoHeight = null))
    }

    /** For the in-player menu: an unplayable Direct is not listed; Remux is always there. */
    @Test
    fun `direct can be omitted while remux never is`() {
        val options = availablePlaybackModes(videoHeight = 480, includeDirect = false)
        assertEquals(listOf(PlaybackMode.Remux, PlaybackMode.P720Mbps3), options)
    }
}
