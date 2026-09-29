package com.igloo.blindpenguincoder.feature.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.repository.MusicRepository
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.feature.shared.errorOrKeep
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The musician detail screen (docs/design-system.md section 11.5.2): one read, nothing to
 * write — [AlbumDetailsViewModel]'s shape over `GET /music/musicians/{id}`, the fourth
 * occupant of the host's one details slot, kept mutually exclusive with the other three by the
 * host's open callbacks.
 */
class MusicianDetailsViewModel(
    private val music: MusicRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MusicianDetailsUiState())
    val uiState: StateFlow<MusicianDetailsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    fun open(id: Long) {
        loadJob?.cancel()
        _uiState.value = MusicianDetailsUiState(openMusicianId = id)
        load(id, userInitiated = true)
    }

    /** Back from the overlay; idempotent, because the host closes every detail view model. */
    fun close() {
        loadJob?.cancel()
        loadJob = null
        _uiState.value = MusicianDetailsUiState()
    }

    fun retry() {
        val id = _uiState.value.openMusicianId ?: return
        loadJob?.cancel()
        _uiState.update { it.copy(details = DetailsState.Loading) }
        load(id, userInitiated = true)
    }

    /** Background re-read while open (the host's start effect); a failure keeps the page. */
    fun refresh() {
        val id = _uiState.value.openMusicianId ?: return
        load(id, userInitiated = false)
    }

    private fun load(id: Long, userInitiated: Boolean) {
        loadJob = viewModelScope.launch {
            val result = music.musicianDetails(id)
            if (_uiState.value.openMusicianId != id) return@launch
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(details = DetailsState.Loaded(toMusicianDetailsUi(result.value)))
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

data class MusicianDetailsUiState(
    val openMusicianId: Long? = null,
    val details: DetailsState<MusicianDetailsUi> = DetailsState.Loading,
)
