package com.igloo.blindpenguincoder.core.network

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.MessageResponse
import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.HttpResponse
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

private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299

private suspend fun HttpResponse.backendMessage(): String? = try {
    body<MessageResponse>().message?.takeIf { it.isNotBlank() }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
