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
 * The album detail screen (docs/design-system.md section 11.5.1): one read, nothing to write —
 * the [TheaterMovieDetailsViewModel][com.igloo.blindpenguincoder.feature.movies.TheaterMovieDetailsViewModel]
 * shape. It publishes its own state type rather than riding `MovieDetailsUiState`: an album and
 * a movie share nothing but the overlay slot, and the host keeps the slot single-path by making
 * the detail view models' open states mutually exclusive.
 */
class AlbumDetailsViewModel(
    private val music: MusicRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailsUiState())
    val uiState: StateFlow<AlbumDetailsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /** Opens the overlay on album [id] and starts its one load. */
    fun open(id: Long) {
        loadJob?.cancel()
        _uiState.value = AlbumDetailsUiState(
            openAlbumId = id,
            details = AlbumDetailsState.Loading,
        )
        load(id, userInitiated = true)
    }

    /**
     * Back from the overlay. Cancels the in-flight read so a late response cannot reopen state.
     * Idempotent, because the host closes every detail view model on Back without asking which
     * one was up.
     */
    fun close() {
        loadJob?.cancel()
        loadJob = null
        _uiState.value = AlbumDetailsUiState()
    }

    /** The full-screen error's Retry: user-initiated, so the screen returns to Loading truth. */
    fun retry() {
        val id = _uiState.value.openAlbumId ?: return
        loadJob?.cancel()
        _uiState.update { it.copy(details = AlbumDetailsState.Loading) }
        load(id, userInitiated = true)
    }

    /**
     * Background re-read while the overlay is open (the host's start effect): a failure keeps
     * what is on screen — a TV waking from standby must not swap a readable page for an error
     * card the user never asked for.
     */
    fun refresh() {
        val id = _uiState.value.openAlbumId ?: return
        load(id, userInitiated = false)
    }

    private fun load(id: Long, userInitiated: Boolean) {
        loadJob = viewModelScope.launch {
            val result = music.albumDetails(id)
            if (_uiState.value.openAlbumId != id) return@launch
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(details = AlbumDetailsState.Loaded(toAlbumDetailsUi(result.value)))
                }

                is ApiResult.Failure -> _uiState.update {
                    it.copy(
                        details = it.details.errorOrKeep(
                            result.error.toLibraryDisplayMessage(),
                            userInitiated,
                        ),
                    )
                }
            }
        }
    }
}

sealed interface AlbumDetailsState {
    data object Loading : AlbumDetailsState
    data class Loaded(val album: AlbumDetailsUi) : AlbumDetailsState
    data class Error(val message: String) : AlbumDetailsState
}

/**
 * A background refresh failure never replaces readable content; only a user-initiated read
 * (open, Retry) may surface as the full-screen error.
 */
internal fun AlbumDetailsState.errorOrKeep(
    message: String,
    userInitiated: Boolean,
): AlbumDetailsState =
    if (!userInitiated && this is AlbumDetailsState.Loaded) {
        this
    } else {
        AlbumDetailsState.Error(message)
    }

data class AlbumDetailsUiState(
    val openAlbumId: Long? = null,
    val details: AlbumDetailsState = AlbumDetailsState.Loading,
)
