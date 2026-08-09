package com.igloo.blindpenguincoder.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.feature.auth.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignOutUiState(
    /** The confirmation is up; the shell behind it is dimmed and hidden from TalkBack. */
    val confirming: Boolean = false,
    /** The revoke is in flight. The confirm control is gone, so it cannot be pressed twice. */
    val pending: Boolean = false,
)

/**
 * Owns the sign-out confirmation (docs/design-system.md section 9.3).
 *
 * A ViewModel rather than shell state for one reason beyond the layering rule: the revoke runs in
 * [viewModelScope], which outlives the `Authenticated` arm of the composition. A session that ends
 * from elsewhere mid-request — another 401 arriving — would otherwise cancel the coroutine partway
 * and leave the credential on the TV, which is precisely the outcome signing out exists to prevent.
 */
class SignOutViewModel(
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SignOutUiState())
    val uiState: StateFlow<SignOutUiState> = _uiState.asStateFlow()

    fun request() {
        if (_uiState.value.pending) return
        _uiState.value = SignOutUiState(confirming = true)
    }

    fun dismiss() {
        _uiState.value = SignOutUiState()
    }

    fun confirm() {
        if (_uiState.value.pending) return
        _uiState.update { it.copy(pending = true) }
        viewModelScope.launch {
            try {
                sessionManager.logout()
            } finally {
                // Reset even if the scope is cancelled: this instance is reused when the same
                // Activity signs back in, and a stale flag would resurrect the dialog.
                _uiState.value = SignOutUiState()
            }
        }
    }
}
