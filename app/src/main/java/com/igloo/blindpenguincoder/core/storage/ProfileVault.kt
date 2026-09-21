package com.igloo.blindpenguincoder.core.storage

import kotlinx.serialization.Serializable

/**
 * One paired user on this TV. [token] is a secret: never log it, and never let it
 * leave the storage and repository layers.
 */
@Serializable
data class StoredProfile(
    val userId: Long,
    val token: String,
    val name: String,
    val avatarUrl: String? = null,
    val hasPin: Boolean = false,
    val lastUsedAtEpochMillis: Long = 0L,
)

/**
 * Everything the app persists about who is signed in. Held as a single encrypted
 * blob so adding a profile is one atomic write, and so names and avatars are
 * protected alongside the tokens they belong to.
 */
@Serializable
data class ProfileVault(
    /** Set only once a sign-in completes, so a kill mid-PIN returns to the picker. */
    val activeUserId: Long? = null,
    /** A token that has been minted but not yet exchanged for a user id. */
    val pendingToken: String? = null,
    val profiles: List<StoredProfile> = emptyList(),
)
