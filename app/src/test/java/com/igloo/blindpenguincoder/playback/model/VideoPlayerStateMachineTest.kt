package com.igloo.blindpenguincoder.playback.model

import com.igloo.blindpenguincoder.data.model.PlaybackMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPlayerStateMachineTest {

    private val loading = VideoPlayerState()

    private fun playing(duration: Double = 600.0, position: Double = 100.0) = loading
        .onEvent(VideoPlayerEvent.PlayWhenReadyChanged(true))
        .onEvent(VideoPlayerEvent.Ready(duration))
        .onEvent(VideoPlayerEvent.IsPlayingChanged(true))
        .onEvent(VideoPlayerEvent.Time(position, duration))

    // --- resume ---

    @Test
    fun `resume choice moves awaiting-resume to loading and nothing else`() {
        val awaiting = VideoPlayerState(phase = VideoPlayerPhase.AwaitingResume)
        assertEquals(VideoPlayerPhase.Loading, awaiting.onResumeChosen().phase)
        assertEquals(VideoPlayerPhase.Playing, playing().onResumeChosen().phase)
    }

    @Test
    fun `buffering does not disturb the resume prompt`() {
        val awaiting = VideoPlayerState(phase = VideoPlayerPhase.AwaitingResume)
        assertEquals(
            VideoPlayerPhase.AwaitingResume,
            awaiting.onEvent(VideoPlayerEvent.Buffering).phase,
        )
    }

    // --- ready / play / pause / buffering ---

    @Test
    fun `ready lands on paused until playback actually starts`() {
        val state = loading.onEvent(VideoPlayerEvent.Ready(3600.0))
        assertTrue(state.ready)
        assertEquals(VideoPlayerPhase.Paused, state.phase)
        assertEquals(3600.0, state.durationSec, 0.0)
    }

    @Test
    fun `is-playing true and false toggle playing and paused`() {
        val state = playing()
        assertEquals(VideoPlayerPhase.Playing, state.phase)
        assertEquals(
            VideoPlayerPhase.Paused,
            state.onEvent(VideoPlayerEvent.IsPlayingChanged(false)).phase,
        )
    }

    @Test
    fun `is-playing false does not downgrade buffering`() {
        val state = playing().onEvent(VideoPlayerEvent.Buffering)
        assertEquals(VideoPlayerPhase.Buffering, state.phase)
        assertEquals(
            VideoPlayerPhase.Buffering,
            state.onEvent(VideoPlayerEvent.IsPlayingChanged(false)).phase,
        )
    }

    @Test
    fun `buffering preserves play intent and pause cancels pending autoplay`() {
        val buffering = playing().onEvent(VideoPlayerEvent.Buffering)
        assertTrue(buffering.playWhenReady)

        val pausedIntent = buffering.onEvent(VideoPlayerEvent.PlayWhenReadyChanged(false))
        assertEquals(VideoPlayerPhase.Buffering, pausedIntent.phase)
        assertTrue(!pausedIntent.playWhenReady)

        val ready = pausedIntent.onEvent(VideoPlayerEvent.Ready(600.0))
        assertEquals(VideoPlayerPhase.Paused, ready.phase)
        assertTrue(!ready.playWhenReady)
    }

    @Test
    fun `ready after a mid-play rebuffer returns to paused until play resumes`() {
        val state = playing()
            .onEvent(VideoPlayerEvent.Buffering)
            .onEvent(VideoPlayerEvent.Ready(600.0))
        assertEquals(VideoPlayerPhase.Paused, state.phase)
        assertEquals(
            VideoPlayerPhase.Playing,
            state.onEvent(VideoPlayerEvent.IsPlayingChanged(true)).phase,
        )
    }

    // --- duration and time ---

    @Test
    fun `a known duration never shrinks back to zero`() {
        val state = playing(duration = 600.0)
            .onEvent(VideoPlayerEvent.Time(150.0, 0.0))
        assertEquals(600.0, state.durationSec, 0.0)
        assertEquals(150.0, state.currentTimeSec, 0.0)
    }

    @Test
    fun `time clamps a negative position to zero`() {
        assertEquals(
            0.0,
            playing().onEvent(VideoPlayerEvent.Time(-3.0, 600.0)).currentTimeSec,
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
        val state = playing(duration = 600.0, position = 590.0).onEvent(VideoPlayerEvent.Ended)
        assertEquals(VideoPlayerPhase.Ended, state.phase)
        assertEquals(600.0, state.currentTimeSec, 0.0)
    }

    @Test
    fun `ended keeps the last real position when every duration is unknown`() {
        val state = VideoPlayerState(currentTimeSec = 123.0)
            .onEvent(VideoPlayerEvent.Ended)
        assertEquals(123.0, state.currentTimeSec, 0.0)
    }

    @Test
    fun `ended uses the request duration seeded into state`() {
        val state = VideoPlayerState(currentTimeSec = 123.0, durationSec = 600.0)
            .onEvent(VideoPlayerEvent.Ended)
        assertEquals(600.0, state.currentTimeSec, 0.0)
    }

    @Test
    fun `events after ended change nothing`() {
        val ended = playing().onEvent(VideoPlayerEvent.Ended)
        assertEquals(ended, ended.onEvent(VideoPlayerEvent.IsPlayingChanged(true)))
        assertEquals(ended, ended.onEvent(VideoPlayerEvent.Buffering))
        assertEquals(ended, ended.onEvent(VideoPlayerEvent.Time(10.0, 600.0)))
        assertEquals(ended, ended.onSeekApplied(10.0))
    }

    // --- errors ---

    @Test
    fun `the first error is sticky`() {
        val failed = playing().onEvent(VideoPlayerEvent.Error("The stream stopped."))
        assertEquals(VideoPlayerPhase.Error, failed.phase)
        assertEquals("The stream stopped.", failed.errorMessage)

        val after = failed
            .onEvent(VideoPlayerEvent.Error("Something else."))
            .onEvent(VideoPlayerEvent.IsPlayingChanged(true))
            .onEvent(VideoPlayerEvent.Ready(600.0))
            .onEvent(VideoPlayerEvent.Ended)
        assertEquals(VideoPlayerPhase.Error, after.phase)
        assertEquals("The stream stopped.", after.errorMessage)
    }

    @Test
    fun `an error can still follow ended`() {
        val state = playing().onEvent(VideoPlayerEvent.Ended)
            .onEvent(VideoPlayerEvent.Error("The stream stopped."))
        assertEquals(VideoPlayerPhase.Error, state.phase)
    }

    // --- tracks ---

    @Test
    fun `tracks changed replaces both menus and survives every phase`() {
        val audio = listOf(TrackOption("0:0", "English · 5.1 surround", selected = true))
        val subtitles = listOf(TrackOption("1:0", "English", selected = false))
        val state = playing().onEvent(VideoPlayerEvent.TracksChanged(audio, subtitles))
        assertEquals(audio, state.audioOptions)
        assertEquals(subtitles, state.subtitleOptions)

        // Even under an error the menus keep their last real content.
        val failed = state.onEvent(VideoPlayerEvent.Error("boom"))
            .onEvent(VideoPlayerEvent.TracksChanged(emptyList(), emptyList()))
        assertTrue(failed.audioOptions.isEmpty())
    }

    // --- quality options ---

    @Test
    fun `quality options replace the menu and the selected mark follows re-emits`() {
        val first = listOf(
            TrackOption("Direct", "Original quality — plays the file as-is", selected = true),
            TrackOption("Remux", "Original quality — audio adjusted", selected = false),
        )
        val state = playing().onEvent(VideoPlayerEvent.QualityOptionsChanged(first, PlaybackMode.Direct))
        assertEquals(first, state.qualityOptions)

        val switched = first.map { it.copy(selected = it.id == "Remux") }
        assertEquals(
            switched,
            state.onEvent(VideoPlayerEvent.QualityOptionsChanged(switched, PlaybackMode.Remux)).qualityOptions,
        )
    }

    // --- status narration ---

    @Test
    fun `a status message shows while waiting and ready clears it`() {
        val waiting = loading.onEvent(VideoPlayerEvent.StatusMessage("Waiting for the server to free up…"))
        assertEquals("Waiting for the server to free up…", waiting.statusMessage)

        val ready = waiting.onEvent(VideoPlayerEvent.Ready(3600.0))
        assertEquals(null, ready.statusMessage)
    }

    @Test
    fun `a null status message clears the narration`() {
        val cleared = loading
            .onEvent(VideoPlayerEvent.StatusMessage("Reconnecting to the stream…"))
            .onEvent(VideoPlayerEvent.StatusMessage(null))
        assertEquals(null, cleared.statusMessage)
    }

    @Test
    fun `status messages never disturb a terminal phase`() {
        val failed = playing().onEvent(VideoPlayerEvent.Error("boom"))
            .onEvent(VideoPlayerEvent.StatusMessage("Waiting…"))
        assertEquals(null, failed.statusMessage)

        val ended = playing().onEvent(VideoPlayerEvent.Ended)
            .onEvent(VideoPlayerEvent.StatusMessage("Waiting…"))
        assertEquals(null, ended.statusMessage)
    }

    @Test
    fun `detailed wait messages outrank generic loading and buffering announcements`() {
        assertEquals(
            "Waiting for the server to free up…",
            videoPlayerAnnouncement(
                VideoPlayerPhase.Loading,
                "Waiting for the server to free up…",
                "Heat",
                "movie",
            ),
        )
        assertEquals(
            "Reconnecting to the stream…",
            videoPlayerAnnouncement(
                VideoPlayerPhase.Buffering,
                "Reconnecting to the stream…",
                "Heat",
                "movie",
            ),
        )
    }

    @Test
    fun `wait announcements fall back to the generic phase text`() {
        assertEquals(
            "Loading movie",
            videoPlayerAnnouncement(VideoPlayerPhase.Loading, null, "Heat", "movie"),
        )
        assertEquals(
            "Buffering",
            videoPlayerAnnouncement(VideoPlayerPhase.Buffering, null, "Heat", "movie"),
        )
        assertEquals(
            "Loading episode",
            videoPlayerAnnouncement(VideoPlayerPhase.Loading, null, "Severance", "episode"),
        )
    }
}
