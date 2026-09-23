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

/**
 * Keepalive cadence for the whole session. A paused or fully buffered player stops fetching,
 * and this refresh is what carries the server's 5-minute idle TTL through those stretches.
 */
const val HLS_KEEPALIVE_INTERVAL_MS = 120_000L

/** Must exceed the server's 120s segment long-poll or every not-yet-encoded segment "fails". */
const val HLS_SEGMENT_READ_TIMEOUT_MS = 150_000

const val HLS_CAPACITY_RETRY_MAX_ATTEMPTS = 6
private const val HLS_CAPACITY_RETRY_DEFAULT_DELAY_SEC = 5

/**
 * Wall-clock ceiling on one [HlsSessionController.start], attempt budgets included. The attempt
 * counts alone bound nothing useful: a server that accepts connections but never answers spends
 * the manifest request timeout on every attempt, so six attempts can hold the viewer for minutes
 * on a loading screen whose only escape is Back.
 */
const val HLS_START_TOTAL_BUDGET_MS = 90_000L

const val HLS_SESSION_LOST_MAX_ATTEMPTS = 3
private const val HLS_SESSION_LOST_MIN_DELAY_MS = 2_000L

/**
 * A lost session this long after the previous recovery starts a new incident with a full
 * budget. Resetting on READY instead let a failure that repeats at the same point in the film
 * recover forever, because every recreated session reaches READY before it fails again.
 */
const val HLS_SESSION_LOST_INCIDENT_WINDOW_MS = 60_000L

/**
 * The server marks the segment 404 it sends once FFmpeg has finished cleanly without writing
 * the file. That is the end of the media, not a lost session: the synthesized transcode
 * playlist can list one or two segments more than FFmpeg writes when a source's audio outlasts
 * its video.
 */
private const val HLS_SEGMENT_STATUS_HEADER = "X-Igloo-Segment"
private const val HLS_SEGMENT_STATUS_PAST_END = "past-end"

private val MOVIE_HLS_REQUEST_PATH = Regex(
    "^/api/movies/[^/]+/hls/[^/]+/(?:playlist\\.m3u8|init\\.mp4|segment_[0-9]+\\.m4s)$",
)

private val MOVIE_HLS_SEGMENT_PATH = Regex("^/api/movies/[^/]+/hls/[^/]+/segment_[0-9]+\\.m4s$")

/** True only for the movie HLS routes whose assets are owned by an ephemeral FFmpeg session. */
fun isMovieHlsRequestPath(path: String?): Boolean =
    path != null && MOVIE_HLS_REQUEST_PATH.matches(path)

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
 * Whether a mid-play load failure should recreate the session in place rather than surface as
 * a terminal error. A 404 from an ephemeral movie HLS playlist/init/segment means "the
 * server-side session evaporated" (idle eviction, restart). A 500 on a segment means FFmpeg
 * died partway through, and the server replaces a failed session on the next manifest
 * request. Sideloaded WebVTT and unrelated endpoints retain ordinary HTTP handling.
 * [recoveries] is how many recreations this incident has already run (see
 * [sessionLostRecoveriesAt]), so a genuinely missing movie cannot loop forever.
 */
fun shouldRecoverLostHlsSession(
    responseCode: Int?,
    requestPath: String?,
    recoveries: Int,
): Boolean = recoveries < HLS_SESSION_LOST_MAX_ATTEMPTS &&
    when (responseCode) {
        404 -> isMovieHlsRequestPath(requestPath)
        500 -> requestPath != null && MOVIE_HLS_SEGMENT_PATH.matches(requestPath)
        else -> false
    }

/** The recoveries a lost session at [nowMs] counts against, given the last one at [lastRecoveryAtMs]. */
fun sessionLostRecoveriesAt(recoveries: Int, lastRecoveryAtMs: Long, nowMs: Long): Int =
    if (nowMs - lastRecoveryAtMs >= HLS_SESSION_LOST_INCIDENT_WINDOW_MS) 0 else recoveries

/** Whether a load failure is the server's end-of-media 404 rather than a lost session. */
fun isPastEndHlsSegment(
    responseCode: Int?,
    requestPath: String?,
    headerFields: Map<out String?, List<String>>?,
): Boolean = responseCode == 404 &&
    requestPath != null && MOVIE_HLS_SEGMENT_PATH.matches(requestPath) &&
    headerValueFrom(headerFields, HLS_SEGMENT_STATUS_HEADER) == HLS_SEGMENT_STATUS_PAST_END

/**
 * `Retry-After` seconds out of an HTTP header map. HttpURLConnection's map is case-preserving
 * and, despite its non-null declared key type, holds the status line under a null key — so the
 * key stays nullable here and a tolerant scan beats a direct get; null for a missing or
 * non-numeric header.
 */
fun retryAfterSecondsFrom(headerFields: Map<out String?, List<String>>?): Int? =
    headerValueFrom(headerFields, "Retry-After")?.toIntOrNull()

private fun headerValueFrom(headerFields: Map<out String?, List<String>>?, name: String): String? =
    headerFields?.entries
        ?.firstOrNull { it.key?.equals(name, ignoreCase = true) == true }
        ?.value?.firstOrNull()

/**
 * Retry delay for a failed Media3 segment/playlist load, or null to fail the load. Only 503 gets
 * special treatment: it means "not encoded yet" or "no capacity", both worth patient retries
 * honoring `Retry-After` rather than Media3's three quick attempts. Non-503 failures retain
 * Media3's own delay only through its normal retry count, and a null default delay preserves
 * Media3's immediate fail-fast classifications.
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
