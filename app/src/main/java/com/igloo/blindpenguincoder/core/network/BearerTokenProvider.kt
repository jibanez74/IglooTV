package com.igloo.blindpenguincoder.core.network

import com.igloo.blindpenguincoder.core.storage.DeviceTokenStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serves the device bearer token to the HTTP client on every request, so reads
 * come from an in-memory cache after the first DataStore load.
 */
class BearerTokenProvider(private val store: DeviceTokenStore) {

    private val mutex = Mutex()
    private var loaded = false
    private var cached: String? = null

    suspend fun token(): String? = mutex.withLock {
        if (!loaded) {
            cached = store.read()
            loaded = true
        }
        cached
    }

    suspend fun set(token: String) = mutex.withLock {
        cached = token
        loaded = true
        store.write(token)
    }

    suspend fun clear() = mutex.withLock {
        cached = null
        loaded = true
        store.clear()
    }
}
