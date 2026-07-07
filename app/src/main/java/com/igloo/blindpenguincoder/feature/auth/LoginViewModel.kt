package com.igloo.blindpenguincoder.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
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
)

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onEmailChange(value: String) {
        _uiState.update { it.copy(email = value, error = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, error = null) }
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
            val login = authRepository.login(current.email.trim(), current.password)
            if (login is ApiResult.Failure) {
                _uiState.update {
                    it.copy(isSubmitting = false, error = login.error.toDisplayMessage())
                }
                return@launch
            }
            when (val user = authRepository.fetchCurrentUser()) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isSubmitting = false, password = "") }
                    sessionManager.onLoggedIn(user.value)
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isSubmitting = false, error = user.error.toDisplayMessage())
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
