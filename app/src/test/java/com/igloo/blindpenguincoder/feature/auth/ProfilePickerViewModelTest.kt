package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.storage.InMemoryPreferencesDataStore
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.repository.ProfileRepository
import com.igloo.blindpenguincoder.data.repository.TEST_SERVER
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.authUserJson
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.testStoredProfile
import io.ktor.client.engine.mock.MockRequestHandler
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilePickerViewModelTest {

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
        val viewModel: ProfilePickerViewModel,
        val sessionManager: SessionManager,
        val http: TestHttp,
    ) {
        var requestCount = 0
        val picker get() = sessionManager.state.value as AppAuthState.ChooseProfile
    }

    private suspend fun fixture(
        profileCount: Int = 2,
        hasPin: Boolean = false,
        handler: MockRequestHandler,
    ): Fixture {
        val settings = ServerSettingsStore(InMemoryPreferencesDataStore())
        settings.save(TEST_SERVER)
        lateinit var fixture: Fixture
        val http = TestHttp { request ->
            fixture.requestCount += 1
            handler(request)
        }
        val sessionManager = SessionManager(
            authRepository = http.authRepository,
            profiles = http.profiles,
            settings = settings,
            serverUrl = http.serverUrl,
            authEvents = http.authEvents,
            scope = newScope(),
            elapsed = { 0L },
        )
        fixture = Fixture(ProfilePickerViewModel(sessionManager), sessionManager, http)
        http.seedVault(
            *(1..profileCount).map {
                testStoredProfile(userId = it.toLong(), name = "User$it", hasPin = hasPin)
            }.toTypedArray(),
        )
        sessionManager.restore()
        return fixture
    }

    @Test
    fun `selecting a profile without a PIN signs it in`() = runTest {
        val fixture = fixture { jsonResponse(authUserJson(id = 1, name = "User1")) }

        fixture.viewModel.select(fixture.picker.profiles.first { it.userId == 1L })

        assertTrue(fixture.sessionManager.state.first { it is AppAuthState.Authenticated } != null)
    }

    @Test
    fun `selecting a PIN-protected profile asks before touching the network`() = runTest {
        val fixture = fixture(hasPin = true) { error("no request expected") }
        val before = fixture.requestCount

        fixture.viewModel.select(fixture.picker.profiles.first { it.userId == 1L })

        val state = fixture.sessionManager.state.value as AppAuthState.NeedsPin
        assertEquals(1L, state.profile.userId)
        assertEquals(before, fixture.requestCount)
    }

    @Test
    fun `a transient failure leaves an error on the picker`() = runTest {
        val fixture = fixture { throw java.io.IOException("offline") }

        fixture.viewModel.select(fixture.picker.profiles.first())

        assertNotNull(fixture.viewModel.uiState.first { it.error != null }.error)
        assertTrue(fixture.sessionManager.state.value is AppAuthState.ChooseProfile)
    }

    @Test
    fun `adding a profile moves to sign-in with a way back`() = runTest {
        val fixture = fixture { error("no request expected") }

        fixture.viewModel.addProfile(profileCount = 2)

        val state = fixture.sessionManager.state.value as AppAuthState.NeedsLogin
        assertTrue(state.canCancel)
    }

    @Test
    fun `a full TV explains itself instead of starting another pairing`() = runTest {
        val fixture = fixture(profileCount = ProfileRepository.MAX_PROFILES) {
            error("no request expected")
        }

        fixture.viewModel.addProfile(profileCount = ProfileRepository.MAX_PROFILES)

        assertTrue(fixture.sessionManager.state.value is AppAuthState.ChooseProfile)
        assertEquals(
            "This TV already has ${ProfileRepository.MAX_PROFILES} profiles. Sign one out first.",
            fixture.viewModel.uiState.value.error,
        )
    }

    @Test
    fun `changing server leaves the picker for setup`() = runTest {
        val fixture = fixture { error("no request expected") }

        fixture.viewModel.changeServer()

        assertTrue(fixture.sessionManager.state.value is AppAuthState.NeedsServer)
    }
}
