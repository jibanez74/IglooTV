package com.igloo.blindpenguincoder.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.model.MovieWatchProgressUpdateData
import com.igloo.blindpenguincoder.data.model.UpdateMovieWatchProgressRequest
import com.igloo.blindpenguincoder.playback.progress.COMPLETION_RATIO
import com.igloo.blindpenguincoder.playback.progress.EXIT_SYNC_TIMEOUT_MS
import com.igloo.blindpenguincoder.playback.progress.MAX_TICK_DELTA_SEC
import com.igloo.blindpenguincoder.playback.progress.MIN_POSITION_SEC
import com.igloo.blindpenguincoder.playback.progress.ProgressReporter
import com.igloo.blindpenguincoder.playback.progress.shouldSaveProgress
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    private var reporter: ProgressReporter? = null
    private var playedSec = 0.0
    private var playedAtLastSave: Double? = null
    private var lastPositionSec: Double? = null
    private var saveInFlight = false

    fun startSession(movieId: Long) {
        reporter = ProgressReporter(movieId, saveProgress)
        playedSec = 0.0
        playedAtLastSave = null
        lastPositionSec = null
        saveInFlight = false
    }

    fun onTick(positionSec: Double, durationSec: Double, isPlaying: Boolean) {
        val reporter = reporter ?: return
        val previous = lastPositionSec
        lastPositionSec = positionSec
        if (isPlaying && previous != null) {
            val delta = positionSec - previous
            if (delta > 0.0 && delta <= MAX_TICK_DELTA_SEC) playedSec += delta
        }
        val sinceLastSave = playedAtLastSave?.let { playedSec - it }
        if (saveInFlight ||
            !shouldSaveProgress(playedSec, positionSec, durationSec, sinceLastSave)
        ) {
            return
        }
        playedAtLastSave = playedSec
        saveInFlight = true
        viewModelScope.launch {
            val watched = reporter.saveNow(positionSec, durationSec)
            saveInFlight = false
            if (watched == true) onWatchedStateCommitted()
        }
    }

    /**
     * The player is closing: one last save (when the position is worth keeping), bounded so a
     * dead server cannot hold the exit, then the continue-watching refresh either way.
     */
    fun endSession(finalPositionSec: Double, durationSec: Double) {
        val reporter = reporter ?: return
        this.reporter = null
        viewModelScope.launch {
            val worthKeeping = durationSec > 0.0 &&
                (
                    finalPositionSec >= MIN_POSITION_SEC ||
                        finalPositionSec / durationSec >= COMPLETION_RATIO
                    )
            if (worthKeeping) {
                withTimeoutOrNull(EXIT_SYNC_TIMEOUT_MS) {
                    reporter.saveNow(finalPositionSec, durationSec)
                }
            }
            onWatchedStateCommitted()
        }
    }
}
