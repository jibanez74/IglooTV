package com.igloo.blindpenguincoder.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public-auth exemption is carried by a request attribute rather than a path match,
 * so a route that merely looks like a credential endpoint cannot slip past it.
 */
class DeviceTokenAuthTest {

    private class Fixture(status: HttpStatusCode = HttpStatusCode.OK) {
        val credentials = BearerTokenProvider()
        val events = AuthEventBus()
        var sentAuthorization: String? = null
        val client = createIglooHttpClient(
            credentials = credentials,
            authEvents = events,
            engine = MockEngine { request ->
                sentAuthorization = request.headers[HttpHeaders.Authorization]
                respond("{}", status)
            },
        )
    }

    /** Collects from the moment it returns, so a `tryEmit` with no buffer is not missed. */
    private suspend fun CoroutineScope.collectSignals(fixture: Fixture): List<Long?> {
        val signals = mutableListOf<Long?>()
        launch { fixture.events.unauthorized.collect { signals += it } }
        yield()
        return signals
    }

    @Test
    fun `an authenticated request carries the active credential`() = runTest {
        val fixture = Fixture()
        fixture.credentials.set(ActiveCredential(1, "igd_active"))

        fixture.client.get("http://igloo.test:8080/api/auth/user")

        assertEquals("Bearer igd_active", fixture.sentAuthorization)
    }

    @Test
    fun `a request with no credential sends no header`() = runTest {
        val fixture = Fixture()

        fixture.client.get("http://igloo.test:8080/api/auth/user")

        assertNull(fixture.sentAuthorization)
    }

    @Test
    fun `an opted-out request carries no credential even when one is active`() = runTest {
        val fixture = Fixture()
        fixture.credentials.set(ActiveCredential(1, "igd_active"))

        fixture.client.get("http://igloo.test:8080/api/auth/device-login") { withoutDeviceAuth() }

        assertNull(fixture.sentAuthorization)
    }

    @Test
    fun `a path that only looks like a credential endpoint is still authenticated`() = runTest {
        val fixture = Fixture()
        fixture.credentials.set(ActiveCredential(1, "igd_active"))

        // Suffix matching would have stripped the header here.
        fixture.client.get("http://igloo.test:8080/api/tenant/auth/device-login")

        assertEquals("Bearer igd_active", fixture.sentAuthorization)
    }

    @Test
    fun `an override wins over the active credential`() = runTest {
        val fixture = Fixture()
        fixture.credentials.set(ActiveCredential(2, "igd_active"))

        fixture.client.get("http://igloo.test:8080/api/auth/logout") {
            withBearerOverride("igd_superseded")
        }

        assertEquals("Bearer igd_superseded", fixture.sentAuthorization)
    }

    @Test
    fun `a rejected credential is reported with the profile it belonged to`() = runTest {
        val fixture = Fixture(HttpStatusCode.Unauthorized)
        fixture.credentials.set(ActiveCredential(7, "igd_dead"))
        val signals = backgroundScope.collectSignals(fixture)

        fixture.client.get("http://igloo.test:8080/api/movies")
        yield()

        assertEquals(listOf<Long?>(7L), signals)
    }

    @Test
    fun `a rejected opted-out request reports nothing`() = runTest {
        val fixture = Fixture(HttpStatusCode.Unauthorized)
        fixture.credentials.set(ActiveCredential(7, "igd_active"))
        val signals = backgroundScope.collectSignals(fixture)

        fixture.client.get("http://igloo.test:8080/api/auth/device-login") { withoutDeviceAuth() }
        yield()

        assertTrue(signals.isEmpty())
    }

    @Test
    fun `a rejected revoke of a superseded token reports nothing`() = runTest {
        val fixture = Fixture(HttpStatusCode.Unauthorized)
        fixture.credentials.set(ActiveCredential(7, "igd_active"))
        val signals = backgroundScope.collectSignals(fixture)

        fixture.client.get("http://igloo.test:8080/api/auth/logout") {
            withBearerOverride("igd_already_gone")
        }
        yield()

        assertTrue(signals.isEmpty())
    }
}
