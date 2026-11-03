package com.igloo.blindpenguincoder.feature.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.data.repository.MusicRepository
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.feature.shared.ReadOnlyDetailsLoader
import kotlinx.coroutines.flow.StateFlow

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

    private val loader = ReadOnlyDetailsLoader(
        scope = viewModelScope,
        createState = { AlbumDetailsUiState(openAlbumId = it) },
        openId = AlbumDetailsUiState::openAlbumId,
        details = AlbumDetailsUiState::details,
        withDetails = { state, details -> state.copy(details = details) },
        fetch = music::albumDetails,
        toUi = ::toAlbumDetailsUi,
    )
    val uiState: StateFlow<AlbumDetailsUiState> = loader.uiState

    /** Opens the overlay on album [id] and starts its one load. */
    fun open(id: Long) = loader.open(id)

    /**
     * Back from the overlay. Cancels the in-flight read so a late response cannot reopen state.
     * Idempotent, because the host closes every detail view model on Back without asking which
     * one was up.
     */
    fun close() = loader.close()

    /** The full-screen error's Retry: user-initiated, so the screen returns to Loading truth. */
    fun retry() = loader.retry()

    /**
     * Background re-read while the overlay is open (the host's start effect): a failure keeps
     * what is on screen — a TV waking from standby must not swap a readable page for an error
     * card the user never asked for.
     */
    fun refresh() = loader.refresh()
}

data class AlbumDetailsUiState(
    val openAlbumId: Long? = null,
    val details: DetailsState<AlbumDetailsUi> = DetailsState.Loading,
)
