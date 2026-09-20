package com.igloo.blindpenguincoder.playback.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlayerStateMachineTest {

    private val loading = MusicPlayerState()

    private fun playing(duration: Double = 240.0, position: Double = 30.0) = loading
        .onEvent(MusicPlayerEvent.Ready(duration))
        .onEvent(MusicPlayerEvent.IsPlayingChanged(true))
        .onEvent(MusicPlayerEvent.Time(position, duration))

    // --- ready / play / pause / buffering ---

    @Test
    fun `play intent starts armed - Play Album is itself the play press`() {
        assertTrue(loading.playWhenReady)
    }

    @Test
    fun `ready lands on paused until playback actually starts`() {
        val state = loading.onEvent(MusicPlayerEvent.Ready(240.0))
        assertTrue(state.ready)
        assertEquals(MusicPlayerPhase.Paused, state.phase)
        assertEquals(240.0, state.durationSec, 0.0)
    }

    @Test
    fun `is-playing true and false toggle playing and paused`() {
        val state = playing()
        assertEquals(MusicPlayerPhase.Playing, state.phase)
        assertEquals(
            MusicPlayerPhase.Paused,
            state.onEvent(MusicPlayerEvent.IsPlayingChanged(false)).phase,
        )
    }

    @Test
    fun `is-playing false does not downgrade buffering`() {
        val state = playing().onEvent(MusicPlayerEvent.Buffering)
        assertEquals(MusicPlayerPhase.Buffering, state.phase)
        assertEquals(
            MusicPlayerPhase.Buffering,
            state.onEvent(MusicPlayerEvent.IsPlayingChanged(false)).phase,
        )
    }

    @Test
    fun `buffering preserves play intent and pause cancels pending autoplay`() {
        val buffering = playing().onEvent(MusicPlayerEvent.Buffering)
        assertTrue(buffering.playWhenReady)

        val pausedIntent = buffering.onEvent(MusicPlayerEvent.PlayWhenReadyChanged(false))
        assertEquals(MusicPlayerPhase.Buffering, pausedIntent.phase)
        assertTrue(!pausedIntent.playWhenReady)

        val ready = pausedIntent.onEvent(MusicPlayerEvent.Ready(240.0))
        assertEquals(MusicPlayerPhase.Paused, ready.phase)
        assertTrue(!ready.playWhenReady)
    }

    // --- duration and time ---

    @Test
    fun `a known duration never shrinks back to zero within a track`() {
        val state = playing(duration = 240.0)
            .onEvent(MusicPlayerEvent.Time(45.0, 0.0))
        assertEquals(240.0, state.durationSec, 0.0)
        assertEquals(45.0, state.currentTimeSec, 0.0)
    }

    @Test
    fun `time clamps a negative position to zero`() {
        assertEquals(
            0.0,
            playing().onEvent(MusicPlayerEvent.Time(-3.0, 240.0)).currentTimeSec,
            0.0,
        )
    }

    // --- the queue ---

    @Test
    fun `track change resets time and duration for the new track`() {
        val state = playing(duration = 240.0, position = 238.0)
            .onEvent(MusicPlayerEvent.TrackChanged(1, 187.0))
        assertEquals(1, state.currentTrackIndex)
        assertEquals(0.0, state.currentTimeSec, 0.0)
        assertEquals(187.0, state.durationSec, 0.0)
    }

    @Test
    fun `time after a track change clamps against the new duration`() {
        val state = playing(duration = 240.0)
            .onEvent(MusicPlayerEvent.TrackChanged(1, 187.0))
        assertEquals(180.0, state.seekTarget(180.0), 0.0)
        assertEquals(187.0, state.onSeekApplied(500.0).currentTimeSec, 0.0)
    }

    @Test
    fun `track change with an unknown duration leaves the bar empty until ready`() {
        val state = playing(duration = 240.0)
            .onEvent(MusicPlayerEvent.TrackChanged(1, 0.0))
        assertEquals(0.0, state.durationSec, 0.0)
        assertEquals(187.0, state.onEvent(MusicPlayerEvent.Ready(187.0)).durationSec, 0.0)
    }

    @Test
    fun `a track change naming the current index keeps the playhead`() {
        // Setting the playlist is itself an item transition, so the engine reports the queue's
        // own start index before a frame plays. Treating that as an advance would zero the
        // position a Retry and a background return both resume from.
        val state = playing(duration = 240.0, position = 42.0)
            .onEvent(MusicPlayerEvent.TrackChanged(0, 240.0))
        assertEquals(0, state.currentTrackIndex)
        assertEquals(42.0, state.currentTimeSec, 0.0)
        assertEquals(240.0, state.durationSec, 0.0)
    }

    @Test
    fun `a same-index track change still refreshes a known duration`() {
        val restored = MusicPlayerState(currentTrackIndex = 1, currentTimeSec = 42.0)
        val state = restored.onEvent(MusicPlayerEvent.TrackChanged(1, 187.0))
        assertEquals(42.0, state.currentTimeSec, 0.0)
        assertEquals(187.0, state.durationSec, 0.0)
    }

    @Test
    fun `a same-index track change never shrinks the duration back to zero`() {
        val state = playing(duration = 240.0, position = 42.0)
            .onEvent(MusicPlayerEvent.TrackChanged(0, 0.0))
        assertEquals(240.0, state.durationSec, 0.0)
    }

    @Test
    fun `track change keeps the playing phase - auto-advance is not a stop`() {
        val state = playing().onEvent(MusicPlayerEvent.TrackChanged(1, 187.0))
        assertEquals(MusicPlayerPhase.Playing, state.phase)
    }

    // --- ended ---

    @Test
    fun `ended pins the position to the duration and drops the play intent`() {
        val state = playing(duration = 240.0, position = 100.0)
            .onEvent(MusicPlayerEvent.Ended)
        assertEquals(MusicPlayerPhase.Ended, state.phase)
        assertEquals(240.0, state.currentTimeSec, 0.0)
        assertTrue(!state.playWhenReady)
    }

    @Test
    fun `events after ended change nothing`() {
        val ended = playing().onEvent(MusicPlayerEvent.Ended)
        assertEquals(ended, ended.onEvent(MusicPlayerEvent.Time(5.0, 240.0)))
        assertEquals(ended, ended.onEvent(MusicPlayerEvent.IsPlayingChanged(true)))
        assertEquals(ended, ended.onEvent(MusicPlayerEvent.TrackChanged(0, 240.0)))
    }

    // --- errors ---

    @Test
    fun `the first error is sticky`() {
        val failed = playing().onEvent(MusicPlayerEvent.Error("Playback failed"))
        assertEquals(MusicPlayerPhase.Error, failed.phase)
        assertEquals("Playback failed", failed.errorMessage)

        val after = failed
            .onEvent(MusicPlayerEvent.Error("Second failure"))
            .onEvent(MusicPlayerEvent.Ready(240.0))
            .onEvent(MusicPlayerEvent.IsPlayingChanged(true))
            .onEvent(MusicPlayerEvent.TrackChanged(2, 100.0))
            .onEvent(MusicPlayerEvent.Ended)
        assertEquals(MusicPlayerPhase.Error, after.phase)
        assertEquals("Playback failed", after.errorMessage)
    }

    // --- seeking ---

    @Test
    fun `seek target clamps into the playable range`() {
        val state = playing(duration = 240.0, position = 5.0)
        assertEquals(0.0, state.seekTarget(-10.0), 0.0)
        assertEquals(15.0, state.seekTarget(10.0), 0.0)
    }

    @Test
    fun `an applied seek moves the bar optimistically`() {
        val state = playing(duration = 240.0, position = 30.0)
            .onSeekApplied(90.0)
        assertEquals(90.0, state.currentTimeSec, 0.0)
    }

    // --- announcements ---

    @Test
    fun `announcements carry the track title so auto-advance re-announces`() {
        assertEquals(
            "Playing: Song Two",
            musicPlayerAnnouncement(MusicPlayerPhase.Playing, "Song Two"),
        )
        assertEquals(
            "Paused: Song Two",
            musicPlayerAnnouncement(MusicPlayerPhase.Paused, "Song Two"),
        )
        assertEquals("Loading", musicPlayerAnnouncement(MusicPlayerPhase.Loading, "Song"))
        assertEquals("Buffering", musicPlayerAnnouncement(MusicPlayerPhase.Buffering, "Song"))
        assertNull(musicPlayerAnnouncement(MusicPlayerPhase.Ended, "Song"))
        assertNull(musicPlayerAnnouncement(MusicPlayerPhase.Error, "Song"))
    }
}
