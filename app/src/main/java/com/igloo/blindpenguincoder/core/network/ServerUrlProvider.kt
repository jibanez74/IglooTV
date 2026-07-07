package com.igloo.blindpenguincoder.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds the current normalized API base URL, e.g. `http://10.0.2.2:8080/api`. */
class ServerUrlProvider {
    private val state = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = state.asStateFlow()

    fun set(url: String?) {
        state.value = url
    }

    fun require(): String =
        checkNotNull(state.value) { "Server URL requested before setup completed" }
}
