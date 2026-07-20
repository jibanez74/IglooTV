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
