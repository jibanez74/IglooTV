package com.igloo.blindpenguincoder.core.network

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.MessageResponse
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * Runs a request and maps the Igloo envelope conventions onto [ApiResult]:
 * 401 -> Unauthorized, other non-2xx -> Api with the backend message
 * preserved, transport failures -> Timeout / TlsVerification / Network.
 */
suspend fun <T> safeApiCall(
    request: suspend () -> HttpResponse,
    decode: suspend (HttpResponse) -> T,
): ApiResult<T> {
    val response = try {
        request()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return ApiResult.Failure(e.toTransportError())
    }

    if (response.status == HttpStatusCode.Unauthorized) {
        return ApiResult.Failure(AppError.Unauthorized)
    }

    if (!response.status.isSuccess()) {
        val message = response.backendMessage() ?: "Server error (${response.status.value})"
        return ApiResult.Failure(AppError.Api(message, response.status.value))
    }

    return try {
        ApiResult.Success(decode(response))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ApiResult.Failure(AppError.Unexpected(e.message))
    }
}

/**
 * The envelope's `message` from an error response, on one line and capped so a verbose server
 * cannot flood the screen that shows it; null when the body carries none.
 */
internal suspend fun HttpResponse.backendMessage(): String? = try {
    IglooJson.decodeFromString<MessageResponse>(bodyAsText()).message
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAX_BACKEND_MESSAGE_LENGTH)
        ?.takeIf { it.isNotEmpty() }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

private const val MAX_BACKEND_MESSAGE_LENGTH = 240
