package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.storage.InMemoryPreferencesDataStore
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.repository.AuthRepository
import com.igloo.blindpenguincoder.data.repository.ServerRepository
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import io.ktor.client.engine.mock.MockRequestHandler
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSetupViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(handler: MockRequestHandler): Pair<ServerSetupViewModel, SessionManager> {
        val settings = ServerSettingsStore(InMemoryPreferencesDataStore())
        val http = TestHttp(handler)
        http.serverUrl.set(null)
        val authRepository = AuthRepository(http.api, http.cookiesStorage)
        val serverRepository =
            ServerRepository(http.api, settings, http.serverUrl, http.cookiesStorage)
        val sessionManager = SessionManager(authRepository, settings, http.serverUrl)
        return ServerSetupViewModel(serverRepository, sessionManager) to sessionManager
    }

    @Test
    fun `successful connect moves the session to NeedsLogin`() = runTest {
        val (viewModel, sessionManager) = viewModel {
            jsonResponse("""{"error":false,"message":"ok"}""")
        }

        viewModel.onInputChange("igloo.local:8080")
        viewModel.connect()

        val state = sessionManager.state
            .first { it is AppAuthState.NeedsLogin } as AppAuthState.NeedsLogin
        assertEquals("http://igloo.local:8080/api", state.serverUrl)
        assertFalse(viewModel.uiState.value.isConnecting)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `invalid input shows a validation error`() = runTest {
        val (viewModel, sessionManager) = viewModel {
            jsonResponse("""{"error":false,"message":"ok"}""")
        }

        viewModel.onInputChange("ftp://nope")
        viewModel.connect()

        val state = viewModel.uiState.first { it.error != null }
        assertEquals(
            "Enter a valid server URL, like http://192.168.1.5:8080",
            state.error,
        )
        assertEquals(AppAuthState.Loading, sessionManager.state.value)
    }

    @Test
    fun `unreachable server shows a network error and stays on setup`() = runTest {
        val (viewModel, sessionManager) = viewModel { throw IOException("unreachable") }

        viewModel.onInputChange("igloo.local:8080")
        viewModel.connect()

        val state = viewModel.uiState.first { it.error != null }
        assertEquals(
            "Couldn't reach the server. Check the address and your connection.",
            state.error,
        )
        assertFalse(state.isConnecting)
        assertEquals(AppAuthState.Loading, sessionManager.state.value)
    }

    @Test
    fun `typing clears the previous error`() = runTest {
        val (viewModel, _) = viewModel { throw IOException("unreachable") }

        viewModel.onInputChange("igloo.local:8080")
        viewModel.connect()
        viewModel.uiState.first { it.error != null }
        viewModel.onInputChange("igloo.local:8081")

        assertNull(viewModel.uiState.value.error)
    }
}
