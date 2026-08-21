package com.igloo.blindpenguincoder.playback.media3

import android.view.View
import com.igloo.blindpenguincoder.playback.model.MoviePlayerEvent
import kotlinx.coroutines.flow.SharedFlow

/**
 * The movie player's engine boundary, cut exactly at ExoPlayer so everything above it —
 * reducer, chrome, focus, Back — runs against a fake in tests without touching a decoder, and
 * so another engine (VLC was named as a possible future) can slot in behind the same seam.
 * All members are main-thread only; [events] replays what a late collector missed.
 */
interface MoviePlayerEngine {
    val events: SharedFlow<MoviePlayerEvent>

    /** The video surface to mount, or null when the engine draws nothing (fakes). */
    fun surface(): View?

    /**
     * The resume decision was made: prepare the stream and start playing, from
     * [startPositionSec] or from the beginning when null. Called exactly once.
     */
    fun startPlayback(startPositionSec: Double?)

    fun play()
    fun pause()

    fun seekTo(seconds: Double)

    /** [com.igloo.blindpenguincoder.playback.model.TrackOption.id] from the audio menu. */
    fun selectAudioTrack(optionId: String)

    /** A subtitle menu id, or null for "None". */
    fun selectSubtitleTrack(optionId: String?)

    /** Host lifecycle went to the background: stop playback — a TV in standby must be silent. */
    fun onHostPaused()

    /** Host lifecycle returned; playback stays paused for the user to resume. */
    fun onHostResumed()

    /** Tear down the player; the engine is unusable afterwards. */
    fun release()
}
