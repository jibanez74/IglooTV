package com.igloo.blindpenguincoder.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.model.UpdateWatchProgressRequest
import com.igloo.blindpenguincoder.data.model.WatchedData
import com.igloo.blindpenguincoder.feature.auth.toFailureNotice
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import com.igloo.blindpenguincoder.playback.progress.COMPLETION_RATIO
import com.igloo.blindpenguincoder.playback.progress.FLUSH_DEDUPE_SEC
import com.igloo.blindpenguincoder.playback.progress.MAX_TICK_DELTA_SEC
import com.igloo.blindpenguincoder.playback.progress.ProgressReporter
import com.igloo.blindpenguincoder.playback.progress.shouldPersistProgress
import com.igloo.blindpenguincoder.playback.progress.shouldSaveProgress
import kotlin.math.abs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * The progress-saving side of the player, in a ViewModel because writes must outlive the
 * screen: Back disposes the composable in the same frame, and the exit write still has to land.
 * Pause, background and exit writes run under [NonCancellable] — the activity finishing right
 * behind the player would otherwise cancel the request and lose the end of the movie without a
 * trace. Played time accumulates from position deltas between ticks — a seek's jump is excluded
 * by [MAX_TICK_DELTA_SEC] — so the cadence's "15 seconds of actual playback" means playback, not
 * wall-clock or seek distance; the other writes skip that floor, like the web.
 *
 * Every screen visit is a new save session, and the server takes any write from another session,
 * so a player re-sending a position it already saved — paused here while the viewer went on
 * watching on another device, or restored at its saved position after the process died — would
 * roll the newer progress back. Pause, background and exit writes therefore go out only once this
 * session has played (or already saved), and only for a position the server does not hold yet.
 */
class VideoPlayerViewModel(
    private val saveProgress: suspend (PlaybackMediaRef, UpdateWatchProgressRequest) ->
    ApiResult<WatchedData>,
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
    private var lastDispatchedPositionSec: Double? = null
    private var hasPlayed = false
    private var nextFailureResolutionOrder = 0L
    private var retryInFlight = false

    fun startSession(media: PlaybackMediaRef) {
        val previousSession = activeSession
        val reporter = ProgressReporter(media, saveProgress)
        activeSession = ProgressSaveSession(reporter).also {
            saveSessions[reporter.sessionId] = it
        }
        previousSession?.let(::discardSettledSession)
        playedSec = 0.0
        playedAtLastSave = null
        lastPositionSec = null
        lastDispatchedPositionSec = null
        hasPlayed = false
    }

    fun onTick(positionSec: Double, durationSec: Double, isPlaying: Boolean) {
        val session = activeSession ?: return
        val previous = lastPositionSec
        lastPositionSec = positionSec
        if (isPlaying) hasPlayed = true
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
        launchSave(session, positionSec, durationSec, refreshAfterSuccess = false, outlivesOwner = false)
    }

    /**
     * Playback stopped without the screen leaving — a pause, or the host going to the
     * background. The snapshot goes out at once when it says something new, and the cadence
     * restarts from here. Unlike a cadence save it does not wait for an in-flight write: the
     * higher sequence wins on the server either way.
     */
    fun flushProgress(positionSec: Double, durationSec: Double) {
        val session = activeSession ?: return
        if (!mayReport() || !shouldPersistProgress(positionSec, durationSec)) return
        val lastDispatched = lastDispatchedPositionSec
        if (lastDispatched != null && sameReport(positionSec, lastDispatched, durationSec)) return
        playedAtLastSave = playedSec
        launchSave(session, positionSec, durationSec, refreshAfterSuccess = false, outlivesOwner = true)
    }

    /**
     * The player is closing [media]: one last eligible save, whose success refreshes the details
     * page and Continue Watching on return. A position the session already saved is not sent
     * again (see the class notes), but the refresh still runs, since the earlier save did not
     * trigger one; an earlier save still in flight or failed does not count, so the exit writes
     * then. A failure stays available for an explicit retry after the screen is gone. The media
     * names which session is ending: an episode handing off to the next one disposes its screen in
     * the same pass the new screen starts its session, so the old screen's exit must not end — or
     * write onto — the new episode's session.
     */
    fun endSession(media: PlaybackMediaRef, finalPositionSec: Double, durationSec: Double) {
        val session = activeSession?.takeIf { it.reporter.media == media } ?: return
        activeSession = null
        if (!mayReport()) {
            // Nothing to write, but another device may have moved on: the page underneath
            // re-reads, as every close does.
            discardSettledSession(session)
            onWatchedStateCommitted()
            return
        }
        if (!shouldPersistProgress(finalPositionSec, durationSec)) {
            discardSettledSession(session)
            return
        }
        if (alreadySaved(session, finalPositionSec, durationSec)) {
            discardSettledSession(session)
            onWatchedStateCommitted()
            return
        }
        launchSave(session, finalPositionSec, durationSec, refreshAfterSuccess = true, outlivesOwner = true)
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

    /** Nothing played in this session and nothing saved: its position is not this visit's news. */
    private fun mayReport(): Boolean = hasPlayed || lastDispatchedPositionSec != null

    /** The latest dispatched save is [positionSec]'s, and it landed: nothing in flight or failed. */
    private fun alreadySaved(
        session: ProgressSaveSession,
        positionSec: Double,
        durationSec: Double,
    ): Boolean {
        val lastDispatched = lastDispatchedPositionSec ?: return false
        return sameReport(positionSec, lastDispatched, durationSec) &&
            session.savesInFlight == 0 &&
            session.failedSnapshot == null
    }

    /**
     * Two positions say the same thing: within [FLUSH_DEDUPE_SEC], and on the same side of the
     * watched threshold, so a pause just short of it never swallows an exit just past it.
     */
    private fun sameReport(positionSec: Double, otherSec: Double, durationSec: Double): Boolean =
        abs(positionSec - otherSec) < FLUSH_DEDUPE_SEC &&
            (positionSec / durationSec >= COMPLETION_RATIO) ==
            (otherSec / durationSec >= COMPLETION_RATIO)

    /** Sends one snapshot as [session]'s next save, reporting a watched flip it commits. */
    private fun launchSave(
        session: ProgressSaveSession,
        positionSec: Double,
        durationSec: Double,
        refreshAfterSuccess: Boolean,
        outlivesOwner: Boolean,
    ) {
        val snapshot = dispatch(positionSec, durationSec)
        val attempt = beginSave(session)
        viewModelScope.launch {
            if (saveSnapshot(attempt, snapshot, refreshAfterSuccess, outlivesOwner)) {
                onWatchedStateCommitted()
            }
        }
    }

    private fun dispatch(positionSec: Double, durationSec: Double): ProgressSnapshot {
        lastDispatchedPositionSec = positionSec
        return ProgressSnapshot(positionSec, durationSec)
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
        outlivesOwner: Boolean = false,
    ): Boolean {
        val session = attempt.session
        return try {
            val result = if (outlivesOwner) {
                withContext(NonCancellable) {
                    session.reporter.saveNow(snapshot.positionSec, snapshot.durationSec)
                }
            } else {
                session.reporter.saveNow(snapshot.positionSec, snapshot.durationSec)
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
                    session.failureMessage = result.error.toFailureNotice("save playback progress")
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
