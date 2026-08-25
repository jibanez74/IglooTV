package com.igloo.blindpenguincoder.playback.hls

import kotlin.math.floor
import kotlin.math.max

/**
 * Every timing and retry decision the HLS session machinery makes, as pure functions so the
 * rules are JVM-testable and live in one place. The numbers mirror the Igloo web client, whose
 * budgets are tuned against the server's own timeouts (120s segment long-poll, 5-minute idle
 * TTL, `Retry-After` on capacity).
 */

/** Resuming over HLS rewinds a little so the viewer re-enters on context, not mid-sentence. */
const val HLS_RESUME_REWIND_BUFFER_SEC = 10.0

/** Seeks this far past the current position leave the produced window — rebase instead. */
const val HLS_FORWARD_REBASE_THRESHOLD_SEC = 120.0

/** A paused or fully buffered player stops fetching; this keeps the 5-minute TTL refreshed. */
const val HLS_KEEPALIVE_INTERVAL_MS = 120_000L

/** Must exceed the server's 120s segment long-poll or every not-yet-encoded segment "fails". */
const val HLS_SEGMENT_READ_TIMEOUT_MS = 150_000

const val HLS_CAPACITY_RETRY_MAX_ATTEMPTS = 6
private const val HLS_CAPACITY_RETRY_DEFAULT_DELAY_SEC = 5

const val HLS_SESSION_LOST_MAX_ATTEMPTS = 3
private const val HLS_SESSION_LOST_MIN_DELAY_MS = 2_000L

/** Session start second for a resume at [requestedSec], rewound and clamped at zero. */
fun hlsResumeStartSec(requestedSec: Double): Int =
    floor(max(0.0, requestedSec - HLS_RESUME_REWIND_BUFFER_SEC)).toInt()

/**
 * Whether a seek to [targetSec] needs a new session. Before the actual start the media simply
 * does not exist in this session; far ahead of the playhead the segments may not be produced
 * yet (remux) or would force a long transcode catch-up. Near-forward seeks ride the player and
 * the server's segment long-poll.
 */
fun shouldRebaseHlsSeek(targetSec: Double, actualStartSec: Double, currentSec: Double): Boolean =
    targetSec < actualStartSec || targetSec > currentSec + HLS_FORWARD_REBASE_THRESHOLD_SEC

/**
 * Delay before capacity-retry number [attempt] (1-based), honoring the server's `Retry-After`;
 * null = give up.
 */
fun capacityRetryDelayMs(attempt: Int, retryAfterSec: Int?): Long? {
    if (attempt > HLS_CAPACITY_RETRY_MAX_ATTEMPTS) return null
    val seconds = retryAfterSec?.takeIf { it > 0 } ?: HLS_CAPACITY_RETRY_DEFAULT_DELAY_SEC
    return seconds * 1000L
}

/** Delay before session-lost recreate number [attempt] (1-based); null = give up. */
fun sessionLostRetryDelayMs(attempt: Int): Long? {
    if (attempt > HLS_SESSION_LOST_MAX_ATTEMPTS) return null
    return HLS_SESSION_LOST_MIN_DELAY_MS
}

/**
 * Retry delay for a failed Media3 segment/playlist load, or null to let the default policy
 * decide. Only 503 gets special treatment: it means "not encoded yet" or "no capacity", both
 * worth patient retries honoring `Retry-After` rather than the default three quick attempts.
 */
fun hlsLoadRetryDelayMs(responseCode: Int?, retryAfterSec: Int?, errorCount: Int): Long? {
    if (responseCode != 503) return null
    return capacityRetryDelayMs(errorCount, retryAfterSec)
}

/**
 * The complete Media3 retry decision. The public three-argument rule above remains the 503
 * override; non-503 failures retain Media3's own delay only through its normal retry count,
 * and a null default delay preserves Media3's immediate fail-fast classifications.
 */
internal fun hlsLoadRetryDelayMs(
    responseCode: Int?,
    retryAfterSec: Int?,
    errorCount: Int,
    defaultRetryCount: Int,
    defaultRetryDelayMs: Long?,
): Long? = when {
    responseCode == 503 -> capacityRetryDelayMs(errorCount, retryAfterSec)
    defaultRetryDelayMs == null -> null
    errorCount > defaultRetryCount -> null
    else -> defaultRetryDelayMs
}
