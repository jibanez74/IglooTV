package com.igloo.blindpenguincoder.feature.auth

import android.os.SystemClock
import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.network.AuthEventBus
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.model.ProfileSummary
import com.igloo.blindpenguincoder.data.repository.AuthRepository
import com.igloo.blindpenguincoder.data.repository.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Outcome of exchanging the active device token for the signed-in user. */
sealed interface SignInResult {
    data object Authenticated : SignInResult

    /** The token was rejected and has been forgotten; the user must pair or log in again. */
    data object Revoked : SignInResult

    /** Transient failure; the token is still stored, so the caller may retry. */
    data class Failed(val error: AppError) : SignInResult
}

/**
 * Decides which of the auth screens the app is showing. One device token belongs to one
 * user, so "multiple profiles" means several stored tokens and a picker; every path
 * still ends at [completeSignIn], which binds whichever token is active to its owner.
 */
class SessionManager(
    private val authRepository: AuthRepository,
    private val profiles: ProfileRepository,
    private val settings: ServerSettingsStore,
    private val serverUrl: ServerUrlProvider,
    authEvents: AuthEventBus,
    private val scope: CoroutineScope,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    private val _state = MutableStateFlow<AppAuthState>(AppAuthState.Loading)
    val state: StateFlow<AppAuthState> = _state.asStateFlow()

    private val transitionMutex = Mutex()
    private var lastValidatedAt = Long.MIN_VALUE

    init {
        scope.launch {
            authEvents.unauthorized.collect { profileId ->
                // A slow request from a profile that is no longer active must not sign out
                // whoever is watching now.
                if (profileId != null && profileId == profiles.activeProfileId) {
                    onActiveSessionRevoked()
                }
            }
        }
    }

    /** Called once at launch: restores the stored server and picks up where sign-in left off. */
    suspend fun restore() {
        val storedUrl = settings.serverUrl.first()
        if (storedUrl == null) {
            _state.value = AppAuthState.NeedsServer(firstRun = true)
            return
        }
        val storedAddress = ServerAddress.fromApiBaseUrl(storedUrl)
        if (storedAddress == null) {
            settings.clear()
            profiles.clearAll()
            serverUrl.set(null)
            _state.value = AppAuthState.NeedsServer()
            return
        }
        serverUrl.set(storedAddress)

        val stored = profiles.load()
        when {
            // A token minted but never bound to a user: finishing it is always right,
            // pairing again would mint a second device for one person.
            stored.hasPendingToken -> {
                profiles.activatePending()
                finishOrGate(revokedName = null)
            }

            stored.profiles.isEmpty() -> _state.value = AppAuthState.NeedsLogin(storedAddress)

            // One profile with nothing to ask: launching straight into it beats a
            // single-item picker.
            stored.profiles.size == 1 -> {
                val only = stored.profiles.single()
                if (!profiles.activate(only.userId)) {
                    gate()
                } else if (only.hasPin) {
                    _state.value = AppAuthState.NeedsPin(storedAddress, only)
                } else {
                    finishOrGate(revokedName = only.name)
                }
            }

            else -> _state.value = AppAuthState.ChooseProfile(
                serverAddress = storedAddress,
                profiles = stored.profiles,
                initialFocusUserId = stored.lastActiveUserId,
            )
        }
    }

    /**
     * Exchanges the active device token for its owner and publishes
     * [AppAuthState.Authenticated]. Shared by every sign-in path, since pairing, password
     * login, profile selection and revalidation all finish the same way.
     */
    suspend fun completeSignIn(): SignInResult =
        when (val result = authRepository.fetchCurrentUser()) {
            is ApiResult.Success -> {
                val user = result.value
                val commit = profiles.commitSignIn(user)
                // A re-pairing leaves the old device registered; tidying it up is best effort.
                commit.replacedToken?.let { replaced ->
                    scope.launch { authRepository.logout(bearerOverride = replaced) }
                }
                lastValidatedAt = elapsed()
                _state.value = AppAuthState.Authenticated(user)
                SignInResult.Authenticated
            }
            is ApiResult.Failure -> if (result.error == AppError.Unauthorized) {
                forgetActiveCredential()
                SignInResult.Revoked
            } else {
                SignInResult.Failed(result.error)
            }
        }

    /** Profile picker: sign in as [profile], which has no PIN to ask for. */
    suspend fun signInAs(profile: ProfileSummary): SignInResult {
        if (!profiles.activate(profile.userId)) {
            gate()
            return SignInResult.Revoked
        }
        val result = completeSignIn()
        // A transient failure keeps the picker up so the tile can simply be retried.
        if (result == SignInResult.Revoked) gate(notice = revokedNotice(profile.name))
        return result
    }

    /** Profile picker: [profile] is PIN-protected, so activate it and ask. */
    suspend fun requirePin(profile: ProfileSummary) {
        val address = serverUrl.current.value ?: return
        if (profiles.activate(profile.userId)) {
            _state.value = AppAuthState.NeedsPin(address, profile)
        } else {
            gate()
        }
    }

    /** The server rejected the credential of whoever is signed in right now. */
    suspend fun onActiveSessionRevoked() = transitionMutex.withLock {
        val current = _state.value
        if (current !is AppAuthState.Authenticated && current !is AppAuthState.NeedsPin) {
            return@withLock
        }
        val name = when (current) {
            is AppAuthState.Authenticated -> current.user.name
            is AppAuthState.NeedsPin -> current.profile.name
        }
        forgetActiveCredential()
        gate(notice = name?.let(::revokedNotice))
    }

    /** Re-checks the session after the app comes back to the foreground. */
    suspend fun revalidateActive() {
        if (_state.value !is AppAuthState.Authenticated) return
        if (elapsed() - lastValidatedAt < REVALIDATE_INTERVAL_MILLIS) return
        val name = (_state.value as AppAuthState.Authenticated).user.name
        when (completeSignIn()) {
            SignInResult.Authenticated -> Unit
            SignInResult.Revoked -> gate(notice = revokedNotice(name))
            // An offline TV keeps watching; only an outright rejection ends the session.
            is SignInResult.Failed -> Unit
        }
    }

    /** Pair or log in an additional user without disturbing the profiles already here. */
    suspend fun addProfile() {
        val address = serverUrl.current.value ?: return
        profiles.clearPending()
        profiles.deactivate()
        _state.value = AppAuthState.NeedsLogin(address, canCancel = true)
    }

    /** Back out of "add a user" without keeping a half-finished pairing. */
    suspend fun cancelAddProfile() {
        profiles.clearPending()
        profiles.deactivate()
        gate()
    }

    /** Hand the TV to someone else; this profile stays paired. */
    suspend fun switchProfile() {
        profiles.deactivate()
        gate()
    }

    /** Sign out for real: the device token is revoked and the profile leaves this TV. */
    suspend fun logout() {
        authRepository.logout()
        forgetActiveCredential()
        gate()
    }

    /** Server setup finished successfully; move on to sign-in. */
    fun onServerChanged(address: ServerAddress) {
        _state.value = AppAuthState.NeedsLogin(address)
    }

    /** "Change server" from the sign-in or picker screens. */
    fun requireServerChange() {
        _state.value = AppAuthState.NeedsServer(serverUrl.current.value?.origin.orEmpty())
    }

    private suspend fun finishOrGate(revokedName: String?) {
        when (val result = completeSignIn()) {
            SignInResult.Authenticated -> Unit
            SignInResult.Revoked -> gate(notice = revokedName?.let(::revokedNotice))
            // The token survives, so the gate offers a retry rather than pairing again.
            is SignInResult.Failed -> gate(restoreError = result.error)
        }
    }

    private suspend fun forgetActiveCredential() {
        val activeId = profiles.activeProfileId
        if (activeId != null) profiles.remove(activeId) else profiles.clearPending()
    }

    /** Publishes the picker, or the sign-in screen when no profile is left to pick. */
    private suspend fun gate(notice: String? = null, restoreError: AppError? = null) {
        val address = serverUrl.current.value
        if (address == null) {
            _state.value = AppAuthState.NeedsServer()
            return
        }
        val stored = profiles.load()
        _state.value = if (stored.profiles.isEmpty()) {
            AppAuthState.NeedsLogin(address, restoreError = restoreError)
        } else {
            AppAuthState.ChooseProfile(
                serverAddress = address,
                profiles = stored.profiles,
                initialFocusUserId = stored.lastActiveUserId,
                notice = notice,
                restoreError = restoreError,
            )
        }
    }

    private fun revokedNotice(name: String) = "$name's session expired. Sign in again."

    private companion object {
        const val REVALIDATE_INTERVAL_MILLIS = 5 * 60 * 1000L
    }
}
