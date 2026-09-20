package com.igloo.blindpenguincoder.playback.youtube

import android.view.View
import kotlinx.coroutines.flow.SharedFlow

/** What the embed reports upward; each maps 1:1 onto a [TrailerPlayerState] transition. */
sealed interface TrailerPlayerEvent {
    data class Ready(val durationSec: Double) : TrailerPlayerEvent
    data class StateChange(val code: Int) : TrailerPlayerEvent
    data class Error(val code: Int) : TrailerPlayerEvent
    data class Time(val currentSec: Double, val durationSec: Double) : TrailerPlayerEvent
}

fun TrailerPlayerState.onEvent(event: TrailerPlayerEvent): TrailerPlayerState = when (event) {
    is TrailerPlayerEvent.Ready -> onReady(event.durationSec)
    is TrailerPlayerEvent.StateChange -> onYtStateChange(event.code)
    is TrailerPlayerEvent.Error -> onError(event.code)
    is TrailerPlayerEvent.Time -> onTime(event.currentSec, event.durationSec)
}

/**
 * The trailer player's engine boundary, cut exactly at the JS bridge so everything above it —
 * reducer, chrome, focus, Back — runs against a fake in tests without touching youtube.com.
 * All members are main-thread only; [events] replays what a late collector missed, because the
 * real engine starts loading before the screen's collector attaches.
 */
interface TrailerPlayerEngine {
    val events: SharedFlow<TrailerPlayerEvent>

    /** The video surface to mount, or null when the engine draws nothing (fakes). */
    fun surface(): View?

    fun play()
    fun pause()

    fun seekTo(seconds: Double)

    /** Host lifecycle went to the background: stop playback — a TV in standby must be silent. */
    fun onHostPaused()

    /** Host lifecycle returned; playback stays paused for the user to resume. */
    fun onHostResumed()

    /** Tear down the surface; the engine is unusable afterwards. */
    fun release()
}
