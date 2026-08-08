package com.igloo.blindpenguincoder.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.repository.ServerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerSetupUiState(
    val input: String = "",
    val isConnecting: Boolean = false,
    val error: String? = null,
    /**
     * How many connect attempts have finished. The screen keys focus restoration on this rather
     * than on [error] or [isConnecting]: two attempts against the same bad address produce the
     * same message, and a client-side rejection resolves inside one snapshot batch, so neither of
     * those changes in a way a `LaunchedEffect` can observe.
     */
    val completedAttempts: Int = 0,
)

class ServerSetupViewModel(
    private val serverRepository: ServerRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerSetupUiState())
    val uiState: StateFlow<ServerSetupUiState> = _uiState.asStateFlow()
    private var connectionJob: Job? = null

    fun beginSetup(initialOrigin: String) {
        connectionJob?.cancel()
        connectionJob = null
        _uiState.value = ServerSetupUiState(input = initialOrigin)
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value, error = null) }
    }

    fun connect() {
        if (_uiState.value.isConnecting) return
        _uiState.update { it.copy(isConnecting = true, error = null) }
        connectionJob = viewModelScope.launch {
            when (val result = serverRepository.connect(_uiState.value.input)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(isConnecting = false, completedAttempts = it.completedAttempts + 1)
                    }
                    sessionManager.onServerChanged(result.value)
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(
                        isConnecting = false,
                        error = result.error.toDisplayMessage(),
                        completedAttempts = it.completedAttempts + 1,
                    )
                }
            }
        }
    }
}
