package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.storage.InMemoryPreferencesDataStore
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.repository.AuthRepository
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import io.ktor.client.engine.mock.MockRequestHandler
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

    private fun viewModel(handler: MockRequestHandler): Pair<LoginViewModel, SessionManager> {
        val http = TestHttp(handler)
        val repository = AuthRepository(http.api, http.cookiesStorage)
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
            if (request.url.encodedPath.endsWith("/auth/login")) {
                jsonResponse(
                    """{"error":false,"message":"Hello Jose"}""",
                    setCookie = "session=abc123; Path=/; HttpOnly",
                )
            } else {
                jsonResponse(userJson)
            }
        }

        viewModel.onEmailChange("jose@example.com")
        viewModel.onPasswordChange("hunter2")
        viewModel.submit()

        val state = sessionManager.state
            .first { it is AppAuthState.Authenticated } as AppAuthState.Authenticated
        assertEquals("Jose", state.user.name)
        assertFalse(viewModel.uiState.value.isSubmitting)
        assertNull(viewModel.uiState.value.error)
        assertEquals("", viewModel.uiState.value.password)
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
