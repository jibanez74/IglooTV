package com.igloo.blindpenguincoder.playback.model

/**
 * The numeric rules every player reducer shares (design-system.md section 11.8). They are
 * arithmetic, not policy: each machine still decides *when* to apply them, which is where the
 * movie's resume prompt, the music queue's per-track reset, and the trailer's embed states
 * genuinely differ. Named distinctly from the reducers' own one-argument helpers so neither
 * shadows the other at a call site.
 */

/** A seek target clamped into the playable range; an unknown duration only pins the floor. */
internal fun clampSecondsToDuration(seconds: Double, durationSec: Double): Double = when {
    durationSec > 0.0 -> seconds.coerceIn(0.0, durationSec)
    else -> seconds.coerceAtLeast(0.0)
}

/**
 * A known duration never shrinks back to zero: a seek bar that collapses mid-play reads as a
 * crash, and the engines report `TIME_UNSET` as 0.0 whenever a timeline is between states.
 */
internal fun nonShrinkingDuration(incoming: Double, known: Double): Double =
    if (incoming > 0.0) incoming else known

/** Where a finished playhead rests: the end of a known duration, else wherever it stopped. */
internal fun endedPositionSec(currentSec: Double, durationSec: Double): Double =
    durationSec.takeIf { it > 0.0 } ?: currentSec
