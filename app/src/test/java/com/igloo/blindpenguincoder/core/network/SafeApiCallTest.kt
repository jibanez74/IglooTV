package com.igloo.blindpenguincoder.core.network

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.data.model.MessageResponse
import com.igloo.blindpenguincoder.data.repository.TEST_SERVER
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import io.ktor.client.call.body
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transport failures must map the same way here as in [ServerHealthProbe], so
 * the setup screen and the login screen word the same failure identically.
 */
class SafeApiCallTest {

    private suspend fun message(handler: MockRequestHandler): ApiResult<MessageResponse> {
        val http = TestHttp(handler = handler)
        return safeApiCall(request = { http.api.currentUser() }, decode = { it.body() })
    }

    private fun ApiResult<*>.error(): AppError = (this as ApiResult.Failure).error

    @Test
    fun `a 2xx response decodes the body`() = runTest {
        val result = message { jsonResponse("""{"error":false,"message":"ok"}""") }

        assertEquals("ok", (result as ApiResult.Success).value.message)
    }

    @Test
    fun `a request timeout maps to Timeout`() = runTest {
        val result = message {
            throw HttpRequestTimeoutException("$TEST_SERVER/auth/user", 30_000L)
        }

        assertEquals(AppError.Timeout, result.error())
    }

    @Test
    fun `a socket timeout wrapped by the engine maps to Timeout`() = runTest {
        val result = message {
            throw RuntimeException("engine failure", SocketTimeoutException("read timed out"))
        }

        assertEquals(AppError.Timeout, result.error())
    }

    @Test
    fun `an io failure maps to Network`() = runTest {
        val result = message { throw IOException("connection refused") }

        assertEquals(AppError.Network, result.error())
    }

    @Test
    fun `a tls failure maps to TlsVerification rather than Network`() = runTest {
        val result = message { throw SSLHandshakeException("certificate unknown") }

        assertEquals(AppError.TlsVerification, result.error())
    }

    @Test
    fun `401 maps to Unauthorized even with a backend message`() = runTest {
        val result = message {
            jsonResponse(
                body = """{"error":true,"message":"invalid credentials"}""",
                status = HttpStatusCode.Unauthorized,
            )
        }

        assertEquals(AppError.Unauthorized, result.error())
    }

    @Test
    fun `a non-2xx response preserves the backend message`() = runTest {
        val result = message {
            jsonResponse(
                body = """{"error":true,"message":"database unavailable"}""",
                status = HttpStatusCode.ServiceUnavailable,
            )
        }

        assertEquals(AppError.Api("database unavailable", 503), result.error())
    }

    @Test
    fun `a non-2xx response without a json body falls back to the status`() = runTest {
        val result = message { respond("bad gateway", HttpStatusCode.BadGateway) }

        assertEquals(AppError.Api("Server error (502)", 502), result.error())
    }

    @Test
    fun `a decode failure maps to Unexpected`() = runTest {
        val http = TestHttp { jsonResponse("""{"error":false,"message":"ok"}""") }

        val result = safeApiCall(
            request = { http.api.currentUser() },
            decode = { it.body<AuthUser>() },
        )

        assertTrue(result.error() is AppError.Unexpected)
    }
}
