package com.igloo.blindpenguincoder.playback.model

import kotlin.math.ceil

/** How long before an episode's end the Up Next card appears (docs/design-system.md section 11.8). */
const val UP_NEXT_LEAD_SEC = 15.0

/** The Up Next card while it belongs on screen: how many whole seconds the episode has left. */
data class UpNextOffer(val secondsLeft: Int)

/** Seconds still to play, or null while the duration is unknown. */
fun remainingSec(durationSec: Double, currentTimeSec: Double): Double? {
    if (!durationSec.isFinite() || durationSec <= 0.0 || !currentTimeSec.isFinite()) return null
    return (durationSec - currentTimeSec).coerceAtLeast(0.0)
}

/**
 * Whether the Up Next card belongs on screen, and what it counts. Null is no card: the viewer
 * dismissed it, the picture is not yet or no longer moving (Loading, the resume prompt, an error),
 * the duration is unknown, or more than [leadSec] remain. Ended always offers, with nothing left
 * to count, so an episode whose duration never arrived still gets its hand-off and the held frame
 * has something to say while the next episode prepares.
 */
fun upNextOffer(
    state: VideoPlayerState,
    dismissed: Boolean,
    leadSec: Double = UP_NEXT_LEAD_SEC,
): UpNextOffer? {
    if (dismissed) return null
    return when (state.phase) {
        VideoPlayerPhase.Ended -> UpNextOffer(secondsLeft = 0)
        VideoPlayerPhase.Playing, VideoPlayerPhase.Paused, VideoPlayerPhase.Buffering -> {
            val remaining = remainingSec(state.durationSec, state.currentTimeSec) ?: return null
            if (remaining > leadSec) null else UpNextOffer(secondsLeft = ceil(remaining).toInt())
        }
        VideoPlayerPhase.AwaitingResume, VideoPlayerPhase.Loading, VideoPlayerPhase.Error -> null
    }
}

/** True once the playhead sits more than [leadSec] from the end: an earlier dismissal no longer applies. */
fun upNextOutsideLead(state: VideoPlayerState, leadSec: Double = UP_NEXT_LEAD_SEC): Boolean =
    remainingSec(state.durationSec, state.currentTimeSec)?.let { it > leadSec } == true
