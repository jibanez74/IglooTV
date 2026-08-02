package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.storage.InMemoryPreferencesDataStore
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.repository.AuthRepository
import com.igloo.blindpenguincoder.data.repository.TEST_SERVER
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.testDeviceIdentity
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionManagerTest {

    private val userJson = """
        {"error":false,"message":"user found","data":{"user":{
            "id":1,"name":"Jose","email":"jose@example.com","is_admin":false,
            "avatar":{"String":"","Valid":false},"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"
        }}}
    """.trimIndent()

    private class Fixture(val manager: SessionManager, val http: TestHttp) {
        var requestCount = 0
    }

    private suspend fun fixture(storedServerUrl: String?, handler: MockRequestHandler): Fixture {
        val settings = ServerSettingsStore(InMemoryPreferencesDataStore())
        if (storedServerUrl != null) settings.save(storedServerUrl)
        lateinit var fixture: Fixture
        val http = TestHttp { request ->
            fixture.requestCount += 1
            handler(request)
        }
        http.serverUrl.set(null)
        val manager = SessionManager(
            authRepository = AuthRepository(http.api, http.tokenProvider, testDeviceIdentity),
            settings = settings,
            serverUrl = http.serverUrl,
        )
        fixture = Fixture(manager, http)
        return fixture
    }

    @Test
    fun `no stored server leads to NeedsServer`() = runTest {
        val fixture = fixture(null) { error("no request expected") }

        fixture.manager.restore()

        assertEquals(AppAuthState.NeedsServer(), fixture.manager.state.value)
        assertNull(fixture.http.serverUrl.current.value)
    }

    @Test
    fun `no stored token leads to NeedsLogin without any network call`() = runTest {
        val fixture = fixture(TEST_SERVER) { error("no request expected") }

        fixture.manager.restore()

        val state = fixture.manager.state.value as AppAuthState.NeedsLogin
        assertEquals(TEST_SERVER, state.serverAddress.apiBaseUrl)
        assertNull(state.restoreError)
        assertEquals(0, fixture.requestCount)
    }

    @Test
    fun `stored server with valid token restores Authenticated`() = runTest {
        val fixture = fixture(TEST_SERVER) { jsonResponse(userJson) }
        fixture.http.tokenStore.stored = "igd_valid"

        fixture.manager.restore()

        val state = fixture.manager.state.value as AppAuthState.Authenticated
        assertEquals("Jose", state.user.name)
        assertEquals(TEST_SERVER, fixture.http.serverUrl.current.value?.apiBaseUrl)
    }

    @Test
    fun `revoked token is cleared and lands on NeedsLogin`() = runTest {
        val fixture = fixture(TEST_SERVER) {
            jsonResponse("""{"error":true,"message":"expired"}""", HttpStatusCode.Unauthorized)
        }
        fixture.http.tokenStore.stored = "igd_stale"

        fixture.manager.restore()

        val state = fixture.manager.state.value as AppAuthState.NeedsLogin
        assertEquals(TEST_SERVER, state.serverAddress.apiBaseUrl)
        assertEquals("http://igloo.test:8080", state.serverAddress.origin)
        assertNull(state.restoreError)
        assertNull(fixture.http.tokenStore.stored)
    }

    @Test
    fun `unreachable server lands on NeedsLogin with a restore error and keeps the token`() = runTest {
        val fixture = fixture(TEST_SERVER) { throw IOException("no route to host") }
        fixture.http.tokenStore.stored = "igd_valid"

        fixture.manager.restore()

        val state = fixture.manager.state.value as AppAuthState.NeedsLogin
        assertEquals(AppError.Network, state.restoreError)
        assertEquals("http://igloo.test:8080", state.serverAddress.origin)
        assertEquals("igd_valid", fixture.http.tokenStore.stored)
    }

    @Test
    fun `logout returns to NeedsLogin and clears the token`() = runTest {
        val fixture = fixture(TEST_SERVER) { request ->
            if (request.url.encodedPath.endsWith("/logout")) {
                jsonResponse("""{"error":false,"message":"bye"}""")
            } else {
                jsonResponse(userJson)
            }
        }
        fixture.http.tokenStore.stored = "igd_valid"

        fixture.manager.restore()
        fixture.manager.logout()

        val state = fixture.manager.state.value as AppAuthState.NeedsLogin
        assertEquals(TEST_SERVER, state.serverAddress.apiBaseUrl)
        assertNull(fixture.http.tokenStore.stored)
    }

    @Test
    fun `invalid stored api base is cleared and returns to fresh setup`() = runTest {
        val fixture = fixture("http://igloo.test:8080/not-api") { error("no request expected") }
        fixture.http.tokenStore.stored = "igd_stale"

        fixture.manager.restore()

        assertEquals(AppAuthState.NeedsServer(), fixture.manager.state.value)
        assertNull(fixture.http.tokenStore.stored)
        assertNull(fixture.http.serverUrl.current.value)
    }
}
