package com.igloo.blindpenguincoder.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
    /** A token was issued but the user fetch failed; resubmitting resumes it, not a second login. */
    val awaitingUser: Boolean = false,
)

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    // Editing credentials invalidates any half-finished sign-in: start from device-login again.
    fun onEmailChange(value: String) {
        _uiState.update { it.copy(email = value, error = null, awaitingUser = false) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, error = null, awaitingUser = false) }
    }

    fun submit() {
        val current = _uiState.value
        if (current.isSubmitting) return
        if (current.email.isBlank() || current.password.isBlank()) {
            _uiState.update { it.copy(error = "Enter your email and password.") }
            return
        }
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            // Logging in again would mint a second device token for the same user, so a retry
            // after a failed user fetch resumes with the token already stored.
            if (!current.awaitingUser) {
                val login = authRepository.deviceLogin(current.email.trim(), current.password)
                if (login is ApiResult.Failure) {
                    _uiState.update {
                        it.copy(isSubmitting = false, error = login.error.toDisplayMessage())
                    }
                    return@launch
                }
            }
            when (val result = sessionManager.completeSignIn()) {
                SignInResult.Authenticated ->
                    _uiState.update {
                        it.copy(isSubmitting = false, password = "", awaitingUser = false)
                    }
                SignInResult.Revoked -> _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        error = AppError.Unauthorized.toDisplayMessage(),
                        awaitingUser = false,
                    )
                }
                is SignInResult.Failed -> _uiState.update {
                    it.copy(
                        isSubmitting = false,
                        error = result.error.toDisplayMessage(),
                        awaitingUser = true,
                    )
                }
            }
        }
    }

    fun retryRestore() {
        viewModelScope.launch { sessionManager.restore() }
    }

    fun changeServer() {
        sessionManager.requireServerChange()
    }
}
