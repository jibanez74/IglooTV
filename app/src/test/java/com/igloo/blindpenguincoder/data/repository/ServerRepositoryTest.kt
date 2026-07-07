package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.storage.InMemoryPreferencesDataStore
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.core.storage.StoredCookie
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRepositoryTest {

    private var requestCount = 0

    private class Fixture(
        val repository: ServerRepository,
        val http: TestHttp,
        val settings: ServerSettingsStore,
    )

    private fun fixture(handler: MockRequestHandler): Fixture {
        val settings = ServerSettingsStore(InMemoryPreferencesDataStore())
        val http = TestHttp { request ->
            requestCount++
            handler(request)
        }
        val repository = ServerRepository(http.api, settings, http.serverUrl, http.cookiesStorage)
        return Fixture(repository, http, settings)
    }

    @Test
    fun `healthy server is normalized, persisted, and activated`() = runTest {
        val fixture = fixture { request ->
            assertEquals("http://igloo.local:8080/api/health", request.url.toString())
            jsonResponse("""{"error":false,"message":"ok"}""")
        }

        val result = fixture.repository.connect("igloo.local:8080/")

        assertEquals("http://igloo.local:8080/api", (result as ApiResult.Success).value)
        assertEquals("http://igloo.local:8080/api", fixture.settings.serverUrl.first())
        assertEquals("http://igloo.local:8080/api", fixture.http.serverUrl.current.value)
    }

    @Test
    fun `failing health check does not persist the url`() = runTest {
        val fixture = fixture {
            jsonResponse(
                """{"error":true,"message":"database unavailable"}""",
                status = HttpStatusCode.InternalServerError,
            )
        }
        fixture.http.serverUrl.set(null)

        val result = fixture.repository.connect("igloo.local:8080")

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("database unavailable", error.message)
        assertNull(fixture.settings.serverUrl.first())
        assertNull(fixture.http.serverUrl.current.value)
    }

    @Test
    fun `unreachable server maps to Network and does not persist`() = runTest {
        val fixture = fixture { throw IOException("no route to host") }
        fixture.http.serverUrl.set(null)

        val result = fixture.repository.connect("igloo.local:8080")

        assertEquals(AppError.Network, (result as ApiResult.Failure).error)
        assertNull(fixture.settings.serverUrl.first())
    }

    @Test
    fun `invalid input fails validation without hitting the network`() = runTest {
        val fixture = fixture { jsonResponse("""{"error":false}""") }

        val result = fixture.repository.connect("ftp://example.com")

        assertTrue((result as ApiResult.Failure).error is AppError.Validation)
        assertEquals(0, requestCount)
        assertNull(fixture.settings.serverUrl.first())
    }

    @Test
    fun `switching host clears the stored session cookie`() = runTest {
        val fixture = fixture { jsonResponse("""{"error":false,"message":"ok"}""") }
        fixture.http.cookieStore.stored = StoredCookie(
            name = "session",
            value = "abc123",
            host = "igloo.test",
            path = "/",
            expiresEpochMillis = null,
            secure = false,
            httpOnly = true,
        )

        fixture.repository.connect("other.host:8080")

        assertNull(fixture.http.cookieStore.stored)
    }

    @Test
    fun `reconnecting to the same host keeps the session cookie`() = runTest {
        val fixture = fixture { jsonResponse("""{"error":false,"message":"ok"}""") }
        val cookie = StoredCookie(
            name = "session",
            value = "abc123",
            host = "igloo.test",
            path = "/",
            expiresEpochMillis = null,
            secure = false,
            httpOnly = true,
        )
        fixture.http.cookieStore.stored = cookie

        fixture.repository.connect("igloo.test:8080")

        assertEquals(cookie, fixture.http.cookieStore.stored)
    }
}
