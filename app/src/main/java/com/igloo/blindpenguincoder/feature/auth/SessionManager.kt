package com.igloo.blindpenguincoder.feature.auth

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
            _state.value = AppAuthState.NeedsServer
            return
        }
        serverUrl.set(storedUrl)

        when (val result = authRepository.fetchCurrentUser()) {
            is ApiResult.Success -> _state.value = AppAuthState.Authenticated(result.value)
            is ApiResult.Failure -> when (result.error) {
                AppError.Unauthorized -> {
                    authRepository.clearSession()
                    _state.value = AppAuthState.NeedsLogin(storedUrl)
                }
                else -> _state.value = AppAuthState.NeedsLogin(storedUrl, result.error)
            }
        }
    }

    fun onLoggedIn(user: AuthUser) {
        _state.value = AppAuthState.Authenticated(user)
    }

    suspend fun logout() {
        authRepository.logout()
        val url = serverUrl.current.value
        _state.value = if (url != null) AppAuthState.NeedsLogin(url) else AppAuthState.NeedsServer
    }

    /** Server setup finished successfully; move on to login. */
    fun onServerChanged() {
        val url = serverUrl.require()
        _state.value = AppAuthState.NeedsLogin(url)
    }

    /** "Change server" from the login screen. */
    fun requireServerChange() {
        _state.value = AppAuthState.NeedsServer
    }
}
