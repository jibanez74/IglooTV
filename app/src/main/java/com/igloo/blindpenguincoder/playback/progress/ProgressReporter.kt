package com.igloo.blindpenguincoder.playback.progress

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.MovieWatchProgressUpdateData
import com.igloo.blindpenguincoder.data.model.UpdateMovieWatchProgressRequest
import java.util.UUID

/**
 * The save-timing rule (AGENTS.md, web parity): a save needs at least [MIN_PLAYED_SEC] of
 * actual playback, a position past [MIN_POSITION_SEC] (the server's own continue-watching
 * floor), and [SAVE_INTERVAL_SEC] since the previous save. In practice the first save lands
 * around 30 seconds of real playback. Pure, for exhaustive table tests.
 */
internal fun shouldSaveProgress(
    playedSec: Double,
    positionSec: Double,
    durationSec: Double,
    secondsSinceLastSave: Double?,
): Boolean = playedSec >= MIN_PLAYED_SEC &&
    positionSec >= MIN_POSITION_SEC &&
    durationSec > 0.0 &&
    (secondsSinceLastSave == null || secondsSinceLastSave >= SAVE_INTERVAL_SEC)

/** The exit write uses the same actual-playback floor as cadence writes. */
internal fun shouldSaveFinalProgress(
    playedSec: Double,
    positionSec: Double,
    durationSec: Double,
): Boolean = playedSec >= MIN_PLAYED_SEC &&
    positionSec.isFinite() &&
    durationSec.isFinite() &&
    durationSec > 0.0 &&
    positionSec >= 0.0 &&
    (positionSec >= MIN_POSITION_SEC || positionSec / durationSec >= COMPLETION_RATIO)

/**
 * One playback session's writes to `PUT /movies/{id}/watch-progress`. The server's upsert rule:
 * a different session always wins; within the same session only a strictly higher sequence
 * wins, and a stale save is silently dropped. So the session id is minted once here, and every
 * attempt — including a retry of the same position — takes a fresh `++sequence`, or a retried
 * write could lose to the attempt it is retrying.
 */
internal class ProgressReporter(
    private val movieId: Long,
    private val save: suspend (Long, UpdateMovieWatchProgressRequest) ->
    ApiResult<MovieWatchProgressUpdateData>,
    val sessionId: String = UUID.randomUUID().toString(),
) {
    private var sequence = 0L

    /** Persists one snapshot without discarding the server's mapped failure reason. */
    suspend fun saveNow(
        positionSec: Double,
        durationSec: Double,
    ): ApiResult<MovieWatchProgressUpdateData> {
        if (durationSec <= 0.0) {
            return ApiResult.Failure(AppError.Validation("Playback duration is not available."))
        }
        val request = UpdateMovieWatchProgressRequest(
            progressSec = positionSec.coerceIn(0.0, durationSec),
            durationSec = durationSec,
            saveSessionId = sessionId,
            saveSequence = ++sequence,
        )
        return save(movieId, request)
    }
}

internal const val SAVE_INTERVAL_SEC = 15.0
internal const val MIN_PLAYED_SEC = 15.0
internal const val MIN_POSITION_SEC = 30.0

/** Past this ratio the server marks the movie watched instead of storing progress. */
internal const val COMPLETION_RATIO = 0.98

/** A tick-to-tick position jump larger than this is a seek, not playback. */
internal const val MAX_TICK_DELTA_SEC = 2.0

/** How long the exit save may hold the close before the player leaves anyway (web parity). */
internal const val EXIT_SYNC_TIMEOUT_MS = 2_000L
