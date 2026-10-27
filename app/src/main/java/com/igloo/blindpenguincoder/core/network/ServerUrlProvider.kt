package com.igloo.blindpenguincoder.core.network

import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.error.flatMap
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

/**
 * Pairs a response with the API base URL its paths resolve against. The session keeps the address
 * set while the app is signed in, but a sign-out can clear it between a response and its mapping;
 * that reads as a failed load rather than an exception thrown out of the coroutine. Only mappings
 * that build URLs take it — album covers, for one, arrive as the API returns them, and the
 * image loader resolves a server path among them against the origin.
 */
fun <T> ApiResult<T>.withApiBaseUrl(serverUrl: ServerUrlProvider): ApiResult<Pair<T, String>> =
    flatMap { value ->
        serverUrl.current.value?.let { ApiResult.Success(value to it.apiBaseUrl) }
            ?: ApiResult.Failure(AppError.Unexpected("Server address cleared mid-load"))
    }
