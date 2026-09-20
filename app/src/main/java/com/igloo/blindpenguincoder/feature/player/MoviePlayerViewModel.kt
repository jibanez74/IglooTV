package com.igloo.blindpenguincoder.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.MovieWatchProgressUpdateData
import com.igloo.blindpenguincoder.data.model.UpdateMovieWatchProgressRequest
import com.igloo.blindpenguincoder.playback.progress.EXIT_SYNC_TIMEOUT_MS
import com.igloo.blindpenguincoder.playback.progress.MAX_TICK_DELTA_SEC
import com.igloo.blindpenguincoder.playback.progress.ProgressReporter
import com.igloo.blindpenguincoder.playback.progress.shouldSaveFinalProgress
import com.igloo.blindpenguincoder.playback.progress.shouldSaveProgress
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ProgressSyncUiState {
    data object Synced : ProgressSyncUiState
    data class Failed(val message: String) : ProgressSyncUiState
}

private data class ProgressSnapshot(
    val positionSec: Double,
    val durationSec: Double,
)

private data class ProgressSaveSession(
    val reporter: ProgressReporter,
    var nextAttemptOrder: Long = 0L,
    var lastResolvedAttemptOrder: Long = 0L,
    var savesInFlight: Int = 0,
    var failedSnapshot: ProgressSnapshot? = null,
    var failureMessage: String? = null,
    var failureResolutionOrder: Long = 0L,
)

private data class ProgressSaveAttempt(
    val session: ProgressSaveSession,
    val order: Long,
)

/**
 * The progress-saving side of the player, in a ViewModel because the exit save must outlive
 * the screen: Back disposes the composable in the same frame, and the final write still has
 * [EXIT_SYNC_TIMEOUT_MS] to land. Played time accumulates from position deltas between ticks —
 * a seek's jump is excluded by [MAX_TICK_DELTA_SEC] — so "15 seconds of actual playback"
 * means playback, not wall-clock or seek distance.
 */
class MoviePlayerViewModel(
    private val saveProgress: suspend (Long, UpdateMovieWatchProgressRequest) ->
    ApiResult<MovieWatchProgressUpdateData>,
    private val onWatchedStateCommitted: () -> Unit,
) : ViewModel() {

    private val _progressSyncUiState = MutableStateFlow<ProgressSyncUiState>(
        ProgressSyncUiState.Synced,
    )
    val progressSyncUiState: StateFlow<ProgressSyncUiState> =
        _progressSyncUiState.asStateFlow()

    private val saveSessions = mutableMapOf<String, ProgressSaveSession>()
    private var activeSession: ProgressSaveSession? = null
    private var playedSec = 0.0
    private var playedAtLastSave: Double? = null
    private var lastPositionSec: Double? = null
    private var nextFailureResolutionOrder = 0L
    private var retryInFlight = false

    fun startSession(movieId: Long) {
        val previousSession = activeSession
        val reporter = ProgressReporter(movieId, saveProgress)
        activeSession = ProgressSaveSession(reporter).also {
            saveSessions[reporter.sessionId] = it
        }
        previousSession?.let(::discardSettledSession)
        playedSec = 0.0
        playedAtLastSave = null
        lastPositionSec = null
    }

    fun onTick(positionSec: Double, durationSec: Double, isPlaying: Boolean) {
        val session = activeSession ?: return
        val previous = lastPositionSec
        lastPositionSec = positionSec
        if (isPlaying && previous != null) {
            val delta = positionSec - previous
            if (delta > 0.0 && delta <= MAX_TICK_DELTA_SEC) playedSec += delta
        }
        val sinceLastSave = playedAtLastSave?.let { playedSec - it }
        if (session.savesInFlight > 0 ||
            !shouldSaveProgress(playedSec, positionSec, durationSec, sinceLastSave)
        ) {
            return
        }
        playedAtLastSave = playedSec
        val snapshot = ProgressSnapshot(positionSec, durationSec)
        val attempt = beginSave(session)
        viewModelScope.launch {
            if (saveSnapshot(attempt, snapshot, refreshAfterSuccess = false)) {
                onWatchedStateCommitted()
            }
        }
    }

    /**
     * The player is closing: one last eligible save, bounded so a dead server cannot hold the
     * exit. A failure stays available for an explicit retry after the screen is gone.
     */
    fun endSession(finalPositionSec: Double, durationSec: Double) {
        val session = activeSession ?: return
        activeSession = null
        val snapshot = ProgressSnapshot(finalPositionSec, durationSec)
        if (!shouldSaveFinalProgress(playedSec, finalPositionSec, durationSec)) {
            discardSettledSession(session)
            return
        }
        val attempt = beginSave(session)
        viewModelScope.launch {
            if (
                saveSnapshot(
                    attempt = attempt,
                    snapshot = snapshot,
                    refreshAfterSuccess = true,
                    timeoutMillis = EXIT_SYNC_TIMEOUT_MS,
                )
            ) {
                onWatchedStateCommitted()
            }
        }
    }

    /** Retries every session's latest failed snapshot with its original reporter. */
    fun retryFailedSave() {
        if (retryInFlight) return
        val pendingSessions = saveSessions.values
            .filter { it.failedSnapshot != null }
            .sortedBy { it.failureResolutionOrder }
        if (pendingSessions.isEmpty()) return
        retryInFlight = true
        viewModelScope.launch {
            var shouldRefresh = false
            try {
                pendingSessions.forEach { session ->
                    val snapshot = session.failedSnapshot ?: return@forEach
                    shouldRefresh = saveSnapshot(
                        attempt = beginSave(session),
                        snapshot = snapshot,
                        refreshAfterSuccess = true,
                    ) || shouldRefresh
                }
            } finally {
                retryInFlight = false
            }
            if (shouldRefresh) onWatchedStateCommitted()
        }
    }

    private fun beginSave(session: ProgressSaveSession): ProgressSaveAttempt {
        session.savesInFlight += 1
        session.nextAttemptOrder += 1
        return ProgressSaveAttempt(session, session.nextAttemptOrder)
    }

    private suspend fun saveSnapshot(
        attempt: ProgressSaveAttempt,
        snapshot: ProgressSnapshot,
        refreshAfterSuccess: Boolean,
        timeoutMillis: Long? = null,
    ): Boolean {
        val session = attempt.session
        return try {
            val result = if (timeoutMillis == null) {
                session.reporter.saveNow(snapshot.positionSec, snapshot.durationSec)
            } else {
                withTimeoutOrNull(timeoutMillis) {
                    session.reporter.saveNow(snapshot.positionSec, snapshot.durationSec)
                } ?: ApiResult.Failure(AppError.Timeout)
            }
            if (attempt.order < session.lastResolvedAttemptOrder) return false
            session.lastResolvedAttemptOrder = attempt.order

            when (result) {
                is ApiResult.Success -> {
                    val recovered = session.failedSnapshot != null
                    session.failedSnapshot = null
                    session.failureMessage = null
                    updateProgressSyncUiState()
                    refreshAfterSuccess || recovered || result.value.watched
                }
                is ApiResult.Failure -> {
                    session.failedSnapshot = snapshot
                    session.failureMessage =
                        "Couldn't save playback progress: ${result.error.toLibraryDisplayMessage()}"
                    session.failureResolutionOrder = ++nextFailureResolutionOrder
                    updateProgressSyncUiState()
                    false
                }
            }
        } finally {
            session.savesInFlight -= 1
            discardSettledSession(session)
        }
    }

    private fun updateProgressSyncUiState() {
        val latestFailure = saveSessions.values
            .filter { it.failedSnapshot != null }
            .maxByOrNull { it.failureResolutionOrder }
        _progressSyncUiState.value = latestFailure?.failureMessage
            ?.let(ProgressSyncUiState::Failed)
            ?: ProgressSyncUiState.Synced
    }

    private fun discardSettledSession(session: ProgressSaveSession) {
        if (
            session !== activeSession && session.savesInFlight == 0 &&
            session.failedSnapshot == null
        ) {
            saveSessions.remove(session.reporter.sessionId, session)
        }
    }
}
