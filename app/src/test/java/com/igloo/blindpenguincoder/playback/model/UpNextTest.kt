package com.igloo.blindpenguincoder.playback.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpNextTest {

    private fun state(phase: VideoPlayerPhase, position: Double, duration: Double = 1_200.0) =
        VideoPlayerState(phase = phase, currentTimeSec = position, durationSec = duration)

    @Test
    fun `no card while more than the lead remains`() {
        assertNull(upNextOffer(state(VideoPlayerPhase.Playing, 1_184.9), dismissed = false))
        assertTrue(upNextOutsideLead(state(VideoPlayerPhase.Playing, 1_184.9)))
    }

    @Test
    fun `the card appears at the lead and counts whole seconds up`() {
        assertEquals(UpNextOffer(15), upNextOffer(state(VideoPlayerPhase.Playing, 1_185.0), dismissed = false))
        assertEquals(UpNextOffer(12), upNextOffer(state(VideoPlayerPhase.Playing, 1_188.4), dismissed = false))
        assertEquals(UpNextOffer(1), upNextOffer(state(VideoPlayerPhase.Playing, 1_199.5), dismissed = false))
        assertFalse(upNextOutsideLead(state(VideoPlayerPhase.Playing, 1_185.0)))
    }

    @Test
    fun `a paused or buffering picture keeps the card`() {
        assertEquals(UpNextOffer(10), upNextOffer(state(VideoPlayerPhase.Paused, 1_190.0), dismissed = false))
        assertEquals(UpNextOffer(10), upNextOffer(state(VideoPlayerPhase.Buffering, 1_190.0), dismissed = false))
    }

    @Test
    fun `a dismissal hides the card`() {
        assertNull(upNextOffer(state(VideoPlayerPhase.Playing, 1_190.0), dismissed = true))
    }

    @Test
    fun `loading, the resume prompt and an error never offer`() {
        listOf(VideoPlayerPhase.Loading, VideoPlayerPhase.AwaitingResume, VideoPlayerPhase.Error).forEach {
            assertNull(upNextOffer(state(it, 1_190.0), dismissed = false))
        }
    }

    @Test
    fun `an unknown duration offers nothing before the end`() {
        assertNull(upNextOffer(state(VideoPlayerPhase.Playing, 500.0, duration = 0.0), dismissed = false))
        assertFalse(upNextOutsideLead(state(VideoPlayerPhase.Playing, 500.0, duration = 0.0)))
    }

    @Test
    fun `ended offers with nothing left to count, duration known or not`() {
        assertEquals(UpNextOffer(0), upNextOffer(state(VideoPlayerPhase.Ended, 1_200.0), dismissed = false))
        assertEquals(UpNextOffer(0), upNextOffer(state(VideoPlayerPhase.Ended, 0.0, duration = 0.0), dismissed = false))
        assertNull(upNextOffer(state(VideoPlayerPhase.Ended, 1_200.0), dismissed = true))
    }

    @Test
    fun `remaining seconds never go negative`() {
        assertEquals(0.0, remainingSec(durationSec = 100.0, currentTimeSec = 130.0))
        assertNull(remainingSec(durationSec = Double.NaN, currentTimeSec = 10.0))
    }
}
