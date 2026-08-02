package com.igloo.blindpenguincoder.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.QuickConnectInitiateData
import com.igloo.blindpenguincoder.data.model.QuickConnectStatus
import com.igloo.blindpenguincoder.data.repository.AuthRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface QuickConnectPhase {
    data object RequestingCode : QuickConnectPhase
    data class CodeReady(val code: String) : QuickConnectPhase
    data class Failed(val message: String) : QuickConnectPhase
}

data class QuickConnectUiState(
    val phase: QuickConnectPhase = QuickConnectPhase.RequestingCode,
)

/**
 * Runs the pairing loop: initiate a code, poll redeem until approved, and
 * silently re-initiate when the code expires. The secret only ever lives in
 * the loop coroutine's locals.
 */
class QuickConnectViewModel(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuickConnectUiState())
    val uiState: StateFlow<QuickConnectUiState> = _uiState.asStateFlow()

    private var pairingJob: Job? = null

    fun start() {
        if (pairingJob?.isActive == true) return
        _uiState.value = QuickConnectUiState(QuickConnectPhase.RequestingCode)
        pairingJob = viewModelScope.launch { runPairing() }
    }

    fun stop() {
        pairingJob?.cancel()
        pairingJob = null
        _uiState.value = QuickConnectUiState(QuickConnectPhase.RequestingCode)
    }

    fun retry() {
        stop()
        start()
    }

    private suspend fun runPairing() {
        while (true) {
            val initiated = initiateWithBackoff() ?: return
            _uiState.value = QuickConnectUiState(QuickConnectPhase.CodeReady(initiated.code))
            if (pollUntilResolved(initiated)) return
            // Code expired or became invalid: request a fresh one.
            _uiState.value = QuickConnectUiState(QuickConnectPhase.RequestingCode)
        }
    }

    private suspend fun initiateWithBackoff(): QuickConnectInitiateData? {
        var backoffMillis = INITIATE_BACKOFF_MILLIS
        while (true) {
            when (val result = authRepository.initiateQuickConnect()) {
                is ApiResult.Success -> return result.value
                is ApiResult.Failure -> {
                    val error = result.error
                    if (error is AppError.Api && error.status in RETRYABLE_STATUSES) {
                        delay(backoffMillis)
                        backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
                    } else {
                        _uiState.value = QuickConnectUiState(
                            QuickConnectPhase.Failed(error.toQuickConnectDisplayMessage()),
                        )
                        return null
                    }
                }
            }
        }
    }

    /** Returns true when pairing is finished (approved or failed terminally). */
    private suspend fun pollUntilResolved(initiated: QuickConnectInitiateData): Boolean {
        val pollMillis = initiated.pollIntervalSeconds * 1000L
        var remainingMillis = initiated.expiresInSeconds * 1000L
        var multiplier = 1L
        while (remainingMillis > 0) {
            val waitMillis = (pollMillis * multiplier).coerceAtMost(MAX_BACKOFF_MILLIS)
            delay(waitMillis)
            remainingMillis -= waitMillis
            when (val result = authRepository.redeemQuickConnect(initiated.code, initiated.secret)) {
                is ApiResult.Success -> when (result.value.status) {
                    QuickConnectStatus.Approved -> return finishApproved()
                    QuickConnectStatus.Pending -> multiplier = 1
                }
                is ApiResult.Failure -> {
                    val error = result.error
                    // 404 means unknown or expired: only a fresh code can recover.
                    if (error is AppError.Api && error.status == 404) return false
                    multiplier *= 2
                }
            }
        }
        return false
    }

    /** The token is already persisted; keep retrying the user fetch rather than re-pairing. */
    private suspend fun finishApproved(): Boolean {
        while (true) {
            when (val user = authRepository.fetchCurrentUser()) {
                is ApiResult.Success -> {
                    sessionManager.onLoggedIn(user.value)
                    return true
                }
                is ApiResult.Failure -> {
                    if (user.error == AppError.Unauthorized) {
                        authRepository.clearSession()
                        _uiState.value = QuickConnectUiState(
                            QuickConnectPhase.Failed(user.error.toQuickConnectDisplayMessage()),
                        )
                        return true
                    }
                    delay(USER_FETCH_RETRY_MILLIS)
                }
            }
        }
    }

    private companion object {
        const val INITIATE_BACKOFF_MILLIS = 5_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
        const val USER_FETCH_RETRY_MILLIS = 2_000L
        val RETRYABLE_STATUSES = setOf(429, 503)
    }
}
