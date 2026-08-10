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
 * A ViewModel rather than shell state keeps duplicate confirmation and pending behavior stable
 * across recomposition. [SessionManager.logout] moves the security-sensitive work to application
 * scope, so clearing this ViewModel only stops waiting for that work; it cannot retain a credential.
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

    /**
     * Closes the confirmation. While a revoke is in flight this stops *showing* it — it does not
     * recall it, so `pending` survives and [request] still refuses to re-ask for a sign-out whose
     * local half is already committed. Only [confirm]'s `finally` clears the flag.
     */
    fun dismiss() {
        _uiState.update { SignOutUiState(pending = it.pending) }
    }

    fun confirm() {
        if (_uiState.value.pending) return
        _uiState.update { it.copy(pending = true) }
        viewModelScope.launch {
            try {
                sessionManager.logout()
            } finally {
                // Reset even if the scope is cancelled: this instance can be reused when the same
                // Activity signs back in, and a stale flag would resurrect the dialog.
                _uiState.value = SignOutUiState()
            }
        }
    }
}
