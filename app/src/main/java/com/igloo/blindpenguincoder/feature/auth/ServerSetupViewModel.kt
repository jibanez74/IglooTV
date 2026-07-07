package com.igloo.blindpenguincoder.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.repository.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerSetupUiState(
    val input: String = "",
    val isConnecting: Boolean = false,
    val error: String? = null,
)

class ServerSetupViewModel(
    private val serverRepository: ServerRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerSetupUiState())
    val uiState: StateFlow<ServerSetupUiState> = _uiState.asStateFlow()

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value, error = null) }
    }

    fun connect() {
        if (_uiState.value.isConnecting) return
        _uiState.update { it.copy(isConnecting = true, error = null) }
        viewModelScope.launch {
            when (val result = serverRepository.connect(_uiState.value.input)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isConnecting = false) }
                    sessionManager.onServerChanged()
                }
                is ApiResult.Failure -> _uiState.update {
                    it.copy(isConnecting = false, error = result.error.toDisplayMessage())
                }
            }
        }
    }
}
