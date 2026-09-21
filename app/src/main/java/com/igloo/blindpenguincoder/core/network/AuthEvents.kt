package com.igloo.blindpenguincoder.core.network

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Carries "the server rejected our credential" from the HTTP client to the session
 * state machine, so a 401 on any endpoint — not just the auth ones — returns the user
 * to sign-in.
 */
class AuthEventBus {

    private val _unauthorized = MutableSharedFlow<Long?>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The profile whose token was rejected, or null when a pending token was rejected. */
    val unauthorized: SharedFlow<Long?> = _unauthorized.asSharedFlow()

    /** Never suspends: this runs inside the Ktor pipeline. */
    fun signalUnauthorized(profileId: Long?) {
        _unauthorized.tryEmit(profileId)
    }
}
