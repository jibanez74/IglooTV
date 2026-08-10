package com.igloo.blindpenguincoder.feature.auth

import android.os.SystemClock
import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.image.ImageCache
import com.igloo.blindpenguincoder.core.network.AuthEventBus
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.model.ProfileSummary
import com.igloo.blindpenguincoder.data.repository.AuthRepository
import com.igloo.blindpenguincoder.data.repository.ProfileRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val imageCache: ImageCache = ImageCache.None,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    private val _state = MutableStateFlow<AppAuthState>(AppAuthState.Loading)
    val state: StateFlow<AppAuthState> = _state.asStateFlow()

    private val transitionMutex = Mutex()
    private var lastValidatedAt = Long.MIN_VALUE

    init {
        scope.launch {
            authEvents.unauthorized.collect { profileId ->
                profileId?.let { handleSessionRevoked(it) }
            }
        }
    }

    /** Called once at launch: restores the stored server and picks up where sign-in left off. */
    suspend fun restore() = transitionMutex.withLock {
        val storedUrl = settings.serverUrl.first()
        if (storedUrl == null) {
            _state.value = AppAuthState.NeedsServer(firstRun = true)
            return@withLock
        }
        val storedAddress = ServerAddress.fromApiBaseUrl(storedUrl)
        if (storedAddress == null) {
            settings.clear()
            profiles.clearAll()
            serverUrl.set(null)
            _state.value = AppAuthState.NeedsServer()
            return@withLock
        }
        serverUrl.set(storedAddress)

        val stored = profiles.load()
        when {
            // A token minted but never bound to a user: finishing it is always right,
            // pairing again would mint a second device for one person.
            stored.hasPendingToken -> {
                if (profiles.activatePending()) {
                    finishOrGateLocked(revokedName = null, expectedProfileId = null)
                } else {
                    gateLocked()
                }
            }

            stored.profiles.isEmpty() -> _state.value = AppAuthState.NeedsLogin(storedAddress)

            // One profile with nothing to ask: launching straight into it beats a
            // single-item picker.
            stored.profiles.size == 1 -> {
                val only = stored.profiles.single()
                if (!profiles.activate(only.userId)) {
                    gateLocked()
                } else if (only.hasPin) {
                    _state.value = AppAuthState.NeedsPin(storedAddress, only)
                } else {
                    finishOrGateLocked(
                        revokedName = only.name,
                        expectedProfileId = only.userId,
                    )
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
    suspend fun completeSignIn(): SignInResult = transitionMutex.withLock {
        completeSignInLocked(expectedProfileId = profiles.activeProfileId)
    }

    private suspend fun completeSignInLocked(expectedProfileId: Long?): SignInResult =
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
                forgetExpectedCredentialLocked(expectedProfileId)
                SignInResult.Revoked
            } else {
                SignInResult.Failed(result.error)
            }
        }

    /** Profile picker: sign in as [profile], which has no PIN to ask for. */
    suspend fun signInAs(profile: ProfileSummary): SignInResult = transitionMutex.withLock {
        if (!profiles.activate(profile.userId)) {
            gateLocked()
            return@withLock SignInResult.Revoked
        }
        val result = completeSignInLocked(expectedProfileId = profile.userId)
        // A transient failure keeps the picker up so the tile can simply be retried.
        if (result == SignInResult.Revoked) {
            gateLocked(notice = revokedNotice(profile.name))
        }
        result
    }

    /** Profile picker: [profile] is PIN-protected, so activate it and ask. */
    suspend fun requirePin(profile: ProfileSummary) = transitionMutex.withLock {
        val address = serverUrl.current.value ?: return@withLock
        if (profiles.activate(profile.userId)) {
            _state.value = AppAuthState.NeedsPin(address, profile)
        } else {
            gateLocked()
        }
    }

    /** The server rejected the credential of whoever is signed in right now. */
    suspend fun onActiveSessionRevoked() {
        val expectedProfileId = when (val current = _state.value) {
            is AppAuthState.Authenticated -> current.user.id
            is AppAuthState.NeedsPin -> current.profile.userId
            else -> return
        }
        handleSessionRevoked(expectedProfileId)
    }

    private suspend fun handleSessionRevoked(expectedProfileId: Long) =
        transitionMutex.withLock {
            val current = _state.value
            val currentProfileId = when (current) {
                is AppAuthState.Authenticated -> current.user.id
                is AppAuthState.NeedsPin -> current.profile.userId
                else -> return@withLock
            }
            // A slow 401 from an earlier profile must never remove whoever is active now.
            if (currentProfileId != expectedProfileId) return@withLock
            val activeProfileId = profiles.activeProfileId
            if (activeProfileId != null && activeProfileId != expectedProfileId) return@withLock

            val name = when (current) {
                is AppAuthState.Authenticated -> current.user.name
                is AppAuthState.NeedsPin -> current.profile.name
            }
            if (activeProfileId == expectedProfileId) profiles.remove(expectedProfileId)
            gateLocked(notice = revokedNotice(name))
        }

    /** Re-checks the session after the app comes back to the foreground. */
    suspend fun revalidateActive() = transitionMutex.withLock {
        val current = _state.value as? AppAuthState.Authenticated ?: return@withLock
        if (elapsed() - lastValidatedAt < REVALIDATE_INTERVAL_MILLIS) return@withLock
        when (completeSignInLocked(expectedProfileId = current.user.id)) {
            SignInResult.Authenticated -> Unit
            SignInResult.Revoked -> gateLocked(notice = revokedNotice(current.user.name))
            // An offline TV keeps watching; only an outright rejection ends the session.
            is SignInResult.Failed -> Unit
        }
    }

    /** Pair or log in an additional user without disturbing the profiles already here. */
    suspend fun addProfile() = transitionMutex.withLock {
        val address = serverUrl.current.value ?: return@withLock
        profiles.clearPending()
        profiles.deactivate()
        _state.value = AppAuthState.NeedsLogin(address, canCancel = true)
    }

    /** Back out of "add a user" without keeping a half-finished pairing. */
    suspend fun cancelAddProfile() = transitionMutex.withLock {
        profiles.clearPending()
        profiles.deactivate()
        gateLocked()
    }

    /** Hand the TV to someone else; this profile stays paired. */
    suspend fun switchProfile() = transitionMutex.withLock {
        profiles.deactivate()
        gateLocked()
    }

    /**
     * Sign out for real: revoke this device token server-side and drop the profile from this TV.
     *
     * Only the profile signing out is affected — the request carries its captured token and no
     * other. Everyone else on this TV keeps their token.
     *
     * Under [transitionMutex] like every other transition, so a queued revocation cannot interleave
     * with it. The local half is unconditional: a revoke that could not be delivered must not leave
     * a live credential on a shared TV, so the profile goes either way and the gate says what the
     * server did not hear. A 401 is *success* — the token it would have revoked is already gone,
     * which is exactly what this call wanted.
     */
    suspend fun logout() {
        val expectedProfileId =
            (_state.value as? AppAuthState.Authenticated)?.user?.id ?: return

        // This child belongs to the application, not the Activity or ViewModel waiting below.
        // UNDISPATCHED makes an uncontended local cleanup begin before another UI transition can
        // race ahead; join remains cancellable without propagating that cancellation to the work.
        val operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transitionMutex.withLock {
                logoutLocked(expectedProfileId)
            }
        }
        operation.join()
    }

    private suspend fun logoutLocked(expectedProfileId: Long) {
        val current = _state.value as? AppAuthState.Authenticated ?: return
        if (current.user.id != expectedProfileId) return
        val activeProfileId = profiles.activeProfileId
        if (activeProfileId != null && activeProfileId != expectedProfileId) return

        // Persistence is the security boundary. It finishes even if the waiting screen is torn
        // down, and it happens before a network request that may take the full timeout. The image
        // cache goes with it: this profile's avatar is the only trace of it Coil keeps on disk.
        val credential = withContext(NonCancellable) {
            val removed = profiles.removeForSignOut(expectedProfileId)
            imageCache.clear()
            removed
        }
        val revoked = if (credential == null) {
            true
        } else {
            when (
                val result = authRepository.logout(bearerOverride = credential.token)
            ) {
                is ApiResult.Success -> true
                is ApiResult.Failure -> result.error == AppError.Unauthorized
            }
        }
        gateLocked(notice = if (revoked) null else UNDELIVERED_REVOKE_NOTICE)
    }

    /** Server setup finished successfully; move on to sign-in. */
    fun onServerChanged(address: ServerAddress) {
        _state.value = AppAuthState.NeedsLogin(address)
    }

    /** "Change server" from the sign-in or picker screens. */
    fun requireServerChange() {
        _state.value = AppAuthState.NeedsServer(serverUrl.current.value?.origin.orEmpty())
    }

    private suspend fun finishOrGateLocked(revokedName: String?, expectedProfileId: Long?) {
        when (val result = completeSignInLocked(expectedProfileId)) {
            SignInResult.Authenticated -> Unit
            SignInResult.Revoked -> gateLocked(notice = revokedName?.let(::revokedNotice))
            // The token survives, so the gate offers a retry rather than pairing again.
            is SignInResult.Failed -> gateLocked(restoreError = result.error)
        }
    }

    private suspend fun forgetExpectedCredentialLocked(expectedProfileId: Long?) {
        if (profiles.activeProfileId != expectedProfileId) return
        if (expectedProfileId == null) {
            profiles.clearPending()
        } else {
            profiles.remove(expectedProfileId)
        }
    }

    /** Publishes the picker, or the sign-in screen when no profile is left to pick. */
    private suspend fun gateLocked(notice: String? = null, restoreError: AppError? = null) {
        val address = serverUrl.current.value
        if (address == null) {
            _state.value = AppAuthState.NeedsServer()
            return
        }
        val stored = profiles.load()
        _state.value = if (stored.profiles.isEmpty()) {
            AppAuthState.NeedsLogin(address, restoreError = restoreError, notice = notice)
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

    internal companion object {
        private const val REVALIDATE_INTERVAL_MILLIS = 5 * 60 * 1000L

        /** A sign-out the server never heard: local state is clean, its record may not be. */
        const val UNDELIVERED_REVOKE_NOTICE =
            "Signed out on this TV. The server couldn't be reached, so it may still list " +
                "this TV as signed in."
    }
}
