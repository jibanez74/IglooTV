package com.igloo.blindpenguincoder.playback.youtube

import android.view.View
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The test-side [TrailerPlayerEngine]: records the commands the chrome sends and lets a test
 * emit the events a real embed would, so player suites run without touching youtube.com.
 */
class FakeTrailerPlayerEngine : TrailerPlayerEngine {

    private val _events = MutableSharedFlow<TrailerPlayerEvent>(replay = 64)
    override val events: SharedFlow<TrailerPlayerEvent> = _events

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

    override fun surface(): View? = null

    override fun play() {
        commands += "play"
    }

    override fun pause() {
        commands += "pause"
    }

    override fun seekTo(seconds: Double) {
        commands += "seek:$seconds"
    }

    override fun onHostPaused() {
        commands += "hostPaused"
    }

    override fun onHostResumed() {
        commands += "hostResumed"
    }

    override fun release() {
        released = true
    }

    fun emit(event: TrailerPlayerEvent) {
        check(_events.tryEmit(event)) { "event buffer full" }
    }
}
