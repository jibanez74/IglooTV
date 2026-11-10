package com.igloo.blindpenguincoder.feature.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.data.repository.MusicRepository
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.feature.shared.ReadOnlyDetailsLoader
import kotlinx.coroutines.flow.StateFlow

/**
 * The musician detail screen (docs/design-system.md section 11.5.2): one read, nothing to
 * write — [AlbumDetailsViewModel]'s shape over `GET /music/musicians/{id}`, the fourth
 * occupant of the host's one details slot, kept mutually exclusive with the other three by the
 * host's open callbacks.
 */
class MusicianDetailsViewModel(
    private val music: MusicRepository,
) : ViewModel() {

    private val loader = ReadOnlyDetailsLoader(
        scope = viewModelScope,
        createState = { MusicianDetailsUiState(openMusicianId = it) },
        openId = MusicianDetailsUiState::openMusicianId,
        details = MusicianDetailsUiState::details,
        withDetails = { state, details -> state.copy(details = details) },
        fetch = music::musicianDetails,
        toUi = ::toMusicianDetailsUi,
    )
    val uiState: StateFlow<MusicianDetailsUiState> = loader.uiState

    fun open(id: Long) = loader.open(id)
    fun close() = loader.close()
    fun retry() = loader.retry()
    fun refresh() = loader.refresh()
}

data class MusicianDetailsUiState(
    val openMusicianId: Long? = null,
    val details: DetailsState<MusicianDetailsUi> = DetailsState.Loading,
)
