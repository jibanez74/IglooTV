package com.igloo.blindpenguincoder.playback.youtube

import com.igloo.blindpenguincoder.playback.model.clampSecondsToDuration
import com.igloo.blindpenguincoder.playback.model.nonShrinkingDuration

/** The trailer player's phases; the chrome renders exactly one of these at a time. */
enum class TrailerPhase { Loading, Playing, Paused, Buffering, Ended, Error }

/**
 * The trailer player's render-ready state, reduced purely from engine events so the whole
 * machine is testable without a WebView. Two rules bind every transition: an [TrailerPhase.Error]
 * is sticky — later events never downgrade the first failure the user saw — and a known
 * [durationSec] never shrinks back to zero, because the IFrame API reports 0 while buffering
 * and a seek bar that collapses mid-play reads as a crash.
 */
data class TrailerPlayerState(
    val phase: TrailerPhase = TrailerPhase.Loading,
    val ready: Boolean = false,
    val playWhenReady: Boolean = true,
    val currentTimeSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val errorMessage: String? = null,
) {
    /** The player reached ready; autoplay's PLAYING event moves the phase, not this. */
    fun onReady(durationSec: Double): TrailerPlayerState = when (phase) {
        TrailerPhase.Error -> this
        else -> copy(ready = true, durationSec = keptDuration(durationSec))
    }

    /**
     * A YT.PlayerState code from the embed: -1 unstarted and 5 cued stay [TrailerPhase.Loading];
     * 1 plays, 2 pauses, 3 buffers, 0 ends. Unknown codes keep the current phase — a new code is
     * YouTube's business, not a reason to disturb what the user sees.
     */
    fun onYtStateChange(code: Int): TrailerPlayerState = when {
        phase == TrailerPhase.Error -> this
        else -> when (code) {
            -1, 5 -> copy(phase = TrailerPhase.Loading)
            0 -> copy(phase = TrailerPhase.Ended, playWhenReady = false)
            1 -> copy(phase = TrailerPhase.Playing, playWhenReady = true)
            2 -> copy(phase = TrailerPhase.Paused, playWhenReady = false)
            3 -> copy(phase = TrailerPhase.Buffering)
            else -> this
        }
    }

    fun onPlayRequested(): TrailerPlayerState = when (phase) {
        TrailerPhase.Error, TrailerPhase.Ended -> this
        else -> copy(playWhenReady = true)
    }

    fun onPauseRequested(): TrailerPlayerState = when (phase) {
        TrailerPhase.Error, TrailerPhase.Ended -> this
        else -> copy(playWhenReady = false)
    }

    /** An embed error code; the first failure wins and later codes never replace its message. */
    fun onError(code: Int): TrailerPlayerState = when (phase) {
        TrailerPhase.Error -> this
        else -> copy(phase = TrailerPhase.Error, errorMessage = errorMessage(code))
    }

    fun onTime(currentSec: Double, durationSec: Double): TrailerPlayerState = when (phase) {
        TrailerPhase.Error -> this
        else -> copy(
            currentTimeSec = currentSec.coerceAtLeast(0.0),
            durationSec = keptDuration(durationSec),
        )
    }

    /** The Kotlin-side ready watchdog fired; a player that already reached ready is left alone. */
    fun onWatchdogExpired(): TrailerPlayerState = when {
        ready || phase == TrailerPhase.Error -> this
        else -> copy(
            phase = TrailerPhase.Error,
            errorMessage = "The video player took too long to load.",
        )
    }

    /**
     * Where a relative seek lands, clamped into the playable range. The engine and the bar are
     * given the same value: a target past the end would make the embed report Ended, and the
     * player auto-closes on Ended — a forward seek near the end must not quietly exit.
     */
    fun seekTarget(deltaSec: Double): Double = clampToPlayable(currentTimeSec + deltaSec)

    /** An optimistic seek: the bar moves under a held key without waiting for the next tick. */
    fun onSeekApplied(targetSec: Double): TrailerPlayerState = when (phase) {
        TrailerPhase.Error -> this
        else -> copy(currentTimeSec = clampToPlayable(targetSec))
    }

    private fun clampToPlayable(seconds: Double): Double =
        clampSecondsToDuration(seconds, durationSec)

    private fun keptDuration(incoming: Double): Double =
        nonShrinkingDuration(incoming, durationSec)

    private fun errorMessage(code: Int): String = when (code) {
        API_LOAD_TIMEOUT -> "The YouTube player took too long to load."
        2 -> "This video's YouTube id is invalid."
        5 -> "YouTube's player hit a playback error."
        100 -> "This video was not found on YouTube."
        // The embed-restriction family: the classic pair plus the newer codes YouTube's embed
        // returns since 2024ish (152 observed on-device when it rejects the embedding context).
        101, 150, 152, 153 -> "YouTube doesn't allow this video to play outside youtube.com."
        else -> "The trailer could not be played."
    }

    companion object {
        /** Engine-synthesized code for the in-page IFrame API script never loading. */
        const val API_LOAD_TIMEOUT = -2
    }
}
