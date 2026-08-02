package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.storage.InMemoryPreferencesDataStore
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.repository.AuthRepository
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.testDeviceIdentity
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.io.IOException
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
        Dispatchers.resetMain()
    }

    private val userJson = """
        {"error":false,"message":"user found","data":{"user":{
            "id":1,"name":"Jose","email":"jose@example.com","is_admin":false,
            "avatar":{"String":"","Valid":false},"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"
        }}}
    """.trimIndent()

    private val deviceTokenJson = """
        {"error":false,"data":{"token":"igd_test","device":{
            "id":9,"name":"Shield","platform":"android_tv","app_version":"0.1.0",
            "created_at":"2026-07-01T00:00:00Z",
            "last_used_at":"2026-07-01T00:01:00Z","is_current":true
        }}}
    """.trimIndent()

    private fun viewModel(handler: MockRequestHandler): Pair<LoginViewModel, SessionManager> {
        val http = TestHttp(handler = handler)
        val repository = AuthRepository(http.api, http.tokenProvider, testDeviceIdentity)
        val sessionManager = SessionManager(
            authRepository = repository,
            settings = ServerSettingsStore(InMemoryPreferencesDataStore()),
            serverUrl = http.serverUrl,
        )
        return LoginViewModel(repository, sessionManager) to sessionManager
    }

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
    fun `editing credentials after a failed user fetch logs in again`() = runTest {
        var deviceLogins = 0
        val (viewModel, _) = viewModel { request ->
            if (request.url.encodedPath.endsWith("/auth/device-login")) {
                deviceLogins++
                jsonResponse(deviceTokenJson)
            } else {
                throw IOException("unreachable")
            }
        }

        viewModel.onEmailChange("jose@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()
        assertTrue(viewModel.uiState.first { it.error != null }.awaitingUser)

        viewModel.onPasswordChange("corrected")
        assertFalse(viewModel.uiState.value.awaitingUser)

        viewModel.submit()
        viewModel.uiState.first { it.error != null }
        assertEquals(2, deviceLogins)
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
