package com.igloo.blindpenguincoder.core.network

import com.igloo.blindpenguincoder.core.config.ServerAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the active normalized server origin and API base URL. */
class ServerUrlProvider {
    private val state = MutableStateFlow<ServerAddress?>(null)
    val current: StateFlow<ServerAddress?> = state.asStateFlow()

    fun set(address: ServerAddress?) {
        state.value = address
    }

    fun require(): ServerAddress =
        checkNotNull(state.value) { "Server address requested before setup completed" }
}

/**
 * Whether [url] is on the server at [origin]. A bearer token goes only there: the image loader
 * and the player bypass the Ktor client, avatars are arbitrary absolute URLs, and a credential
 * sent to a foreign host is a credential leaked.
 */
internal fun isIglooServerUrl(url: String?, origin: String?): Boolean =
    url != null && origin != null && url.startsWith("$origin/")
