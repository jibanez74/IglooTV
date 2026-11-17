package com.igloo.blindpenguincoder.data.api

import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/** [path] (starting with `/`) on the API of the server the session points at. */
internal fun ServerUrlProvider.api(path: String): String = "${require().apiBaseUrl}$path"

/** The same address as [api] with its WebSocket scheme: `http` becomes `ws`, `https` `wss`. */
internal fun ServerUrlProvider.webSocket(path: String): String = "ws" + api(path).removePrefix("http")

/** [body] as the request's JSON payload. */
internal inline fun <reified T> HttpRequestBuilder.jsonBody(body: T) {
    contentType(ContentType.Application.Json)
    setBody(body)
}

/**
 * For a request the server answers only once media is ready: a cold HLS manifest (a remux
 * preflight, a wait for transcode capacity, then FFmpeg's first output — about 75 s at worst) or a
 * watch room's creation, which starts the room's HLS session before replying. Hanging up cancels
 * the server's work and a retry starts it over from nothing, so these outlast the client default.
 */
internal fun HttpRequestBuilder.awaitMediaPreparation() = timeout {
    requestTimeoutMillis = MEDIA_PREPARATION_TIMEOUT_MS
    socketTimeoutMillis = MEDIA_PREPARATION_TIMEOUT_MS
}

private const val MEDIA_PREPARATION_TIMEOUT_MS = 90_000L
