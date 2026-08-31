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

    /**
     * The server says this user has a PIN, so [AppAuthState.NeedsPin] has been published instead.
     * The token is valid and still active; only the gate is left.
     */
    data object PinRequired : SignInResult

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

    /**
     * A launch: the app is starting from scratch, so the gate is re-resolved from nothing.
     *
     * [SessionManager] is a process singleton and outlives the Activity, so a relaunch over a
     * surviving process finds the previous session still published. Dropping back to
     * [AppAuthState.Loading] synchronously — before `setContent`, from `MainActivity.onCreate` —
     * is what keeps the signed-in app from composing and firing its user-scoped fetches on the
     * first frame of the new Activity, ahead of the PIN gate this call is about to publish.
     *
     * Not under [transitionMutex], because it cannot suspend and still beat that first
     * composition. Nothing else can be mid-transition while an Activity is being created, and
     * [restoreOnLaunch] settles the real state under the lock a moment later regardless.
     */
    fun beginLaunch() {
        _state.value = AppAuthState.Loading
    }

    /**
     * The launch entry point, paired with [beginLaunch]. A no-op unless the app is still at
     * [AppAuthState.Loading], so an Activity *recreation* — a font scale, ui mode or locale
     * change, which the manifest deliberately lets through — resumes the session it already had
     * instead of sending the user back to the keypad mid-session (design-system section 11.1.2).
     */
    suspend fun restoreOnLaunch() = transitionMutex.withLock {
        if (_state.value !is AppAuthState.Loading) return@withLock
        restoreLocked()
    }

    /**
     * Retry after a launch that could not reach the server: the gate screens offer it, so unlike
     * [restoreOnLaunch] it runs from whatever state the gate is resting in.
     */
    suspend fun restore() = transitionMutex.withLock { restoreLocked() }

    /** Restores the stored server and picks up where sign-in left off. */
    private suspend fun restoreLocked() {
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
                if (profiles.activatePending()) {
                    finishOrGateLocked(revokedName = null, expectedProfileId = null)
                } else {
                    gateLocked()
                }
            }

            stored.profiles.isEmpty() -> _state.value = AppAuthState.NeedsLogin(storedAddress)

            // One profile with nothing to ask: launching straight into it beats a
            // single-item picker. Whether there is a PIN to ask for is the server's answer,
            // not the stored flag's, so it comes out of the sign-in itself.
            stored.profiles.size == 1 -> {
                val only = stored.profiles.single()
                if (!profiles.activate(only.userId)) {
                    gateLocked()
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
     * [AppAuthState.Authenticated].
     *
     * For the paths where the user has just proved who they are — a password login, a fresh
     * pairing, a PIN that has this moment been verified — so it never gates on the PIN again.
     * Resuming a stored token goes through [signInAs] or [restore] instead.
     */
    suspend fun completeSignIn(): SignInResult = transitionMutex.withLock {
        completeSignInLocked(
            expectedProfileId = profiles.activeProfileId,
            enforcePin = false,
        )
    }

    /**
     * [enforcePin] asks the *server* whether this user has a PIN, rather than trusting
     * [ProfileSummary.hasPin], which is only as fresh as the last completed sign-in. A PIN set
     * elsewhere after this TV was paired would otherwise be skipped on the next switch, and one
     * removed elsewhere would strand the user on a keypad the backend answers with "no PIN is set".
     */
    private suspend fun completeSignInLocked(
        expectedProfileId: Long?,
        enforcePin: Boolean,
    ): SignInResult =
        when (val result = authRepository.fetchCurrentUser()) {
            is ApiResult.Success -> {
                val user = result.value
                // Both outcomes carry the address: the PIN gate shows it, and Authenticated needs
                // it to resolve avatars. gateLocked resolves a missing one to server setup rather
                // than signing in anyway. Unreachable in practice — the screen that got here
                // needed an address to render — but neither a gate nor a session is safe to fake.
                val address = serverUrl.current.value
                if (address == null) {
                    gateLocked()
                    SignInResult.Failed(AppError.Unexpected("no server address"))
                } else if (enforcePin && user.hasPin) {
                    // Nothing is committed yet: the vault's active user must not advance until
                    // the sign-in completes, so a kill at the keypad returns to the gate.
                    _state.value = AppAuthState.NeedsPin(
                        serverAddress = address,
                        profile = ProfileSummary(
                            userId = user.id,
                            name = user.name,
                            avatarUrl = user.avatar,
                            hasPin = true,
                        ),
                    )
                    SignInResult.PinRequired
                } else {
                    val commit = profiles.commitSignIn(user)
                    // A re-pairing leaves the old device registered; tidying it up is best effort.
                    commit.replacedToken?.let { replaced ->
                        scope.launch { authRepository.logout(bearerOverride = replaced) }
                    }
                    lastValidatedAt = elapsed()
                    _state.value = AppAuthState.Authenticated(address, user)
                    SignInResult.Authenticated
                }
            }
            is ApiResult.Failure -> if (result.error == AppError.Unauthorized) {
                forgetExpectedCredentialLocked(expectedProfileId)
                SignInResult.Revoked
            } else {
                SignInResult.Failed(result.error)
            }
        }

    /**
     * Profile picker: resume [profile]'s stored token. Ends at the library, or at the PIN gate
     * when the server says this user has one.
     */
    suspend fun signInAs(profile: ProfileSummary): SignInResult = transitionMutex.withLock {
        if (!profiles.activate(profile.userId)) {
            gateLocked()
            return@withLock SignInResult.Revoked
        }
        val result = completeSignInLocked(
            expectedProfileId = profile.userId,
            enforcePin = true,
        )
        // A transient failure keeps the picker up so the tile can simply be retried.
        if (result == SignInResult.Revoked) {
            gateLocked(notice = revokedNotice(profile.name))
        }
        result
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
        // Not gated on the PIN: a PIN set while this profile is watching must not eject it
        // mid-session. The gate belongs to picking a profile, not to holding one.
        val result = completeSignInLocked(
            expectedProfileId = current.user.id,
            enforcePin = false,
        )
        when (result) {
            SignInResult.Authenticated, SignInResult.PinRequired -> Unit
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

    /**
     * Hand the TV to someone else; this profile stays paired.
     *
     * On the application scope for the same reason [logout] is: the credential is dropped before
     * the gate is published, so a caller torn down in between — an Activity recreated by a UI
     * scale change while this waits on the mutex — would leave the app authenticated with no
     * token. The next request would 401 and take the profile off this TV, which is the one thing
     * a switch must never do.
     */
    suspend fun switchProfile() {
        val operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            transitionMutex.withLock {
                profiles.deactivate()
                gateLocked()
            }
        }
        operation.join()
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

    /** Launch only, which is a resumed token either way, so the PIN gate applies. */
    private suspend fun finishOrGateLocked(revokedName: String?, expectedProfileId: Long?) {
        val result = completeSignInLocked(expectedProfileId, enforcePin = true)
        when (result) {
            // PinRequired has already published the gate it wants.
            SignInResult.Authenticated, SignInResult.PinRequired -> Unit
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
