package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.data.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

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
        when (val result = authRepository.fetchCurrentUser()) {
            is ApiResult.Success -> _state.value = AppAuthState.Authenticated(result.value)
            is ApiResult.Failure -> when (result.error) {
                AppError.Unauthorized -> {
                    authRepository.clearSession()
                    _state.value = AppAuthState.NeedsLogin(storedAddress)
                }
                else -> _state.value = AppAuthState.NeedsLogin(storedAddress, result.error)
            }
        }
    }

    fun onLoggedIn(user: AuthUser) {
        _state.value = AppAuthState.Authenticated(user)
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
