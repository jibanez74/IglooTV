package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.network.causeChain
import com.igloo.blindpenguincoder.core.network.toTransportError
import com.igloo.blindpenguincoder.playback.hls.HlsManifestResult
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_SERVER_UNREACHABLE_MESSAGE
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.discard
import kotlinx.coroutines.CancellationException

/**
 * One HLS manifest request as the session controller reads it, for a viewer's own session and a
 * watch room's alike: [parse] turns the status and headers into a result, and the playlist body
 * is drained unread — Media3 fetches it again on its own stack.
 *
 * Not [com.igloo.blindpenguincoder.core.network.safeApiCall]: 503 and 404 are protocol states
 * the controller retries through, not failures. Established request/socket timeouts count as
 * "busy" because the server may legitimately hold a remux manifest while FFmpeg warms up; a
 * connection timeout means the server was never reached. Only transport failures are absorbed,
 * and programming errors must surface.
 */
internal suspend fun hlsManifestResult(
    request: suspend () -> HttpResponse,
    parse: (status: Int, header: (String) -> String?) -> HlsManifestResult,
): HlsManifestResult = try {
    val response = request()
    val status = response.status.value
    val headers = response.headers.entries().associate { (name, values) ->
        name.lowercase() to values.firstOrNull()
    }
    response.bodyAsChannel().discard()
    parse(status) { name -> headers[name.lowercase()] }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (failure: Exception) {
    if (failure.causeChain().any { it is ConnectTimeoutException }) {
        HlsManifestResult.Failed(PLAYBACK_SERVER_UNREACHABLE_MESSAGE)
    } else {
        when (failure.toTransportError()) {
            AppError.Timeout -> HlsManifestResult.Busy(retryAfterSec = null)
            is AppError.Unexpected -> throw failure
            else -> HlsManifestResult.Failed(PLAYBACK_SERVER_UNREACHABLE_MESSAGE)
        }
    }
}
