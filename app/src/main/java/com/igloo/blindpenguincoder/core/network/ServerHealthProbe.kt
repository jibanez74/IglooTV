package com.igloo.blindpenguincoder.core.network

import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.MessageResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.discard
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ServerHealthProbe(
    private val client: HttpClient,
    private val timeoutMillis: Long = SERVER_PROBE_TIMEOUT_MILLIS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun probe(candidate: ServerAddress): ApiResult<ServerAddress> = withContext(dispatcher) {
        try {
            withTimeout(timeoutMillis) {
                followHealthRedirects(candidate)
            }
        } catch (_: TimeoutCancellationException) {
            ApiResult.Failure(AppError.Timeout)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ApiResult.Failure(error.toTransportError())
        }
    }

    private suspend fun followHealthRedirects(candidate: ServerAddress): ApiResult<ServerAddress> {
        var currentUri = URI("${candidate.apiBaseUrl}/health")
        var finalAddress = candidate
        var redirectsFollowed = 0
        val visited = mutableSetOf(normalizedRedirectKey(currentUri))

        while (true) {
            val response = client.get(currentUri.toString())
            if (response.status.isRedirect()) {
                if (redirectsFollowed == MAX_REDIRECTS) {
                    response.bodyAsChannel().discard()
                    return unsafeRedirect("The server redirected too many times. Check its public URL configuration.")
                }

                val location = response.headers[HttpHeaders.Location]
                response.bodyAsChannel().discard()
                if (location.isNullOrBlank()) {
                    return unsafeRedirect("The server returned a redirect without a valid destination.")
                }

                val target = resolveRedirect(currentUri, location)
                    ?: return unsafeRedirect("The server returned an invalid redirect destination.")
                val redirectedAddress = ServerAddress.fromUriOrigin(target)
                    ?: return unsafeRedirect("The server redirected to an unsupported address.")

                if (redirectedAddress.hostname != candidate.hostname) {
                    return unsafeRedirect("The server tried to redirect to a different host. Enter that server address directly.")
                }
                if (finalAddress.scheme == "https" && redirectedAddress.scheme == "http") {
                    return unsafeRedirect("The server tried to downgrade a secure HTTPS connection to HTTP.")
                }

                val targetKey = normalizedRedirectKey(target)
                if (!visited.add(targetKey)) {
                    return unsafeRedirect("The server returned a redirect loop. Check its public URL configuration.")
                }

                redirectsFollowed += 1
                currentUri = target
                finalAddress = redirectedAddress
                continue
            }

            if (response.status.value in 200..299) {
                response.bodyAsChannel().discard()
                return ApiResult.Success(finalAddress)
            }

            val message = response.safeBackendMessage()
                ?: "Server returned HTTP ${response.status.value} ${response.status.description}."
            return ApiResult.Failure(AppError.Api(message, response.status.value))
        }
    }
}

private fun resolveRedirect(current: URI, location: String): URI? = try {
    val resolved = current.resolve(location).normalize()
    if (!resolved.isAbsolute || resolved.rawUserInfo != null) return null
    URI(
        resolved.scheme,
        resolved.rawAuthority,
        resolved.rawPath.ifEmpty { "/" },
        resolved.rawQuery,
        null,
    )
} catch (_: Exception) {
    null
}

private fun normalizedRedirectKey(uri: URI): String = buildString {
    append(uri.scheme?.lowercase())
    append("://")
    append(uri.rawAuthority?.lowercase())
    append(uri.rawPath.ifEmpty { "/" })
    uri.rawQuery?.let {
        append('?')
        append(it)
    }
}

private fun HttpStatusCode.isRedirect(): Boolean = value in REDIRECT_STATUS_CODES

private suspend fun HttpResponse.safeBackendMessage(): String? = try {
    val body = bodyAsText()
    val message = IglooJson.decodeFromString<MessageResponse>(body).message ?: return null
    message.replace(Regex("\\s+"), " ").trim().take(MAX_BACKEND_MESSAGE_LENGTH)
        .takeIf { it.isNotEmpty() }
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}

private fun unsafeRedirect(message: String): ApiResult.Failure =
    ApiResult.Failure(AppError.UnsafeRedirect(message))

private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
private const val MAX_REDIRECTS = 5
private const val MAX_BACKEND_MESSAGE_LENGTH = 240
