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

    fun onEmailChange(value: String) {
        updateCredentials { it.copy(email = value) }
    }

    fun onPasswordChange(value: String) {
        updateCredentials { it.copy(password = value) }
    }

    fun submit() {
        val current = _uiState.value
        if (current.isSubmitting) return
        if (!current.awaitingUser && (current.email.isBlank() || current.password.isBlank())) {
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
                // The password is no longer needed once the token has been minted. From this
                // point, retrying resumes that token instead of submitting credentials again.
                _uiState.update { it.copy(password = "", awaitingUser = true) }
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

    private fun updateCredentials(transform: (LoginUiState) -> LoginUiState) {
        _uiState.update { transform(it).copy(error = null, awaitingUser = false) }
    }

    fun clearPassword() {
        _uiState.update { it.copy(password = "") }
    }

    fun retryRestore() {
        viewModelScope.launch { sessionManager.restore() }
    }

    fun changeServer() {
        sessionManager.requireServerChange()
    }

    /** Abandons "add a user" and goes back to the profiles already on this TV. */
    fun cancel() {
        viewModelScope.launch { sessionManager.cancelAddProfile() }
    }
}
