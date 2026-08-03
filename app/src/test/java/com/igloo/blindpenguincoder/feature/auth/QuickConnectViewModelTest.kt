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
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
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
        // SessionManager collects auth events for as long as its scope lives; leaking one
        // leaves a collector running into the next test class.
        scopes.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    private val scopes = mutableListOf<CoroutineScope>()

    private fun newScope() = CoroutineScope(Dispatchers.Unconfined).also { scopes += it }

    private fun initiateJson(
        code: String,
        pollIntervalSeconds: Int = 2,
        expiresInSeconds: Int = 300,
    ) = """
        {"error":false,"data":{"code":"$code","secret":"device-secret",
        "expires_in_seconds":$expiresInSeconds,"poll_interval_seconds":$pollIntervalSeconds}}
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
            "id":1,"name":"Jose","email":"jose@example.com","is_admin":false,"has_pin":false,
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
        val repository = http.authRepository
        val sessionManager = SessionManager(
            authRepository = repository,
            profiles = http.profiles,
            settings = ServerSettingsStore(InMemoryPreferencesDataStore()),
            serverUrl = http.serverUrl,
            authEvents = http.authEvents,
            elapsed = { 0L },
            scope = newScope(),
        )
        return Fixture(
            QuickConnectViewModel(repository, http.profiles, sessionManager),
            sessionManager,
            http,
            requests,
        )
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
    fun `advertised intervals above the backoff cap are not shortened`() = runTest {
        val f = fixture { request ->
            if (request.url.encodedPath.endsWith("/initiate")) {
                jsonResponse(
                    initiateJson("ABCD12", pollIntervalSeconds = 45),
                    HttpStatusCode.Created,
                )
            } else {
                jsonResponse(pendingJson)
            }
        }

        f.viewModel.start()
        advanceTimeBy(30_001)
        assertEquals(0, f.count("/redeem"))

        advanceTimeBy(15_000)
        assertEquals(1, f.count("/redeem"))

        f.viewModel.stop()
    }

    @Test
    fun `poll retry backoff is additional and capped at thirty seconds`() {
        assertEquals(45_000L, quickConnectPollDelayMillis(45_000L, 0))
        assertEquals(47_000L, quickConnectPollDelayMillis(45_000L, 1))
        assertEquals(75_000L, quickConnectPollDelayMillis(45_000L, 10))
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
        assertEquals("igd_paired", f.http.profileStore.vault.profiles.single().token)

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
    fun `redeem validation and authorization failures fail immediately`() = runTest {
        data class FailureCase(
            val status: HttpStatusCode,
            val backendMessage: String,
            val expectedMessage: String,
        )

        val cases = listOf(
            FailureCase(HttpStatusCode.BadRequest, "invalid request", "invalid request"),
            FailureCase(
                HttpStatusCode.Unauthorized,
                "unauthorized",
                "Couldn't pair with the server. Try again.",
            ),
            FailureCase(HttpStatusCode.Forbidden, "pairing forbidden", "pairing forbidden"),
        )

        cases.forEach { case ->
            val f = fixture { request ->
                if (request.url.encodedPath.endsWith("/initiate")) {
                    jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                } else {
                    jsonResponse(
                        """{"error":true,"message":"${case.backendMessage}"}""",
                        case.status,
                    )
                }
            }

            f.viewModel.start()
            advanceTimeBy(2_001)

            assertEquals(QuickConnectPhase.Failed(case.expectedMessage), f.phase)
            assertEquals(1, f.count("/redeem"))
            f.viewModel.stop()
        }
    }

    @Test
    fun `malformed successful redeem response fails immediately`() = runTest {
        val f = fixture { request ->
            if (request.url.encodedPath.endsWith("/initiate")) {
                jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
            } else {
                jsonResponse("""{"error":false,"data":{"status":"invalid"}}""")
            }
        }

        f.viewModel.start()
        advanceTimeBy(2_001)

        assertEquals(
            QuickConnectPhase.Failed("Something went wrong. Please try again."),
            f.phase,
        )
        assertEquals(1, f.count("/redeem"))
        f.viewModel.stop()
    }

    @Test
    fun `TLS failure during redeem is terminal`() = runTest {
        val f = fixture { request ->
            if (request.url.encodedPath.endsWith("/initiate")) {
                jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
            } else {
                throw SSLException("certificate rejected")
            }
        }

        f.viewModel.start()
        advanceTimeBy(2_001)

        assertEquals(
            QuickConnectPhase.Failed(
                "Couldn't verify this server's HTTPS certificate. Check the certificate or use the correct HTTP address.",
            ),
            f.phase,
        )
        assertEquals(1, f.count("/redeem"))
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
    fun `pending redeem resets the failure count and poll backoff`() = runTest {
        var redeems = 0
        val f = fixture { request ->
            when {
                request.url.encodedPath.endsWith("/initiate") ->
                    jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                else -> {
                    redeems += 1
                    if (redeems in 1..4 || redeems in 6..9) {
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
        // Four failures land at 2s, 6s, 12s, and 22s. Pending at 40s resets the
        // backoff, so another four failures land at 42s, 46s, 52s, and 62s.
        advanceTimeBy(62_001)

        assertEquals(9, f.count("/redeem"))
        assertEquals(QuickConnectPhase.CodeReady("ABCD12"), f.phase)

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
        // Polls land at 2s, 6s, and 12s as retry backoff is added to the 2s base.
        advanceTimeBy(12_001)

        assertEquals(3, f.count("/redeem"))
        assertEquals(QuickConnectPhase.CodeReady("ABCD12"), f.phase)

        f.viewModel.stop()
    }

    @Test
    fun `persistent retryable redeem failures stop after five attempts`() = runTest {
        data class FailureCase(val kind: String, val expectedMessage: String)

        val cases = listOf(
            FailureCase("rate-limit", "too many attempts"),
            FailureCase("server", "quick connect unavailable"),
            FailureCase(
                "network",
                "Couldn't reach the server. Check the address, port, and network connection.",
            ),
            FailureCase(
                "timeout",
                "The server took too long to respond. Check the address and try again.",
            ),
        )

        cases.forEach { case ->
            val f = fixture { request ->
                if (request.url.encodedPath.endsWith("/initiate")) {
                    jsonResponse(initiateJson("ABCD12"), HttpStatusCode.Created)
                } else {
                    when (case.kind) {
                        "rate-limit" -> jsonResponse(
                            """{"error":true,"message":"too many attempts"}""",
                            HttpStatusCode.TooManyRequests,
                        )
                        "server" -> jsonResponse(
                            """{"error":true,"message":"quick connect unavailable"}""",
                            HttpStatusCode.InternalServerError,
                        )
                        "network" -> throw IOException("wifi dropped")
                        else -> throw SocketTimeoutException("redeem timed out")
                    }
                }
            }

            f.viewModel.start()
            advanceTimeBy(60_001)

            assertEquals(QuickConnectPhase.Failed(case.expectedMessage), f.phase)
            assertEquals(5, f.count("/redeem"))
            f.viewModel.stop()
        }
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
        assertEquals("igd_paired", f.http.profileStore.vault.profiles.single().token)

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
        f.http.seedVault(pendingToken = "igd_restored")

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
        assertNull(f.http.profileStore.vault.pendingToken)

        f.viewModel.stop()
    }
}
