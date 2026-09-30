package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.image.NoOpImageCache
import com.igloo.blindpenguincoder.core.storage.InMemoryPreferencesDataStore
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.repository.ServerRepository
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.testServerAddress
import com.igloo.blindpenguincoder.data.repository.testServerHealthProbe
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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
class ServerSetupViewModelTest {

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

    private class Fixture(
        val viewModel: ServerSetupViewModel,
        val sessionManager: SessionManager,
        val http: TestHttp,
    )

    private fun TestScope.fixture(
        initialOrigin: String = "",
        handler: MockRequestHandler,
    ): Fixture {
        val settings = ServerSettingsStore(InMemoryPreferencesDataStore())
        val http = TestHttp { error("Auth client should not be called during setup") }
        http.serverUrl.set(null)
        val authRepository = http.authRepository
        val serverRepository = ServerRepository(
            testServerHealthProbe(handler, dispatcher = UnconfinedTestDispatcher(testScheduler)),
            settings,
            http.serverUrl,
            http.profiles,
        )
        val sessionManager = SessionManager(
            authRepository = authRepository,
            profiles = http.profiles,
            settings = settings,
            serverUrl = http.serverUrl,
            authEvents = http.authEvents,
            imageCache = NoOpImageCache,
            elapsed = { 0L },
            scope = newScope(),
        )
        val viewModel = ServerSetupViewModel(serverRepository, sessionManager).apply {
            beginSetup(initialOrigin)
        }
        return Fixture(
            viewModel,
            sessionManager,
            http,
        )
    }

    @Test
    fun `fresh setup starts empty while change server preloads the current origin`() = runTest {
        val fresh = fixture { respond("") }
        val changed = fixture(initialOrigin = "http://igloo.local:8080") { respond("") }

        assertEquals("", fresh.viewModel.uiState.value.input)
        assertEquals("http://igloo.local:8080", changed.viewModel.uiState.value.input)

        changed.http.serverUrl.set(testServerAddress("http://igloo.local:8080"))
        changed.sessionManager.requireServerChange()
        val state = changed.sessionManager.state.value as AppAuthState.NeedsServer
        assertEquals("http://igloo.local:8080", state.initialOrigin)
    }

    @Test
    fun `successful connect hands the normalized origin to login`() = runTest {
        val fixture = fixture { respond("not json") }

        fixture.viewModel.onInputChange("IGLOO.local:8080")
        fixture.viewModel.connect()

        val state = fixture.sessionManager.state
            .first { it is AppAuthState.NeedsLogin } as AppAuthState.NeedsLogin
        assertEquals("http://igloo.local:8080", state.serverAddress.origin)
        assertEquals("http://igloo.local:8080/api", state.serverAddress.apiBaseUrl)
        assertFalse(fixture.viewModel.uiState.value.isConnecting)
        assertNull(fixture.viewModel.uiState.value.error)
    }

    @Test
    fun `unsupported path shows specific server-address guidance`() = runTest {
        val fixture = fixture { respond("") }

        fixture.viewModel.onInputChange("http://igloo.local/media")
        fixture.viewModel.connect()

        val state = fixture.viewModel.uiState.first { it.error != null }
        assertEquals("Enter only the server address, optionally ending in /api.", state.error)
        assertEquals(AppAuthState.Loading, fixture.sessionManager.state.value)
    }

    @Test
    fun `beginning a reused setup entry resets retained input and errors`() = runTest {
        val fixture = fixture(initialOrigin = "http://server-a.local") {
            throw IOException("unreachable")
        }

        fixture.viewModel.onInputChange("http://server-b.local")
        fixture.viewModel.connect()
        fixture.viewModel.uiState.first { it.error != null }

        fixture.viewModel.beginSetup("http://server-a.local")

        assertEquals(
            ServerSetupUiState(input = "http://server-a.local"),
            fixture.viewModel.uiState.value,
        )
    }

    @Test
    fun `beginning a reused setup entry cancels the previous connection`() = runTest {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val fixture = fixture(initialOrigin = "http://server-a.local") {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }

        fixture.viewModel.onInputChange("http://server-b.local")
        fixture.viewModel.connect()
        started.await()
        assertTrue(fixture.viewModel.uiState.value.isConnecting)

        fixture.viewModel.beginSetup("http://server-a.local")
        cancelled.await()

        assertEquals(
            ServerSetupUiState(input = "http://server-a.local"),
            fixture.viewModel.uiState.value,
        )
        assertEquals(AppAuthState.Loading, fixture.sessionManager.state.value)
    }

    @Test
    fun `unreachable server recovers with retained input and an actionable error`() = runTest {
        val fixture = fixture { throw IOException("unreachable") }

        fixture.viewModel.onInputChange("igloo.local:8080")
        fixture.viewModel.connect()

        val state = fixture.viewModel.uiState.first { it.error != null }
        assertEquals(
            "Couldn't reach the server. Check the address, port, and network connection.",
            state.error,
        )
        assertEquals("igloo.local:8080", state.input)
        assertFalse(state.isConnecting)
        assertEquals(AppAuthState.Loading, fixture.sessionManager.state.value)
    }

    @Test
    fun `duplicate submissions are ignored while a connection is active`() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var requests = 0
        val fixture = fixture {
            requests += 1
            started.complete(Unit)
            release.await()
            respond("")
        }
        fixture.viewModel.onInputChange("igloo.local")

        fixture.viewModel.connect()
        assertTrue(fixture.viewModel.uiState.value.isConnecting)
        started.await()
        fixture.viewModel.connect()
        assertEquals(1, requests)

        release.complete(Unit)
        fixture.sessionManager.state.first { it is AppAuthState.NeedsLogin }
        assertEquals(1, requests)
    }

    @Test
    fun `typing after failure clears the previous error`() = runTest {
        val fixture = fixture { throw IOException("unreachable") }

        fixture.viewModel.onInputChange("igloo.local:8080")
        fixture.viewModel.connect()
        fixture.viewModel.uiState.first { it.error != null }
        fixture.viewModel.onInputChange("igloo.local:8081")

        assertNull(fixture.viewModel.uiState.value.error)
    }
}
