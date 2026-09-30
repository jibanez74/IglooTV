package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.image.NoOpImageCache
import com.igloo.blindpenguincoder.core.storage.InMemoryPreferencesDataStore
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.repository.TEST_SERVER
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.authUserJson
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.testStoredProfile
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
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
class PinEntryViewModelTest {

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
        val viewModel: PinEntryViewModel,
        val sessionManager: SessionManager,
        val http: TestHttp,
    )

    /**
     * Opens the keypad the way the picker does, and routes only `/user/pin/verify` to [handler] —
     * the gate itself needs `/auth/user` to keep answering, since the server's `has_pin` is what
     * puts this screen up and the sign-in behind a verified PIN asks again.
     */
    private suspend fun fixture(handler: MockRequestHandler): Fixture {
        val settings = ServerSettingsStore(InMemoryPreferencesDataStore())
        settings.save(TEST_SERVER)
        val http = TestHttp { request ->
            if (request.url.encodedPath.endsWith("/user/pin/verify")) {
                handler(request)
            } else {
                jsonResponse(authUserJson(name = "Jose", hasPin = true))
            }
        }
        val sessionManager = SessionManager(
            authRepository = http.authRepository,
            profiles = http.profiles,
            settings = settings,
            serverUrl = http.serverUrl,
            authEvents = http.authEvents,
            imageCache = NoOpImageCache,
            scope = newScope(),
            elapsed = { 0L },
        )
        http.seedVault(
            testStoredProfile(userId = 1, name = "Jose", hasPin = true),
            testStoredProfile(userId = 2, name = "Ana"),
        )
        sessionManager.restore()
        sessionManager.signInAs(
            (sessionManager.state.value as AppAuthState.ChooseProfile)
                .profiles.first { it.userId == 1L },
        )
        return Fixture(
            PinEntryViewModel(http.authRepository, sessionManager),
            sessionManager,
            http,
        )
    }

    private fun PinEntryViewModel.enter(pin: String) = pin.forEach { append(it) }

    @Test
    fun `a correct PIN signs the profile in`() = runTest {
        val fixture = fixture { jsonResponse("""{"error":false,"data":{"valid":true}}""") }

        fixture.viewModel.enter("1234")

        // Signing in behind the verified PIN must not bounce back to the keypad, even though
        // the user still has a PIN.
        assertTrue(fixture.sessionManager.state.first { it is AppAuthState.Authenticated } != null)
    }

    @Test
    fun `reset clears a rejection left by an earlier visit to the keypad`() = runTest {
        val fixture = fixture { jsonResponse("""{"error":false,"data":{"valid":false}}""") }
        fixture.viewModel.enter("9999")
        fixture.viewModel.uiState.first { it.error != null }

        fixture.viewModel.reset()

        assertEquals(PinEntryUiState(), fixture.viewModel.uiState.value)
    }

    @Test
    fun `a wrong PIN clears the digits and keeps the profile`() = runTest {
        val fixture = fixture { jsonResponse("""{"error":false,"data":{"valid":false}}""") }

        fixture.viewModel.enter("9999")

        val state = fixture.viewModel.uiState.first { it.error != null }
        assertEquals(0, state.enteredCount)
        assertEquals("Incorrect PIN.", state.error)
        assertFalse(state.isVerifying)
        assertTrue(fixture.sessionManager.state.value is AppAuthState.NeedsPin)
        assertEquals(2, fixture.http.profileStore.vault.profiles.size)
    }

    @Test
    fun `a dead device token drops the profile and returns to the picker`() = runTest {
        val fixture = fixture {
            jsonResponse("""{"error":true,"message":"gone"}""", HttpStatusCode.Unauthorized)
        }

        fixture.viewModel.enter("1234")

        val state = fixture.sessionManager.state
            .first { it is AppAuthState.ChooseProfile } as AppAuthState.ChooseProfile
        assertEquals(listOf("Ana"), state.profiles.map { it.name })
    }

    @Test
    fun `a rate limit surfaces the backend message verbatim`() = runTest {
        val fixture = fixture {
            jsonResponse(
                """{"error":true,"message":"too many attempts, try again later"}""",
                HttpStatusCode.TooManyRequests,
            )
        }

        fixture.viewModel.enter("1234")

        assertEquals(
            "too many attempts, try again later",
            fixture.viewModel.uiState.first { it.error != null }.error,
        )
        assertEquals(2, fixture.http.profileStore.vault.profiles.size)
    }

    @Test
    fun `input takes only digits and stops at four`() = runTest {
        val fixture = fixture { error("no request expected") }

        fixture.viewModel.append('a')
        fixture.viewModel.append('#')
        assertEquals(0, fixture.viewModel.uiState.value.enteredCount)

        fixture.viewModel.append('1')
        fixture.viewModel.append('2')
        assertEquals(2, fixture.viewModel.uiState.value.enteredCount)

        fixture.viewModel.delete()
        assertEquals(1, fixture.viewModel.uiState.value.enteredCount)
    }

    @Test
    fun `the fourth digit submits exactly once`() = runTest {
        var verifications = 0
        val fixture = fixture {
            verifications += 1
            jsonResponse("""{"error":false,"data":{"valid":false}}""")
        }

        fixture.viewModel.enter("1234")
        fixture.viewModel.uiState.first { it.error != null }
        // The screen clears on rejection, so a fifth press is a fresh first digit.
        fixture.viewModel.append('5')

        assertEquals(1, verifications)
        assertEquals(1, fixture.viewModel.uiState.value.enteredCount)
    }

    @Test
    fun `the entered PIN never reaches the UI state`() = runTest {
        val fixture = fixture { error("no request expected") }

        fixture.viewModel.append('7')
        fixture.viewModel.append('7')

        val rendered = fixture.viewModel.uiState.value.toString()
        assertFalse(rendered.contains("7"))
        assertNull(fixture.viewModel.uiState.value.error)
    }
}
