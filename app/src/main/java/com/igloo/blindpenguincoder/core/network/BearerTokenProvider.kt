package com.igloo.blindpenguincoder.core.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The credential every authenticated request currently carries. [profileId] is null
 * while the token is pending — minted, but not yet exchanged for a user id.
 */
data class ActiveCredential(
    val profileId: Long?,
    val token: String,
)

/** Supplies the active credential to the HTTP client without exposing where it is stored. */
fun interface DeviceCredentialSource {
    suspend fun current(): ActiveCredential?
}

/**
 * Holds the credential the HTTP client attaches. Persistence belongs to the profile
 * vault; this only decides who the app is acting as right now.
 */
class BearerTokenProvider : DeviceCredentialSource {

    private val mutex = Mutex()
    private var active: ActiveCredential? = null

    override suspend fun current(): ActiveCredential? = mutex.withLock { active }

    suspend fun set(credential: ActiveCredential?) = mutex.withLock { active = credential }
}
