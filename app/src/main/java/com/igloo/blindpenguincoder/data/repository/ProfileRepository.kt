package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.network.ActiveCredential
import com.igloo.blindpenguincoder.core.network.BearerTokenProvider
import com.igloo.blindpenguincoder.core.storage.ProfileStore
import com.igloo.blindpenguincoder.core.storage.ProfileVault
import com.igloo.blindpenguincoder.core.storage.StoredProfile
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.data.model.ProfileSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the sign-in gate needs to know at launch, with no token in sight. */
data class ProfileState(
    val profiles: List<ProfileSummary>,
    val hasPendingToken: Boolean,
    val lastActiveUserId: Long?,
)

/** [replacedToken] is the token a re-pairing displaced, and is worth revoking server-side. */
data class CommitResult(val replacedToken: String?)

/**
 * Owns the profile vault: the only writer, and the only place a stored token is read.
 * Callers get [ProfileSummary]s and get told which profile is active, never the token
 * behind it.
 */
class ProfileRepository(
    private val store: ProfileStore,
    private val tokens: BearerTokenProvider,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** Non-suspending so a 401 arriving from the HTTP pipeline can be attributed instantly. */
    @Volatile
    var activeProfileId: Long? = null
        private set

    suspend fun load(): ProfileState = mutex.withLock {
        store.read().toState()
    }

    /** Makes [userId]'s token the credential for subsequent requests. */
    suspend fun activate(userId: Long): Boolean = mutex.withLock {
        val profile = store.read().profiles.firstOrNull { it.userId == userId } ?: return false
        setCredential(ActiveCredential(userId, profile.token))
        true
    }

    /** Resumes a pairing that minted a token but never reached [commitSignIn]. */
    suspend fun activatePending(): Boolean = mutex.withLock {
        val token = store.read().pendingToken ?: return false
        setCredential(ActiveCredential(profileId = null, token = token))
        true
    }

    /** Persists a freshly minted token before its owner is known, and starts using it. */
    suspend fun setPending(token: String) = mutex.withLock {
        store.update { it.copy(pendingToken = token) }
        setCredential(ActiveCredential(profileId = null, token = token))
    }

    /**
     * Binds the active token to [user]: the profile is created or refreshed, the pending
     * token is consumed, and the session becomes resumable at the next launch.
     */
    suspend fun commitSignIn(user: AuthUser): CommitResult = mutex.withLock {
        var replaced: String? = null
        var committed: String? = null
        store.update { vault ->
            replaced = null
            val existing = vault.profiles.firstOrNull { it.userId == user.id }
            val token = vault.pendingToken ?: existing?.token ?: return@update vault
            if (vault.pendingToken != null && existing != null && existing.token != token) {
                replaced = existing.token
            }
            committed = token
            val profile = StoredProfile(
                userId = user.id,
                token = token,
                name = user.name,
                avatarUrl = user.avatar?.orNull(),
                hasPin = user.hasPin,
                lastUsedAtEpochMillis = clock(),
            )
            vault.copy(
                activeUserId = user.id,
                pendingToken = null,
                profiles = vault.profiles.filterNot { it.userId == user.id } + profile,
            )
        }
        committed?.let { setCredential(ActiveCredential(user.id, it)) }
        CommitResult(replacedToken = replaced)
    }

    /** Drops an unbound token; profiles that already have an owner survive. */
    suspend fun clearPending() = mutex.withLock {
        store.update { it.copy(pendingToken = null) }
        if (activeProfileId == null) setCredential(null)
    }

    suspend fun remove(userId: Long) = mutex.withLock {
        store.update { vault ->
            vault.copy(
                activeUserId = vault.activeUserId?.takeIf { it != userId },
                profiles = vault.profiles.filterNot { it.userId == userId },
            )
        }
        if (activeProfileId == userId) setCredential(null)
    }

    /** Stops acting as the active profile without forgetting it. */
    suspend fun deactivate() = mutex.withLock {
        setCredential(null)
    }

    /** Forgets every profile: the tokens belong to one server and mean nothing on another. */
    suspend fun clearAll() = mutex.withLock {
        store.update { ProfileVault() }
        setCredential(null)
    }

    private suspend fun setCredential(credential: ActiveCredential?) {
        activeProfileId = credential?.profileId
        tokens.set(credential)
    }

    private fun ProfileVault.toState() = ProfileState(
        profiles = profiles
            .sortedByDescending { it.lastUsedAtEpochMillis }
            .map { ProfileSummary(it.userId, it.name, it.avatarUrl, it.hasPin) },
        hasPendingToken = pendingToken != null,
        lastActiveUserId = activeUserId,
    )

    companion object {
        /** Keeps the picker to a single row that never scrolls at any UI scale. */
        const val MAX_PROFILES = 6
    }
}
