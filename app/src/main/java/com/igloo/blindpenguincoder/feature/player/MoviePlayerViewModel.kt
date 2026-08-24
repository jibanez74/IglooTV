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

private data class FailedProgressSave(
    val reporter: ProgressReporter,
    val snapshot: ProgressSnapshot,
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

    private var activeReporter: ProgressReporter? = null
    private var failedSave: FailedProgressSave? = null
    private var playedSec = 0.0
    private var playedAtLastSave: Double? = null
    private var lastPositionSec: Double? = null
    private var savesInFlight = 0
    private var nextAttempt = 0L
    private var lastResolvedAttempt = 0L

    fun startSession(movieId: Long) {
        activeReporter = ProgressReporter(movieId, saveProgress)
        playedSec = 0.0
        playedAtLastSave = null
        lastPositionSec = null
    }

    fun onTick(positionSec: Double, durationSec: Double, isPlaying: Boolean) {
        val reporter = activeReporter ?: return
        val previous = lastPositionSec
        lastPositionSec = positionSec
        if (isPlaying && previous != null) {
            val delta = positionSec - previous
            if (delta > 0.0 && delta <= MAX_TICK_DELTA_SEC) playedSec += delta
        }
        val sinceLastSave = playedAtLastSave?.let { playedSec - it }
        if (savesInFlight > 0 ||
            !shouldSaveProgress(playedSec, positionSec, durationSec, sinceLastSave)
        ) {
            return
        }
        playedAtLastSave = playedSec
        val snapshot = ProgressSnapshot(positionSec, durationSec)
        savesInFlight += 1
        viewModelScope.launch {
            try {
                saveSnapshot(reporter, snapshot, refreshAfterSuccess = false)
            } finally {
                savesInFlight -= 1
            }
        }
    }

    /**
     * The player is closing: one last eligible save, bounded so a dead server cannot hold the
     * exit. A failure stays available for an explicit retry after the screen is gone.
     */
    fun endSession(finalPositionSec: Double, durationSec: Double) {
        val reporter = activeReporter ?: return
        activeReporter = null
        val snapshot = ProgressSnapshot(finalPositionSec, durationSec)
        if (!shouldSaveFinalProgress(playedSec, finalPositionSec, durationSec)) return
        savesInFlight += 1
        viewModelScope.launch {
            try {
                saveSnapshot(
                    reporter = reporter,
                    snapshot = snapshot,
                    refreshAfterSuccess = true,
                    timeoutMillis = EXIT_SYNC_TIMEOUT_MS,
                )
            } finally {
                savesInFlight -= 1
            }
        }
    }

    /** Retries the exact failed snapshot with its original session and a higher sequence. */
    fun retryFailedSave() {
        val failed = failedSave ?: return
        if (savesInFlight > 0) return
        savesInFlight += 1
        viewModelScope.launch {
            try {
                saveSnapshot(
                    reporter = failed.reporter,
                    snapshot = failed.snapshot,
                    refreshAfterSuccess = true,
                )
            } finally {
                savesInFlight -= 1
            }
        }
    }

    private suspend fun saveSnapshot(
        reporter: ProgressReporter,
        snapshot: ProgressSnapshot,
        refreshAfterSuccess: Boolean,
        timeoutMillis: Long? = null,
    ) {
        val attempt = ++nextAttempt
        val result = if (timeoutMillis == null) {
            reporter.saveNow(snapshot.positionSec, snapshot.durationSec)
        } else {
            withTimeoutOrNull(timeoutMillis) {
                reporter.saveNow(snapshot.positionSec, snapshot.durationSec)
            } ?: ApiResult.Failure(AppError.Timeout)
        }
        if (attempt < lastResolvedAttempt) return
        lastResolvedAttempt = attempt

        when (result) {
            is ApiResult.Success -> {
                val recovered = failedSave != null
                failedSave = null
                _progressSyncUiState.value = ProgressSyncUiState.Synced
                if (refreshAfterSuccess || recovered || result.value.watched) {
                    onWatchedStateCommitted()
                }
            }
            is ApiResult.Failure -> {
                failedSave = FailedProgressSave(reporter, snapshot)
                _progressSyncUiState.value = ProgressSyncUiState.Failed(
                    "Couldn't save playback progress: ${result.error.toLibraryDisplayMessage()}",
                )
            }
        }
    }
}
