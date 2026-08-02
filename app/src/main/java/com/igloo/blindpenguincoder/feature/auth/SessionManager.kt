package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** Outcome of exchanging a freshly stored device token for the signed-in user. */
sealed interface SignInResult {
    data object Authenticated : SignInResult

    /** The token was rejected and has been cleared; the user must pair or log in again. */
    data object Revoked : SignInResult

    /** Transient failure; the token is still stored, so the caller may retry. */
    data class Failed(val error: AppError) : SignInResult
}

class SessionManager(
    private val authRepository: AuthRepository,
    private val settings: ServerSettingsStore,
    private val serverUrl: ServerUrlProvider,
) {
    private val _state = MutableStateFlow<AppAuthState>(AppAuthState.Loading)
    val state: StateFlow<AppAuthState> = _state.asStateFlow()

    /** Called once at launch: restores the stored server and session, if any. */
    suspend fun restore() {
        val storedUrl = settings.serverUrl.first()
        if (storedUrl == null) {
            _state.value = AppAuthState.NeedsServer()
            return
        }
        val storedAddress = ServerAddress.fromApiBaseUrl(storedUrl)
        if (storedAddress == null) {
            settings.clear()
            authRepository.clearSession()
            serverUrl.set(null)
            _state.value = AppAuthState.NeedsServer()
            return
        }
        serverUrl.set(storedAddress)

        if (!authRepository.hasToken()) {
            _state.value = AppAuthState.NeedsLogin(storedAddress)
            return
        }
        when (val result = completeSignIn()) {
            SignInResult.Authenticated -> Unit
            SignInResult.Revoked -> _state.value = AppAuthState.NeedsLogin(storedAddress)
            // The token survives, so the sign-in screen resumes it rather than pairing again.
            is SignInResult.Failed ->
                _state.value = AppAuthState.NeedsLogin(storedAddress, result.error)
        }
    }

    /**
     * Exchanges the stored device token for the current user and publishes
     * [AppAuthState.Authenticated] on success. Shared by every sign-in path, since pairing and
     * password login both finish the same way once a token exists.
     */
    suspend fun completeSignIn(): SignInResult =
        when (val result = authRepository.fetchCurrentUser()) {
            is ApiResult.Success -> {
                _state.value = AppAuthState.Authenticated(result.value)
                SignInResult.Authenticated
            }
            is ApiResult.Failure -> if (result.error == AppError.Unauthorized) {
                authRepository.clearSession()
                SignInResult.Revoked
            } else {
                SignInResult.Failed(result.error)
            }
        }

    suspend fun logout() {
        authRepository.logout()
        val address = serverUrl.current.value
        _state.value = if (address != null) {
            AppAuthState.NeedsLogin(address)
        } else {
            AppAuthState.NeedsServer()
        }
    }

    /** Server setup finished successfully; move on to login. */
    fun onServerChanged(address: ServerAddress) {
        _state.value = AppAuthState.NeedsLogin(address)
    }

    /** "Change server" from the login screen. */
    fun requireServerChange() {
        _state.value = AppAuthState.NeedsServer(serverUrl.current.value?.origin.orEmpty())
    }
}
