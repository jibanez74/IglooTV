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

data class PinEntryUiState(
    /** How many digits have been entered — never the digits themselves. */
    val enteredCount: Int = 0,
    val isVerifying: Boolean = false,
    val error: String? = null,
    /**
     * How many verifications have been rejected. Verifying disables all eleven keys, so focus is
     * cleared and has to be handed back; see [ServerSetupUiState.completedAttempts] for why
     * neither [error] nor [isVerifying] can key that.
     */
    val rejections: Int = 0,
)

const val PIN_LENGTH = 4

/**
 * Gates a PIN-protected profile. The PIN is checked by the server, so this is a
 * convenience gate over an already-authenticated device token, not a second factor.
 */
class PinEntryViewModel(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PinEntryUiState())
    val uiState: StateFlow<PinEntryUiState> = _uiState.asStateFlow()

    /** Kept out of the UI state so it can never reach a composable or saved state. */
    private var pin = ""

    fun append(digit: Char) {
        if (!digit.isDigit()) return
        val current = _uiState.value
        if (current.isVerifying || current.enteredCount >= PIN_LENGTH) return
        pin += digit
        _uiState.update { it.copy(enteredCount = pin.length, error = null) }
        if (pin.length == PIN_LENGTH) verify()
    }

    fun delete() {
        if (_uiState.value.isVerifying || pin.isEmpty()) return
        pin = pin.dropLast(1)
        _uiState.update { it.copy(enteredCount = pin.length, error = null) }
    }

    fun back() {
        viewModelScope.launch { sessionManager.switchProfile() }
    }

    /**
     * This ViewModel is scoped to the Activity and keyed by profile, so leaving the keypad and
     * coming back to the same profile would otherwise reopen it on the last "Incorrect PIN."
     */
    fun reset() {
        pin = ""
        _uiState.value = PinEntryUiState()
    }

    private fun verify() {
        val submitted = pin
        _uiState.update { it.copy(isVerifying = true, error = null) }
        viewModelScope.launch {
            when (val result = authRepository.verifyPin(submitted)) {
                is ApiResult.Success -> if (result.value) finishSignIn() else reject("Incorrect PIN.")
                // A wrong PIN is a 200 with valid=false, so a 401 here means the device
                // token itself is dead.
                is ApiResult.Failure -> if (result.error == AppError.Unauthorized) {
                    sessionManager.onActiveSessionRevoked()
                } else {
                    reject(result.error.toDisplayMessage())
                }
            }
        }
    }

    private suspend fun finishSignIn() {
        pin = ""
        when (val result = sessionManager.completeSignIn()) {
            // The PIN has just been verified, so completeSignIn does not gate on it again and
            // PinRequired cannot arrive here; both mean this screen is done.
            SignInResult.Authenticated, SignInResult.PinRequired -> Unit
            SignInResult.Revoked -> sessionManager.onActiveSessionRevoked()
            is SignInResult.Failed -> reject(result.error.toDisplayMessage())
        }
    }

    private fun reject(message: String) {
        pin = ""
        _uiState.update {
            it.copy(
                enteredCount = 0,
                isVerifying = false,
                error = message,
                rejections = it.rejections + 1,
            )
        }
    }
}
