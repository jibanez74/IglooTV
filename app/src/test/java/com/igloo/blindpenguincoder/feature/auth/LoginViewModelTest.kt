package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.storage.InMemoryPreferencesDataStore
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.testStoredProfile
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

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

    private fun newScope() = CoroutineScope(UnconfinedTestDispatcher()).also { scopes += it }

    private val userJson = """
        {"error":false,"message":"user found","data":{"user":{
            "id":1,"name":"Jose","email":"jose@example.com","is_admin":false,
            "avatar":null,"has_pin":false,
            "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"
        }}}
    """.trimIndent()

    private val deviceTokenJson = """
        {"error":false,"data":{"token":"igd_test","device":{
            "id":9,"name":"Shield","platform":"android_tv","app_version":"0.1.0",
            "created_at":"2026-07-01T00:00:00Z",
            "last_used_at":"2026-07-01T00:01:00Z","is_current":true
        }}}
    """.trimIndent()

    private class Fixture(
        val viewModel: LoginViewModel,
        val quickConnectViewModel: QuickConnectViewModel,
        val sessionManager: SessionManager,
        val http: TestHttp,
    )

    private fun fixture(handler: MockRequestHandler): Fixture {
        val http = TestHttp(handler = handler)
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
            viewModel = LoginViewModel(repository, sessionManager),
            quickConnectViewModel = QuickConnectViewModel(repository, http.profiles, sessionManager),
            sessionManager = sessionManager,
            http = http,
        )
    }

    private fun TestHttp.pendingToken() = profileStore.vault.pendingToken

    private fun viewModel(handler: MockRequestHandler): Pair<LoginViewModel, SessionManager> =
        fixture(handler).let { it.viewModel to it.sessionManager }

    @Test
    fun `successful login authenticates the session`() = runTest {
        val (viewModel, sessionManager) = viewModel { request ->
            if (request.url.encodedPath.endsWith("/auth/device-login")) {
                assertNull(request.headers[HttpHeaders.Authorization])
                jsonResponse(deviceTokenJson)
            } else {
                assertEquals("Bearer igd_test", request.headers[HttpHeaders.Authorization])
                jsonResponse(userJson)
            }
        }

        viewModel.onEmailChange("jose@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()

        val state = viewModel.uiState.first { !it.isSubmitting }
        assertNull(state.error)
        assertEquals("", state.password)
        assertFalse(state.awaitingUser)

        val authenticated = sessionManager.state.value as AppAuthState.Authenticated
        assertEquals("Jose", authenticated.user.name)
    }

    @Test
    fun `wrong credentials surface a friendly error`() = runTest {
        val (viewModel, sessionManager) = viewModel {
            jsonResponse(
                """{"error":true,"message":"invalid credentials"}""",
                HttpStatusCode.Unauthorized,
            )
        }

        viewModel.onEmailChange("jose@example.com")
        viewModel.onPasswordChange("wrong")
        viewModel.submit()

        val state = viewModel.uiState.first { it.error != null }
        assertEquals("Incorrect email or password.", state.error)
        assertFalse(state.isSubmitting)
        assertEquals(AppAuthState.Loading, sessionManager.state.value)
    }

    @Test
    fun `network failure surfaces a network error`() = runTest {
        val (viewModel, _) = viewModel { throw IOException("unreachable") }

        viewModel.onEmailChange("jose@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()

        val state = viewModel.uiState.first { it.error != null }
        assertEquals(
            "Couldn't reach the server. Check the address, port, and network connection.",
            state.error,
        )
    }

    @Test
    fun `blank fields fail locally without a request`() = runTest {
        var requested = false
        val (viewModel, _) = viewModel {
            requested = true
            jsonResponse("""{"error":false}""")
        }

        viewModel.submit()

        assertEquals("Enter your email and password.", viewModel.uiState.value.error)
        assertFalse(requested)
    }

    @Test
    fun `password is removed as soon as a device token is issued`() = runTest {
        val userRequestStarted = CompletableDeferred<Unit>()
        val finishUserRequest = CompletableDeferred<Unit>()
        val (viewModel, _) = viewModel { request ->
            if (request.url.encodedPath.endsWith("/auth/device-login")) {
                jsonResponse(deviceTokenJson)
            } else {
                userRequestStarted.complete(Unit)
                finishUserRequest.await()
                throw IOException("unreachable")
            }
        }

        viewModel.onEmailChange("jose@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()
        userRequestStarted.await()

        assertEquals("", viewModel.uiState.value.password)
        assertTrue(viewModel.uiState.value.awaitingUser)
        assertTrue(viewModel.uiState.value.isSubmitting)

        finishUserRequest.complete(Unit)
        val failed = viewModel.uiState.first { it.error != null }
        assertEquals("", failed.password)
        assertTrue(failed.awaitingUser)
    }

    @Test
    fun `retrying after a failed user fetch resumes instead of logging in again`() = runTest {
        var deviceLogins = 0
        var userFetches = 0
        val (viewModel, sessionManager) = viewModel { request ->
            if (request.url.encodedPath.endsWith("/auth/device-login")) {
                deviceLogins++
                jsonResponse(deviceTokenJson)
            } else {
                // The token is issued, then the user fetch fails once before recovering.
                if (++userFetches == 1) throw IOException("unreachable") else jsonResponse(userJson)
            }
        }

        viewModel.onEmailChange("jose@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()

        val failed = viewModel.uiState.first { it.error != null }
        assertTrue(failed.awaitingUser)
        assertEquals("", failed.password)
        assertEquals(1, deviceLogins)

        viewModel.submit()

        val recovered = viewModel.uiState.first { !it.isSubmitting && it.error == null }
        assertFalse(recovered.awaitingUser)
        // A second device-login would mint a redundant device token for the same user.
        assertEquals(1, deviceLogins)
        assertEquals(2, userFetches)
        assertTrue(sessionManager.state.value is AppAuthState.Authenticated)
    }

    @Test
    fun `edited credentials mint a replacement token without a logout round trip`() = runTest {
        val requests = mutableListOf<String>()
        val fixture = fixture { request ->
            requests += request.url.encodedPath
            when {
                request.url.encodedPath.endsWith("/auth/device-login") ->
                    jsonResponse(deviceTokenJson)
                requests.count { it.endsWith("/auth/user") } == 1 ->
                    throw IOException("unreachable")
                else -> jsonResponse(userJson)
            }
        }
        val viewModel = fixture.viewModel

        viewModel.onEmailChange("jose@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()
        assertTrue(viewModel.uiState.first { it.error != null }.awaitingUser)
        assertEquals("igd_test", fixture.http.pendingToken())

        viewModel.onEmailChange("other@example.com")
        assertFalse(viewModel.uiState.value.awaitingUser)
        viewModel.onPasswordChange("replacement")

        viewModel.submit()

        viewModel.uiState.first { !it.isSubmitting && it.error == null }
        assertTrue(fixture.sessionManager.state.value is AppAuthState.Authenticated)
        // Revoking here would kill whichever profile is currently signed in; the server
        // replaces the pending token on its own.
        assertEquals(
            listOf(
                "/api/auth/device-login",
                "/api/auth/user",
                "/api/auth/device-login",
                "/api/auth/user",
            ),
            requests,
        )
    }

    @Test
    fun `adding a profile does not revoke the profile already signed in`() = runTest {
        val requests = mutableListOf<String>()
        val fixture = fixture { request ->
            requests += request.url.encodedPath
            if (request.url.encodedPath.endsWith("/auth/device-login")) {
                jsonResponse(deviceTokenJson)
            } else {
                jsonResponse(userJson.replace("\"id\":1", "\"id\":2").replace("Jose", "Ana"))
            }
        }
        fixture.http.seedVault(testStoredProfile(userId = 1, name = "Jose"))
        fixture.http.profiles.activate(1)

        fixture.viewModel.onEmailChange("ana@example.com")
        fixture.viewModel.onPasswordChange("hunter2")
        fixture.viewModel.submit()
        fixture.viewModel.uiState.first { !it.isSubmitting && it.error == null }

        assertFalse(requests.any { it.endsWith("/auth/logout") })
        assertEquals(
            listOf("Jose", "Ana"),
            fixture.http.profileStore.vault.profiles.map { it.name },
        )
    }

    @Test
    fun `clearPassword preserves email and pending token state`() = runTest {
        val fixture = fixture { request ->
            if (request.url.encodedPath.endsWith("/auth/device-login")) {
                jsonResponse(deviceTokenJson)
            } else {
                throw IOException("unreachable")
            }
        }

        fixture.viewModel.onEmailChange("jose@example.com")
        fixture.viewModel.onPasswordChange("hunter2")
        fixture.viewModel.submit()
        assertTrue(fixture.viewModel.uiState.first { it.error != null }.awaitingUser)

        fixture.viewModel.clearPassword()

        val state = fixture.viewModel.uiState.value
        assertEquals("jose@example.com", state.email)
        assertEquals("", state.password)
        assertTrue(state.awaitingUser)
        assertEquals("igd_test", fixture.http.pendingToken())
    }

    @Test
    fun `typing clears the previous error`() = runTest {
        val (viewModel, _) = viewModel {
            jsonResponse("""{"error":true,"message":"bad"}""", HttpStatusCode.Unauthorized)
        }

        viewModel.onEmailChange("jose@example.com")
        viewModel.onPasswordChange("wrong")
        viewModel.submit()
        assertTrue(viewModel.uiState.first { it.error != null }.error != null)

        viewModel.onPasswordChange("wrong2")
        assertNull(viewModel.uiState.value.error)
    }
}
