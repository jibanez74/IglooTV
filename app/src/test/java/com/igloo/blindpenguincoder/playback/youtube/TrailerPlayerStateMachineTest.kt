package com.igloo.blindpenguincoder.playback.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class TrailerPlayerStateMachineTest {

    @Test
    fun `ready then playing reaches Playing with the reported duration`() {
        val state = TrailerPlayerState()
            .onReady(durationSec = 143.0)
            .onYtStateChange(1)

        assertEquals(TrailerPhase.Playing, state.phase)
        assertEquals(143.0, state.durationSec, 0.0)
        assertEquals(true, state.ready)
    }

    @Test
    fun `state code two pauses and zero ends`() {
        val playing = TrailerPlayerState().onReady(100.0).onYtStateChange(1)

        assertEquals(TrailerPhase.Paused, playing.onYtStateChange(2).phase)
        assertEquals(TrailerPhase.Ended, playing.onYtStateChange(0).phase)
    }

    @Test
    fun `unstarted and cued stay Loading and unknown codes keep the current phase`() {
        val playing = TrailerPlayerState().onReady(100.0).onYtStateChange(1)

        assertEquals(TrailerPhase.Loading, playing.onYtStateChange(-1).phase)
        assertEquals(TrailerPhase.Loading, playing.onYtStateChange(5).phase)
        assertEquals(TrailerPhase.Playing, playing.onYtStateChange(42).phase)
    }

    @Test
    fun `buffering keeps the current time and duration`() {
        val state = TrailerPlayerState()
            .onReady(100.0)
            .onYtStateChange(1)
            .onTime(currentSec = 37.5, durationSec = 100.0)
            .onYtStateChange(3)

        assertEquals(TrailerPhase.Buffering, state.phase)
        assertEquals(37.5, state.currentTimeSec, 0.0)
        assertEquals(100.0, state.durationSec, 0.0)
    }

    @Test
    fun `embed restriction codes map to the embed-blocked message`() {
        for (code in listOf(101, 150, 152, 153)) {
            val state = TrailerPlayerState().onError(code)
            assertEquals(TrailerPhase.Error, state.phase)
            assertEquals(
                "YouTube doesn't allow this video to play outside youtube.com.",
                state.errorMessage,
            )
        }
    }

    @Test
    fun `the remaining error codes each get their own message`() {
        assertEquals(
            "This video's YouTube id is invalid.",
            TrailerPlayerState().onError(2).errorMessage,
        )
        assertEquals(
            "YouTube's player hit a playback error.",
            TrailerPlayerState().onError(5).errorMessage,
        )
        assertEquals(
            "This video was not found on YouTube.",
            TrailerPlayerState().onError(100).errorMessage,
        )
        assertEquals(
            "The YouTube player took too long to load.",
            TrailerPlayerState().onError(TrailerPlayerState.API_LOAD_TIMEOUT).errorMessage,
        )
        assertEquals(
            "The trailer could not be played.",
            TrailerPlayerState().onError(9999).errorMessage,
        )
    }

    @Test
    fun `the first error is sticky against later events`() {
        val state = TrailerPlayerState()
            .onError(150)
            .onError(5)
            .onYtStateChange(1)
            .onReady(100.0)
            .onTime(10.0, 100.0)
            .onSeekApplied(20.0)

        assertEquals(TrailerPhase.Error, state.phase)
        assertEquals(
            "YouTube doesn't allow this video to play outside youtube.com.",
            state.errorMessage,
        )
        assertEquals(0.0, state.currentTimeSec, 0.0)
    }

    @Test
    fun `watchdog expiry while loading becomes an error but never downgrades a ready player`() {
        val stillLoading = TrailerPlayerState().onWatchdogExpired()
        assertEquals(TrailerPhase.Error, stillLoading.phase)
        assertEquals("The video player took too long to load.", stillLoading.errorMessage)

        val ready = TrailerPlayerState().onReady(100.0).onWatchdogExpired()
        assertEquals(TrailerPhase.Loading, ready.phase)
        assertEquals(null, ready.errorMessage)
    }

    @Test
    fun `seeks clamp into zero to duration`() {
        val state = TrailerPlayerState().onReady(100.0)

        assertEquals(0.0, state.onSeekApplied(-5.0).currentTimeSec, 0.0)
        assertEquals(100.0, state.onSeekApplied(250.0).currentTimeSec, 0.0)
        assertEquals(42.0, state.onSeekApplied(42.0).currentTimeSec, 0.0)
    }

    /**
     * The engine gets the same clamped value the bar does. An unclamped forward seek would run
     * the embed past the end, which reports Ended — and the player auto-closes on Ended, so the
     * miss would show up as the trailer quietly exiting under a Fast-Forward press.
     */
    @Test
    fun `a relative seek target never passes the end or goes below zero`() {
        val nearTheEnd = TrailerPlayerState().onReady(143.0).onTime(currentSec = 140.0, durationSec = 143.0)
        assertEquals(143.0, nearTheEnd.seekTarget(10.0), 0.0)

        val nearTheStart = TrailerPlayerState().onReady(143.0).onTime(currentSec = 4.0, durationSec = 143.0)
        assertEquals(0.0, nearTheStart.seekTarget(-10.0), 0.0)

        assertEquals(14.0, nearTheStart.seekTarget(10.0), 0.0)
    }

    @Test
    fun `a relative seek before the duration is known only guards the floor`() {
        val unknownDuration = TrailerPlayerState().onTime(currentSec = 4.0, durationSec = 0.0)

        assertEquals(0.0, unknownDuration.seekTarget(-10.0), 0.0)
        assertEquals(14.0, unknownDuration.seekTarget(10.0), 0.0)
    }

    @Test
    fun `a seek before the duration is known never goes negative`() {
        assertEquals(0.0, TrailerPlayerState().onSeekApplied(-10.0).currentTimeSec, 0.0)
        assertEquals(10.0, TrailerPlayerState().onSeekApplied(10.0).currentTimeSec, 0.0)
    }

    @Test
    fun `a time update never shrinks a known duration to zero`() {
        val state = TrailerPlayerState()
            .onReady(143.0)
            .onTime(currentSec = 12.0, durationSec = 0.0)

        assertEquals(143.0, state.durationSec, 0.0)
        assertEquals(12.0, state.currentTimeSec, 0.0)
    }
}
