package com.igloo.blindpenguincoder.playback.media3

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIntentTest {

    @Test
    fun `pause during suspended preflight wins when the source becomes ready`() {
        val intent = PlaybackIntent()
        intent.start(initialPlayWhenReady = true)

        intent.pause()

        assertFalse(intent.shouldPlay)
    }

    @Test
    fun `play during suspended preflight is read at completion`() {
        val intent = PlaybackIntent()
        intent.start(initialPlayWhenReady = false)

        intent.play()

        assertTrue(intent.shouldPlay)
    }

    @Test
    fun `host pause clears autoplay and rejects background play commands`() {
        val intent = PlaybackIntent()
        intent.start(initialPlayWhenReady = true)

        intent.hostPaused()
        assertFalse(intent.play())
        intent.hostResumed()

        assertFalse(intent.shouldPlay)
    }

    @Test
    fun `terminal failure and release reject all later transport`() {
        val failed = PlaybackIntent()
        failed.start(initialPlayWhenReady = true)
        assertTrue(failed.failTerminal())
        assertFalse(failed.play())
        assertFalse(failed.pause())
        assertFalse(failed.shouldPlay)

        val released = PlaybackIntent()
        assertTrue(released.release())
        assertFalse(released.release())
        assertFalse(released.play())
        assertFalse(released.start(initialPlayWhenReady = true))
    }
}
