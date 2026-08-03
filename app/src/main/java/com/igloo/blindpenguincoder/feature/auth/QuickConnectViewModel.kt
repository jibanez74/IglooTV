package com.igloo.blindpenguincoder.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.QuickConnectInitiateData
import com.igloo.blindpenguincoder.data.model.QuickConnectStatus
import com.igloo.blindpenguincoder.data.repository.AuthRepository
import com.igloo.blindpenguincoder.data.repository.ProfileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface QuickConnectPhase {
    data object RequestingCode : QuickConnectPhase
    data class CodeReady(val code: String) : QuickConnectPhase

    /** Approved on the other device; exchanging the stored token for the user. */
    data object SigningIn : QuickConnectPhase
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
    private val profileRepository: ProfileRepository,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuickConnectUiState())
    val uiState: StateFlow<QuickConnectUiState> = _uiState.asStateFlow()

    private var pairingJob: Job? = null

    fun start() {
        if (pairingJob?.isActive == true) return
        _uiState.value = QuickConnectUiState(QuickConnectPhase.RequestingCode)
        pairingJob = viewModelScope.launch {
            // A pending token outlives a failed restore or a half-finished pairing. Finishing
            // that sign-in is always right; pairing again would mint a second device for one
            // user.
            if (profileRepository.activatePending()) finishApproved() else runPairing()
        }
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
        var backoffMillis = INITIATE_INITIAL_BACKOFF_MILLIS
        var attemptsLeft = MAX_INITIATE_ATTEMPTS
        while (true) {
            when (val result = authRepository.initiateQuickConnect()) {
                is ApiResult.Success -> return result.value
                is ApiResult.Failure -> {
                    val error = result.error
                    val retryable = error is AppError.Api && error.status in RETRYABLE_STATUSES
                    // A busy server is worth waiting out, but not silently and not forever.
                    if (retryable && --attemptsLeft > 0) {
                        delay(backoffMillis)
                        backoffMillis = (backoffMillis * 2).coerceAtMost(
                            MAX_INITIATE_BACKOFF_MILLIS,
                        )
                    } else {
                        fail(error)
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
        var consecutiveFailures = 0
        while (remainingMillis > 0) {
            val waitMillis = quickConnectPollDelayMillis(pollMillis, consecutiveFailures)
            delay(waitMillis)
            remainingMillis -= waitMillis
            when (val result = authRepository.redeemQuickConnect(initiated.code, initiated.secret)) {
                is ApiResult.Success -> when (result.value.status) {
                    QuickConnectStatus.Approved -> return finishApproved()
                    QuickConnectStatus.Pending -> {
                        consecutiveFailures = 0
                    }
                }
                is ApiResult.Failure -> {
                    val error = result.error
                    // 404 means unknown or expired: only a fresh code can recover.
                    if (error is AppError.Api && error.status == 404) return false
                    if (!error.isRetryableRedeemFailure()) {
                        fail(error)
                        return true
                    }
                    consecutiveFailures += 1
                    if (consecutiveFailures >= MAX_REDEEM_FAILURES) {
                        fail(error)
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * The token is already persisted, so a transient failure here is retried rather than
     * re-paired. Always returns true: pairing itself is done either way.
     */
    private suspend fun finishApproved(): Boolean {
        _uiState.value = QuickConnectUiState(QuickConnectPhase.SigningIn)
        var attemptsLeft = MAX_USER_FETCH_ATTEMPTS
        while (true) {
            when (val result = sessionManager.completeSignIn()) {
                SignInResult.Authenticated -> return true
                SignInResult.Revoked -> {
                    fail(AppError.Unauthorized)
                    return true
                }
                is SignInResult.Failed -> {
                    // Give up eventually: an unreachable server must not look like a hung screen.
                    if (--attemptsLeft <= 0) {
                        fail(result.error)
                        return true
                    }
                    delay(USER_FETCH_RETRY_MILLIS)
                }
            }
        }
    }

    private fun fail(error: AppError) {
        _uiState.value = QuickConnectUiState(
            QuickConnectPhase.Failed(error.toQuickConnectDisplayMessage()),
        )
    }

    private companion object {
        const val INITIATE_INITIAL_BACKOFF_MILLIS = 5_000L
        const val MAX_INITIATE_BACKOFF_MILLIS = 30_000L
        const val USER_FETCH_RETRY_MILLIS = 2_000L
        const val MAX_INITIATE_ATTEMPTS = 5
        const val MAX_REDEEM_FAILURES = 5
        const val MAX_USER_FETCH_ATTEMPTS = 6
        val RETRYABLE_STATUSES = setOf(429, 503)
    }
}

private const val POLL_INITIAL_BACKOFF_MILLIS = 2_000L
private const val MAX_POLL_BACKOFF_MILLIS = 30_000L

internal fun quickConnectPollDelayMillis(
    baseIntervalMillis: Long,
    consecutiveFailures: Int,
): Long {
    if (consecutiveFailures <= 0) return baseIntervalMillis
    val shift = (consecutiveFailures - 1).coerceAtMost(30)
    val backoff = (POLL_INITIAL_BACKOFF_MILLIS * (1L shl shift))
        .coerceAtMost(MAX_POLL_BACKOFF_MILLIS)
    return baseIntervalMillis + backoff
}

private fun AppError.isRetryableRedeemFailure(): Boolean = when (this) {
    AppError.Network, AppError.Timeout -> true
    is AppError.Api -> status == 429 || status in 500..599
    else -> false
}
