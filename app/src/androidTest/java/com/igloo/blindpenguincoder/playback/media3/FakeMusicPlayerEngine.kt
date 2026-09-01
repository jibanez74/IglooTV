package com.igloo.blindpenguincoder.playback.media3

import com.igloo.blindpenguincoder.playback.model.MusicPlayerEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The test-side [MusicPlayerEngine]: records the commands the chrome sends and lets a test emit
 * the events a real ExoPlayer would, so player suites run without touching a decoder or the
 * network. The [FakeMoviePlayerEngine] recipe minus the movie-only members (no surface, no
 * track/quality selection), plus the queue commands the music seam adds.
 */
class FakeMusicPlayerEngine(
    /**
     * The queue's wire durations, so the startup [MusicPlayerEvent.TrackChanged] carries the
     * same number the real engine's `durationSec()` falls back to. Empty means "unknown",
     * which the reducer treats as "keep what you have".
     */
    private val trackDurationsSec: List<Double> = emptyList(),
) : MusicPlayerEngine {

    private val _events = MutableSharedFlow<MusicPlayerEvent>(replay = 64)
    override val events: SharedFlow<MusicPlayerEvent> = _events

    val commands = mutableListOf<String>()

    /**
     * The transport traffic alone. Lifecycle notifications are excluded because registering a
     * lifecycle observer replays the current state — every screen mount logs a "hostResumed"
     * that transport assertions have no business being coupled to.
     */
    val playbackCommands: List<String>
        get() = commands.filterNot { it == "hostPaused" || it == "hostResumed" }

    var released = false
        private set
    var releaseCount = 0
        private set
    private var hostActive = true

    override fun startPlayback(
        startTrackIndex: Int,
        startPositionSec: Double,
        initialPlayWhenReady: Boolean,
    ) {
        if (released) return
        commands += "start:$startTrackIndex:$startPositionSec:$initialPlayWhenReady"
        emit(MusicPlayerEvent.PlayWhenReadyChanged(initialPlayWhenReady))
        // Setting a playlist is itself an item transition, so the real engine reports the
        // queue's own start index before a frame plays. The fake must too, or the suite never
        // exercises the event order that a restored playhead has to survive.
        emit(
            MusicPlayerEvent.TrackChanged(
                index = startTrackIndex,
                durationSec = trackDurationsSec.getOrNull(startTrackIndex) ?: 0.0,
            ),
        )
    }

    override fun play() {
        if (released || !hostActive) return
        commands += "play"
        emit(MusicPlayerEvent.PlayWhenReadyChanged(true))
    }

    override fun pause() {
        if (released) return
        commands += "pause"
        emit(MusicPlayerEvent.PlayWhenReadyChanged(false))
    }

    override fun seekTo(seconds: Double) {
        if (released) return
        commands += "seek:$seconds"
    }

    override fun skipToNext() {
        if (released) return
        commands += "next"
    }

    override fun skipToPrevious() {
        if (released) return
        commands += "previous"
    }

    override fun onHostPaused() {
        if (released) return
        hostActive = false
        commands += "hostPaused"
        emit(MusicPlayerEvent.PlayWhenReadyChanged(false))
    }

    override fun onHostResumed() {
        if (released) return
        hostActive = true
        commands += "hostResumed"
    }

    override fun release() {
        if (released) return
        releaseCount++
        released = true
    }

    fun emit(event: MusicPlayerEvent) {
        check(_events.tryEmit(event)) { "event buffer full" }
    }
}
