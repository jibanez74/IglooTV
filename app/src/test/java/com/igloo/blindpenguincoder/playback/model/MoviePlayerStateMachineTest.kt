package com.igloo.blindpenguincoder.playback.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoviePlayerStateMachineTest {

    private val loading = MoviePlayerState()

    private fun playing(duration: Double = 600.0, position: Double = 100.0) = loading
        .onEvent(MoviePlayerEvent.Ready(duration))
        .onEvent(MoviePlayerEvent.IsPlayingChanged(true))
        .onEvent(MoviePlayerEvent.Time(position, duration))

    // --- resume ---

    @Test
    fun `resume choice moves awaiting-resume to loading and nothing else`() {
        val awaiting = MoviePlayerState(phase = MoviePlayerPhase.AwaitingResume)
        assertEquals(MoviePlayerPhase.Loading, awaiting.onResumeChosen().phase)
        assertEquals(MoviePlayerPhase.Playing, playing().onResumeChosen().phase)
    }

    @Test
    fun `buffering does not disturb the resume prompt`() {
        val awaiting = MoviePlayerState(phase = MoviePlayerPhase.AwaitingResume)
        assertEquals(
            MoviePlayerPhase.AwaitingResume,
            awaiting.onEvent(MoviePlayerEvent.Buffering).phase,
        )
    }

    // --- ready / play / pause / buffering ---

    @Test
    fun `ready lands on paused until playback actually starts`() {
        val state = loading.onEvent(MoviePlayerEvent.Ready(3600.0))
        assertTrue(state.ready)
        assertEquals(MoviePlayerPhase.Paused, state.phase)
        assertEquals(3600.0, state.durationSec, 0.0)
    }

    @Test
    fun `is-playing true and false toggle playing and paused`() {
        val state = playing()
        assertEquals(MoviePlayerPhase.Playing, state.phase)
        assertEquals(
            MoviePlayerPhase.Paused,
            state.onEvent(MoviePlayerEvent.IsPlayingChanged(false)).phase,
        )
    }

    @Test
    fun `is-playing false does not downgrade buffering`() {
        val state = playing().onEvent(MoviePlayerEvent.Buffering)
        assertEquals(MoviePlayerPhase.Buffering, state.phase)
        assertEquals(
            MoviePlayerPhase.Buffering,
            state.onEvent(MoviePlayerEvent.IsPlayingChanged(false)).phase,
        )
    }

    @Test
    fun `ready after a mid-play rebuffer returns to paused until play resumes`() {
        val state = playing()
            .onEvent(MoviePlayerEvent.Buffering)
            .onEvent(MoviePlayerEvent.Ready(600.0))
        assertEquals(MoviePlayerPhase.Paused, state.phase)
        assertEquals(
            MoviePlayerPhase.Playing,
            state.onEvent(MoviePlayerEvent.IsPlayingChanged(true)).phase,
        )
    }

    // --- duration and time ---

    @Test
    fun `a known duration never shrinks back to zero`() {
        val state = playing(duration = 600.0)
            .onEvent(MoviePlayerEvent.Time(150.0, 0.0))
        assertEquals(600.0, state.durationSec, 0.0)
        assertEquals(150.0, state.currentTimeSec, 0.0)
    }

    @Test
    fun `time clamps a negative position to zero`() {
        assertEquals(
            0.0,
            playing().onEvent(MoviePlayerEvent.Time(-3.0, 600.0)).currentTimeSec,
            0.0,
        )
    }

    // --- seeking ---

    @Test
    fun `seek target clamps into the playable range`() {
        val state = playing(duration = 600.0, position = 5.0)
        assertEquals(0.0, state.seekTarget(-10.0), 0.0)
        assertEquals(15.0, state.seekTarget(10.0), 0.0)
    }

    @Test
    fun `a forward seek near the end lands on the end instead of overshooting`() {
        val state = playing(duration = 600.0, position = 595.0)
        assertEquals(600.0, state.seekTarget(10.0), 0.0)
        assertEquals(600.0, state.onSeekApplied(state.seekTarget(10.0)).currentTimeSec, 0.0)
    }

    @Test
    fun `seek applied moves the bar optimistically`() {
        assertEquals(
            250.0,
            playing().onSeekApplied(250.0).currentTimeSec,
            0.0,
        )
    }

    // --- ended ---

    @Test
    fun `ended pins the position to the duration`() {
        val state = playing(duration = 600.0, position = 590.0).onEvent(MoviePlayerEvent.Ended)
        assertEquals(MoviePlayerPhase.Ended, state.phase)
        assertEquals(600.0, state.currentTimeSec, 0.0)
    }

    @Test
    fun `events after ended change nothing`() {
        val ended = playing().onEvent(MoviePlayerEvent.Ended)
        assertEquals(ended, ended.onEvent(MoviePlayerEvent.IsPlayingChanged(true)))
        assertEquals(ended, ended.onEvent(MoviePlayerEvent.Buffering))
        assertEquals(ended, ended.onEvent(MoviePlayerEvent.Time(10.0, 600.0)))
        assertEquals(ended, ended.onSeekApplied(10.0))
    }

    // --- errors ---

    @Test
    fun `the first error is sticky`() {
        val failed = playing().onEvent(MoviePlayerEvent.Error("The stream stopped."))
        assertEquals(MoviePlayerPhase.Error, failed.phase)
        assertEquals("The stream stopped.", failed.errorMessage)

        val after = failed
            .onEvent(MoviePlayerEvent.Error("Something else."))
            .onEvent(MoviePlayerEvent.IsPlayingChanged(true))
            .onEvent(MoviePlayerEvent.Ready(600.0))
            .onEvent(MoviePlayerEvent.Ended)
        assertEquals(MoviePlayerPhase.Error, after.phase)
        assertEquals("The stream stopped.", after.errorMessage)
    }

    @Test
    fun `an error can still follow ended`() {
        val state = playing().onEvent(MoviePlayerEvent.Ended)
            .onEvent(MoviePlayerEvent.Error("The stream stopped."))
        assertEquals(MoviePlayerPhase.Error, state.phase)
    }

    // --- tracks ---

    @Test
    fun `tracks changed replaces both menus and survives every phase`() {
        val audio = listOf(TrackOption("0:0", "English · 5.1 surround", selected = true))
        val subtitles = listOf(TrackOption("1:0", "English", selected = false))
        val state = playing().onEvent(MoviePlayerEvent.TracksChanged(audio, subtitles))
        assertEquals(audio, state.audioOptions)
        assertEquals(subtitles, state.subtitleOptions)

        // Even under an error the menus keep their last real content.
        val failed = state.onEvent(MoviePlayerEvent.Error("boom"))
            .onEvent(MoviePlayerEvent.TracksChanged(emptyList(), emptyList()))
        assertTrue(failed.audioOptions.isEmpty())
    }
}
