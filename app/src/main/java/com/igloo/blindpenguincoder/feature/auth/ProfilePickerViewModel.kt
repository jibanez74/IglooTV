package com.igloo.blindpenguincoder.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.data.model.ProfileSummary
import com.igloo.blindpenguincoder.data.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfilePickerUiState(
    /** The tile currently waiting on the server; the rest of the picker stays put. */
    val signingInUserId: Long? = null,
    val error: String? = null,
)

class ProfilePickerViewModel(
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfilePickerUiState())
    val uiState: StateFlow<ProfilePickerUiState> = _uiState.asStateFlow()

    fun select(profile: ProfileSummary) {
        if (_uiState.value.signingInUserId != null) return
        _uiState.update { it.copy(signingInUserId = profile.userId, error = null) }
        viewModelScope.launch {
            if (profile.hasPin) {
                // The PIN screen asks before any network call; the token is only activated.
                sessionManager.requirePin(profile)
                _uiState.update { it.copy(signingInUserId = null) }
                return@launch
            }
            val result = sessionManager.signInAs(profile)
            _uiState.update {
                it.copy(
                    signingInUserId = null,
                    error = (result as? SignInResult.Failed)?.error?.toDisplayMessage(),
                )
            }
        }
    }

    fun addProfile(profileCount: Int) {
        if (profileCount >= ProfileRepository.MAX_PROFILES) {
            _uiState.update {
                it.copy(
                    error = "This TV already has ${ProfileRepository.MAX_PROFILES} profiles. " +
                        "Sign one out first.",
                )
            }
            return
        }
        viewModelScope.launch { sessionManager.addProfile() }
    }

    fun changeServer() {
        sessionManager.requireServerChange()
    }

    fun retryRestore() {
        viewModelScope.launch { sessionManager.restore() }
    }
}
