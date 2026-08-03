package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.config.ServerAddress
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.network.BearerTokenProvider
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.storage.FakeProfileStore
import com.igloo.blindpenguincoder.core.storage.InMemoryPreferencesDataStore
import com.igloo.blindpenguincoder.core.storage.ProfileVault
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRepositoryTest {

    private class Fixture(
        val repository: ServerRepository,
        val settings: ServerSettingsStore,
        val serverUrl: ServerUrlProvider,
        val profileStore: FakeProfileStore,
    )

    private suspend fun fixture(
        active: ServerAddress? = null,
        timeoutMillis: Long = 10_000,
        handler: MockRequestHandler,
    ): Fixture {
        val settings = ServerSettingsStore(InMemoryPreferencesDataStore())
        if (active != null) settings.save(active.apiBaseUrl)
        val serverUrl = ServerUrlProvider().apply { set(active) }
        val profileStore = FakeProfileStore()
        val repository = ServerRepository(
            probe = testServerHealthProbe(handler, timeoutMillis),
            settings = settings,
            serverUrl = serverUrl,
            profiles = ProfileRepository(profileStore, BearerTokenProvider()),
        )
        return Fixture(repository, settings, serverUrl, profileStore)
    }

    @Test
    fun `any direct 2xx response is accepted without parsing its body`() = runTest {
        val responses = listOf(
            HttpStatusCode.OK to "",
            HttpStatusCode.Created to "not json",
            HttpStatusCode.Accepted to "{malformed",
        )

        responses.forEach { (status, body) ->
            val fixture = fixture { request ->
                assertEquals("http://igloo.local:8080/api/health", request.url.toString())
                respond(body, status)
            }

            val connectResult = fixture.repository.connect("igloo.local:8080/api")
            assertTrue(connectResult.toString(), connectResult is ApiResult.Success)
            val result = connectResult as ApiResult.Success

            assertEquals("http://igloo.local:8080", result.value.origin)
            assertEquals("http://igloo.local:8080/api", fixture.settings.serverUrl.first())
            assertEquals(result.value, fixture.serverUrl.current.value)
        }
    }

    @Test
    fun `non-2xx preserves a backend message and does not activate the candidate`() = runTest {
        val fixture = fixture {
            jsonResponse(
                """{"error":true,"message":"database unavailable"}""",
                HttpStatusCode.ServiceUnavailable,
            )
        }

        val result = fixture.repository.connect("igloo.local:8080")

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("database unavailable", error.message)
        assertEquals(503, error.status)
        assertNull(fixture.settings.serverUrl.first())
        assertNull(fixture.serverUrl.current.value)
    }

    @Test
    fun `non-json error falls back to the HTTP status`() = runTest {
        val fixture = fixture { respond("bad gateway", HttpStatusCode.BadGateway) }

        val result = fixture.repository.connect("igloo.local")

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertTrue(error.message.contains("HTTP 502"))
    }

    @Test
    fun `one deadline covers a server that never completes its response`() = runTest {
        val fixture = fixture(timeoutMillis = 100) {
            delay(Long.MAX_VALUE)
            respond("")
        }

        val result = fixture.repository.connect("igloo.local")

        assertEquals(AppError.Timeout, (result as ApiResult.Failure).error)
        assertNull(fixture.settings.serverUrl.first())
    }

    @Test
    fun `network and TLS failures have distinct mappings`() = runTest {
        val network = fixture { throw IOException("no route to host") }
        val tls = fixture { throw SSLHandshakeException("certificate unknown") }

        assertEquals(
            AppError.Network,
            (network.repository.connect("igloo.local") as ApiResult.Failure).error,
        )
        assertEquals(
            AppError.TlsVerification,
            (tls.repository.connect("https://igloo.local") as ApiResult.Failure).error,
        )
    }

    @Test
    fun `same-host redirects may select the final secure origin and port`() = runTest {
        var requestNumber = 0
        val fixture = fixture { request ->
            requestNumber += 1
            when (requestNumber) {
                1 -> {
                    assertEquals("http://igloo.local:8080/api/health", request.url.toString())
                    redirectResponse("https://IGLOO.local:8443/api/health")
                }
                2 -> {
                    assertTrue(
                        request.url.toString()
                            .equals("https://igloo.local:8443/api/health", ignoreCase = true),
                    )
                    respond("", HttpStatusCode.NoContent)
                }
                else -> error("Unexpected request")
            }
        }

        val result = fixture.repository.connect("http://igloo.local:8080") as ApiResult.Success

        assertEquals("https://igloo.local:8443", result.value.origin)
        assertEquals("https://igloo.local:8443/api", fixture.settings.serverUrl.first())
        assertEquals(2, requestNumber)
    }

    @Test
    fun `relative same-host redirects are followed`() = runTest {
        var requestNumber = 0
        val fixture = fixture { request ->
            requestNumber += 1
            if (requestNumber == 1) {
                redirectResponse("/api/health/")
            } else {
                assertEquals("http://igloo.local/api/health/", request.url.toString())
                respond("ok")
            }
        }

        val result = fixture.repository.connect("igloo.local")

        assertTrue(result is ApiResult.Success)
        assertEquals(2, requestNumber)
    }

    @Test
    fun `cross-host and HTTPS downgrade redirects are rejected`() = runTest {
        val crossHost = fixture { redirectResponse("http://other.local/api/health") }
        val downgrade = fixture { redirectResponse("http://igloo.local/api/health") }

        assertTrue(
            (crossHost.repository.connect("igloo.local") as ApiResult.Failure).error
                is AppError.UnsafeRedirect,
        )
        assertTrue(
            (downgrade.repository.connect("https://igloo.local") as ApiResult.Failure).error
                is AppError.UnsafeRedirect,
        )
        assertNull(crossHost.settings.serverUrl.first())
        assertNull(downgrade.settings.serverUrl.first())
    }

    @Test
    fun `redirect loops and missing destinations are rejected`() = runTest {
        val loop = fixture { request ->
            if (request.url.encodedPath.endsWith("health")) {
                redirectResponse("/api/health/again")
            } else {
                redirectResponse("/api/health")
            }
        }
        val missing = fixture { respond("", HttpStatusCode.Found) }

        assertTrue(
            (loop.repository.connect("igloo.local") as ApiResult.Failure).error
                is AppError.UnsafeRedirect,
        )
        assertTrue(
            (missing.repository.connect("igloo.local") as ApiResult.Failure).error
                is AppError.UnsafeRedirect,
        )
    }

    @Test
    fun `more than five redirects is rejected`() = runTest {
        var redirects = 0
        val fixture = fixture { request ->
            redirects += 1
            redirectResponse("/api/health/$redirects")
        }

        val result = fixture.repository.connect("igloo.local")

        assertTrue((result as ApiResult.Failure).error is AppError.UnsafeRedirect)
        assertEquals(6, redirects)
    }

    @Test
    fun `probe never sends cookies or authorization headers`() = runTest {
        val fixture = fixture(active = testServerAddress()) { request ->
            assertNull(request.headers[HttpHeaders.Cookie])
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("")
        }
        fixture.profileStore.vault = seededVault()

        fixture.repository.connect("igloo.test:8080")
    }

    @Test
    fun `authentication is preserved only for the identical normalized origin`() = runTest {
        val active = testServerAddress("http://igloo.local:8080")
        val same = fixture(active = active) { respond("") }
        val changedPort = fixture(active = active) { respond("") }
        val changedScheme = fixture(active = active) { respond("") }
        val changedHost = fixture(active = active) { respond("") }
        same.profileStore.vault = seededVault()
        changedPort.profileStore.vault = seededVault()
        changedScheme.profileStore.vault = seededVault()
        changedHost.profileStore.vault = seededVault()

        same.repository.connect("HTTP://IGLOO.local:8080/")
        changedPort.repository.connect("http://igloo.local:8081")
        changedScheme.repository.connect("https://igloo.local:8080")
        changedHost.repository.connect("http://other.local:8080")

        // Tokens and user ids are server-scoped: a different origin invalidates them all.
        assertEquals(seededVault(), same.profileStore.vault)
        assertEquals(ProfileVault(), changedPort.profileStore.vault)
        assertEquals(ProfileVault(), changedScheme.profileStore.vault)
        assertEquals(ProfileVault(), changedHost.profileStore.vault)
    }

    private fun seededVault() = ProfileVault(
        activeUserId = 1,
        pendingToken = "igd_half_paired",
        profiles = listOf(testStoredProfile(userId = 1), testStoredProfile(userId = 2, name = "Ana")),
    )

    @Test
    fun `invalid input does not make a request or replace the active server`() = runTest {
        var requestCount = 0
        val active = testServerAddress()
        val fixture = fixture(active = active) {
            requestCount += 1
            respond("")
        }

        val result = fixture.repository.connect("http://igloo.local/media")

        assertTrue((result as ApiResult.Failure).error is AppError.Validation)
        assertEquals(0, requestCount)
        assertEquals(active.apiBaseUrl, fixture.settings.serverUrl.first())
        assertEquals(active, fixture.serverUrl.current.value)
    }

    private fun MockRequestHandleScope.redirectResponse(location: String) = respond(
        content = "",
        status = HttpStatusCode.Found,
        headers = headersOf(HttpHeaders.Location, location),
    )
}
