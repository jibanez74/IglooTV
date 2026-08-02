package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.storage.InMemoryPreferencesDataStore
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.repository.AuthRepository
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.testDeviceIdentity
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickConnectViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun initiateJson(code: String) = """
        {"error":false,"data":{"code":"$code","secret":"device-secret",
        "expires_in_seconds":300,"poll_interval_seconds":2}}
    """.trimIndent()

    private val pendingJson = """{"error":false,"data":{"status":"pending"}}"""

    private val approvedJson = """
        {"error":false,"data":{"status":"approved","token":"igd_paired","device":{
            "id":9,"name":"Shield","platform":"android_tv","app_version":"0.1.0",
            "created_at":"2026-07-01T00:00:00Z",
            "last_used_at":"2026-07-01T00:01:00Z","is_current":true
        }}}
    """.trimIndent()

    private val userJson = """
        {"error":false,"message":"user found","data":{"user":{
            "id":1,"name":"Jose","email":"jose@example.com","is_admin":false,
            "avatar":{"String":"","Valid":false},"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"
        }}}
    """.trimIndent()

    private class Fixture(
        val viewModel: QuickConnectViewModel,
        val sessionManager: SessionManager,
        val http: TestHttp,
        private val requests: MutableList<String>,
    ) {
        fun count(suffix: String): Int = requests.count { it.endsWith(suffix) }
        val phase: QuickConnectPhase get() = viewModel.uiState.value.phase
    }

    private fun fixture(handler: MockRequestHandler): Fixture {
        val requests = mutableListOf<String>()
        // Unconfined engine dispatcher keeps the whole loop on the test scheduler,
        // so virtual time fully controls poll cadence.
        val http = TestHttp(engineDispatcher = Dispatchers.Unconfined) { request ->
            requests += request.url.encodedPath
            handler(request)
        }
        val repository = AuthRepository(http.api, http.tokenProvider, testDeviceIdentity)
        val sessionManager = SessionManager(
            authRepository = repository,
            settings = ServerSettingsStore(InMemoryPreferencesDataStore()),
            serverUrl = http.serverUrl,
        )
        return Fixture(QuickConnectViewModel(repository, sessionManager), sessionManager, http, requests)
    }

    @Test
    fun `start initiates once and a second start is a no-op`() = runTest {
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") ->
                    jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                else -> jsonResponse(pendingJson)
            }
        }

        f.viewModel.start()
        f.viewModel.start()

        assertEquals(QuickConnectPhase.CodeReady("ABCD12"), f.phase)
        assertEquals(1, f.count("/initiate"))

        f.viewModel.stop()
    }

    @Test
    fun `pending redeems poll at the advertised interval`() = runTest {
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") ->
                    jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                else -> jsonResponse(pendingJson)
            }
        }

        f.viewModel.start()
        advanceTimeBy(6_001)

        assertEquals(3, f.count("/redeem"))
        assertEquals(QuickConnectPhase.CodeReady("ABCD12"), f.phase)

        f.viewModel.stop()
    }

    @Test
    fun `approval stores the token, authenticates, and stops polling`() = runTest {
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") ->
                    jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                request.url.encodedPath.endsWith("/redeem") -> jsonResponse(approvedJson)
                else -> jsonResponse(userJson)
            }
        }

        f.viewModel.start()
        advanceTimeBy(2_001)

        val state = f.sessionManager.state.value as AppAuthState.Authenticated
        assertEquals("Jose", state.user.name)
        assertEquals("igd_paired", f.http.tokenStore.stored)

        advanceTimeBy(60_000)
        assertEquals(1, f.count("/redeem"))

        f.viewModel.stop()
    }

    @Test
    fun `redeem 404 discards the code and initiates a fresh one`() = runTest {
        var initiations = 0
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") -> {
                    initiations += 1
                    val code = if (initiations == 1) "ABCD12" else "EFGH34"
                    jsonResponse(initiateJson(code), HttpStatusCode.Created)
                }
                else -> jsonResponse(
                    """{"error":true,"message":"invalid or expired code"}""",
                    HttpStatusCode.NotFound,
                )
            }
        }

        f.viewModel.start()
        assertEquals(QuickConnectPhase.CodeReady("ABCD12"), f.phase)

        advanceTimeBy(2_001)

        assertEquals(QuickConnectPhase.CodeReady("EFGH34"), f.phase)
        assertEquals(2, f.count("/initiate"))

        f.viewModel.stop()
    }

    @Test
    fun `an expired code is auto-refreshed`() = runTest {
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") ->
                    jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                else -> jsonResponse(pendingJson)
            }
        }

        f.viewModel.start()
        assertEquals(1, f.count("/initiate"))

        advanceTimeBy(300_001)

        assertEquals(2, f.count("/initiate"))
        assertEquals(QuickConnectPhase.CodeReady("ABCD12"), f.phase)

        f.viewModel.stop()
    }

    @Test
    fun `redeem 429 doubles the poll gap and pending resets it`() = runTest {
        var redeems = 0
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") ->
                    jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                else -> {
                    redeems += 1
                    if (redeems == 1) {
                        jsonResponse(
                            """{"error":true,"message":"too many attempts"}""",
                            HttpStatusCode.TooManyRequests,
                        )
                    } else {
                        jsonResponse(pendingJson)
                    }
                }
            }
        }

        f.viewModel.start()
        // Polls land at 2s (429), then 6s after the doubled gap, then back to 8s.
        advanceTimeBy(8_001)

        assertEquals(3, f.count("/redeem"))

        f.viewModel.stop()
    }

    @Test
    fun `transport errors during polling back off but keep the code on screen`() = runTest {
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") ->
                    jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                else -> throw IOException("wifi dropped")
            }
        }

        f.viewModel.start()
        // Polls land at 2s, 6s, 14s with doubling backoff.
        advanceTimeBy(14_001)

        assertEquals(3, f.count("/redeem"))
        assertEquals(QuickConnectPhase.CodeReady("ABCD12"), f.phase)

        f.viewModel.stop()
    }

    @Test
    fun `initiate transport failure fails visibly and retry starts fresh`() = runTest {
        var initiations = 0
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") -> {
                    initiations += 1
                    if (initiations == 1) throw IOException("unreachable")
                    jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                }
                else -> jsonResponse(pendingJson)
            }
        }

        f.viewModel.start()

        val failed = f.phase as QuickConnectPhase.Failed
        assertEquals(
            "Couldn't reach the server. Check the address, port, and network connection.",
            failed.message,
        )

        f.viewModel.retry()

        assertEquals(QuickConnectPhase.CodeReady("ABCD12"), f.phase)
        assertEquals(2, f.count("/initiate"))

        f.viewModel.stop()
    }

    @Test
    fun `initiate 503 retries silently after a backoff`() = runTest {
        var initiations = 0
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") -> {
                    initiations += 1
                    if (initiations == 1) {
                        jsonResponse(
                            """{"error":true,"message":"quick connect is busy"}""",
                            HttpStatusCode.ServiceUnavailable,
                        )
                    } else {
                        jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                    }
                }
                else -> jsonResponse(pendingJson)
            }
        }

        f.viewModel.start()
        assertEquals(QuickConnectPhase.RequestingCode, f.phase)
        assertEquals(1, f.count("/initiate"))

        advanceTimeBy(5_001)

        assertEquals(QuickConnectPhase.CodeReady("ABCD12"), f.phase)
        assertEquals(2, f.count("/initiate"))

        f.viewModel.stop()
    }

    @Test
    fun `stop cancels polling and restart begins a fresh pairing`() = runTest {
        var initiations = 0
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") -> {
                    initiations += 1
                    val code = if (initiations == 1) "ABCD12" else "EFGH34"
                    jsonResponse(initiateJson(code), HttpStatusCode.Created)
                }
                else -> jsonResponse(pendingJson)
            }
        }

        f.viewModel.start()
        advanceTimeBy(4_001)
        assertEquals(2, f.count("/redeem"))

        f.viewModel.stop()
        advanceTimeBy(60_000)
        assertEquals(2, f.count("/redeem"))
        assertEquals(QuickConnectPhase.RequestingCode, f.phase)

        f.viewModel.start()
        assertEquals(QuickConnectPhase.CodeReady("EFGH34"), f.phase)
        assertEquals(2, f.count("/initiate"))

        f.viewModel.stop()
    }

    @Test
    fun `a failed user fetch after approval retries without re-pairing`() = runTest {
        var userFetches = 0
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") ->
                    jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                request.url.encodedPath.endsWith("/redeem") -> jsonResponse(approvedJson)
                else -> {
                    userFetches += 1
                    if (userFetches == 1) throw IOException("blip")
                    jsonResponse(userJson)
                }
            }
        }

        f.viewModel.start()
        advanceTimeBy(2_001)
        assertTrue(f.sessionManager.state.value !is AppAuthState.Authenticated)

        advanceTimeBy(2_001)

        assertTrue(f.sessionManager.state.value is AppAuthState.Authenticated)
        assertEquals(1, f.count("/initiate"))
        assertEquals("igd_paired", f.http.tokenStore.stored)

        f.viewModel.stop()
    }

    @Test
    fun `approval replaces the code with a signing-in state`() = runTest {
        var userFetches = 0
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") ->
                    jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                request.url.encodedPath.endsWith("/redeem") -> jsonResponse(approvedJson)
                else -> {
                    userFetches += 1
                    if (userFetches == 1) throw IOException("blip") else jsonResponse(userJson)
                }
            }
        }

        f.viewModel.start()
        advanceTimeBy(2_001)

        // The code is consumed at this point, so it must not stay on screen.
        assertEquals(QuickConnectPhase.SigningIn, f.phase)

        advanceTimeBy(2_001)
        assertTrue(f.sessionManager.state.value is AppAuthState.Authenticated)

        f.viewModel.stop()
    }

    @Test
    fun `a user fetch that keeps failing stops retrying and fails visibly`() = runTest {
        var userFetches = 0
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") ->
                    jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                request.url.encodedPath.endsWith("/redeem") -> jsonResponse(approvedJson)
                else -> {
                    userFetches += 1
                    throw IOException("still down")
                }
            }
        }

        f.viewModel.start()
        advanceTimeBy(2_001)
        assertEquals(QuickConnectPhase.SigningIn, f.phase)

        // Six attempts two seconds apart, then it gives up rather than hanging forever.
        advanceTimeBy(30_000)

        val failed = f.phase as QuickConnectPhase.Failed
        assertEquals(
            "Couldn't reach the server. Check the address, port, and network connection.",
            failed.message,
        )
        assertEquals(6, userFetches)
        assertEquals(1, f.count("/initiate"))

        f.viewModel.stop()
    }

    @Test
    fun `retry after a failed sign-in resumes with the stored token`() = runTest {
        var userFetches = 0
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") ->
                    jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                request.url.encodedPath.endsWith("/redeem") -> jsonResponse(approvedJson)
                else -> {
                    userFetches += 1
                    if (userFetches <= 6) throw IOException("down") else jsonResponse(userJson)
                }
            }
        }

        f.viewModel.start()
        advanceTimeBy(32_001)
        assertTrue(f.phase is QuickConnectPhase.Failed)

        f.viewModel.retry()

        assertTrue(f.sessionManager.state.value is AppAuthState.Authenticated)
        // Pairing already produced a token; asking for another code would mint a second device.
        assertEquals(1, f.count("/initiate"))
        assertEquals(1, f.count("/redeem"))

        f.viewModel.stop()
    }

    @Test
    fun `initiate that stays busy stops retrying and fails visibly`() = runTest {
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") -> jsonResponse(
                    """{"error":true,"message":"quick connect is busy"}""",
                    HttpStatusCode.ServiceUnavailable,
                )
                else -> jsonResponse(pendingJson)
            }
        }

        f.viewModel.start()
        assertEquals(QuickConnectPhase.RequestingCode, f.phase)

        // Backoffs of 5s, 10s, 20s and 30s separate the five attempts.
        advanceTimeBy(65_001)

        assertEquals(QuickConnectPhase.Failed("quick connect is busy"), f.phase)
        assertEquals(5, f.count("/initiate"))

        f.viewModel.stop()
    }

    @Test
    fun `a stored token finishes sign-in instead of pairing again`() = runTest {
        val f = fixture { request ->
            if (request.url.encodedPath.endsWith("/auth/user")) {
                jsonResponse(userJson)
            } else {
                error("unexpected request to ${request.url.encodedPath}")
            }
        }
        // Mirrors a launch where restore() could not reach the server but the token survived.
        f.http.tokenStore.stored = "igd_restored"

        f.viewModel.start()

        assertTrue(f.sessionManager.state.value is AppAuthState.Authenticated)
        assertEquals(0, f.count("/initiate"))

        f.viewModel.stop()
    }

    @Test
    fun `unauthorized after approval clears the session and fails`() = runTest {
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") ->
                    jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                request.url.encodedPath.endsWith("/redeem") -> jsonResponse(approvedJson)
                else -> jsonResponse(
                    """{"error":true,"message":"unauthorized"}""",
                    HttpStatusCode.Unauthorized,
                )
            }
        }

        f.viewModel.start()
        advanceTimeBy(2_001)

        assertEquals(
            QuickConnectPhase.Failed("Couldn't pair with the server. Try again."),
            f.phase,
        )
        assertNull(f.http.tokenStore.stored)

        f.viewModel.stop()
    }
}
