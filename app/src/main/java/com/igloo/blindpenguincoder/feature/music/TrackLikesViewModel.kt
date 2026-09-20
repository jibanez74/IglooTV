package com.igloo.blindpenguincoder.feature.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.repository.MusicRepository
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Like state for every track row in the app — the Tracks tab, album details and musician
 * details all read this one set, so no two surfaces can disagree about a heart.
 *
 * [likedIds] is null until `GET /music/tracks/liked-ids` has landed once; a row's Like control
 * is inert until then, because the POST is a server-side toggle and flipping an unknown value
 * would be a guess. A failed re-seed keeps the last-known set rather than blanking it.
 */
data class TrackLikesUiState(
    val likedIds: Set<Long>? = null,
    /** Tracks with a write in flight or queued; drives the pending treatment on that control only. */
    val pendingIds: Set<Long> = emptySet(),
    /** The last failed write, worded for the notice slot; cleared by the next accepted press. */
    val notice: String? = null,
) {
    fun isLiked(id: Long): Boolean? = likedIds?.contains(id)
}

class TrackLikesViewModel(
    private val music: MusicRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrackLikesUiState())
    val uiState: StateFlow<TrackLikesUiState> = _uiState.asStateFlow()

    private var seedJob: Job? = null

    /** Accepted presses not yet written, per track; each press is one toggle POST. */
    private val queuedPresses = mutableMapOf<Long, Int>()
    private val workers = mutableMapOf<Long, Job>()

    /** Tracks whose queue saw a failure; the set is re-read once the queue drains (the rollback). */
    private val reseedAfterDrain = mutableSetOf<Long>()

    /** The host's START effect: the first call seeds, later ones re-read keeping what is shown. */
    fun refresh() {
        seedJob?.cancel()
        seedJob = viewModelScope.launch { seed() }
    }

    /**
     * Optimistic flip; the write follows in per-track FIFO order. Ignored while the set is
     * unseeded — the control is inert then, and a press on it announces nothing.
     */
    fun toggle(id: Long) {
        val liked = _uiState.value.likedIds ?: return
        _uiState.update {
            it.copy(
                likedIds = if (id in liked) liked - id else liked + id,
                pendingIds = it.pendingIds + id,
                notice = null,
            )
        }
        queuedPresses[id] = (queuedPresses[id] ?: 0) + 1
        startWorker(id)
    }

    private fun startWorker(id: Long) {
        if (workers[id]?.isActive == true) return
        workers[id] = viewModelScope.launch {
            while ((queuedPresses[id] ?: 0) > 0) {
                queuedPresses[id] = queuedPresses.getValue(id) - 1
                when (val result = music.toggleTrackLike(id)) {
                    is ApiResult.Success -> {
                        // The response is truth only once every accepted press has been written;
                        // mid-queue it describes a state the user has already pressed past.
                        if ((queuedPresses[id] ?: 0) == 0) {
                            _uiState.update {
                                val liked = it.likedIds ?: return@update it
                                it.copy(
                                    likedIds = if (result.value.isLiked) liked + id else liked - id,
                                )
                            }
                        }
                    }

                    is ApiResult.Failure -> {
                        reseedAfterDrain += id
                        _uiState.update {
                            it.copy(
                                notice = "Couldn't update like: " +
                                    result.error.toLibraryDisplayMessage(),
                            )
                        }
                    }
                }
            }
            queuedPresses.remove(id)
            workers.remove(id)
            _uiState.update { it.copy(pendingIds = it.pendingIds - id) }
            // Drained with at least one failure: the displayed value may be a flip the server
            // never applied, and there is no per-track status read, so the whole set is re-read.
            // The track left the pending set first, so the seed is allowed to repaint it.
            if (reseedAfterDrain.remove(id)) {
                seedJob?.cancel()
                seedJob = launch { seed() }
            }
        }
    }

    private suspend fun seed() {
        when (val result = music.likedTrackIds()) {
            is ApiResult.Success -> _uiState.update { state ->
                // A press accepted while the read was in flight owns its own value until its
                // write settles; the seed must not paint over it.
                val pending = state.pendingIds
                val shownPending = state.likedIds.orEmpty().filter { it in pending }
                state.copy(likedIds = result.value.filterNot { it in pending }.toSet() + shownPending)
            }

            is ApiResult.Failure -> Unit
        }
    }
}
