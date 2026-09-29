package com.igloo.blindpenguincoder.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.igloo.blindpenguincoder.core.image.NoOpImageCache
import com.igloo.blindpenguincoder.core.storage.InMemoryPreferencesDataStore
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.repository.TEST_SERVER
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.authUserJson
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.testStoredProfile
import com.igloo.blindpenguincoder.feature.auth.AppAuthState
import com.igloo.blindpenguincoder.feature.auth.SessionManager
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignOutViewModelTest {

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

    private class Fixture(
        val viewModel: SignOutViewModel,
        val sessionManager: SessionManager,
        val http: TestHttp,
    ) {
        var logoutRequests = 0
    }

    /**
     * Signed in as Jose with Ana also stored, so each test also asks whether the *other* profile
     * survived — the invariant in docs/design-system.md section 11.2.
     */
    private suspend fun fixture(
        logout: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Fixture {
        val settings = ServerSettingsStore(InMemoryPreferencesDataStore())
        settings.save(TEST_SERVER)
        lateinit var fixture: Fixture
        // Unconfined engine dispatcher keeps the request on the test scheduler, so a handler
        // gated on a Deferred can be released and awaited deterministically.
        val http = TestHttp(engineDispatcher = Dispatchers.Unconfined) { request ->
            if (request.url.encodedPath.endsWith("/logout")) {
                fixture.logoutRequests += 1
                logout(request)
            } else {
                jsonResponse(authUserJson())
            }
        }
        val scope = CoroutineScope(UnconfinedTestDispatcher()).also { scopes += it }
        val sessionManager = SessionManager(
            authRepository = http.authRepository,
            profiles = http.profiles,
            settings = settings,
            serverUrl = http.serverUrl,
            authEvents = http.authEvents,
            imageCache = NoOpImageCache,
            scope = scope,
            elapsed = { 0L },
        )
        fixture = Fixture(SignOutViewModel(sessionManager), sessionManager, http)
        http.seedVault(
            testStoredProfile(userId = 1, name = "Jose"),
            testStoredProfile(userId = 2, name = "Ana"),
        )
        sessionManager.restore()
        sessionManager.signInAs(
            (sessionManager.state.value as AppAuthState.ChooseProfile).profiles.first {
                it.userId == 1L
            },
        )
        return fixture
    }

    private fun MockRequestHandleScope.accepted() =
        jsonResponse("""{"error":false,"message":"bye"}""")

    @Test
    fun `requesting a sign-out only asks`() = runTest {
        val fixture = fixture { accepted() }

        fixture.viewModel.request()

        assertEquals(SignOutUiState(confirming = true), fixture.viewModel.uiState.value)
        assertEquals(0, fixture.logoutRequests)
        assertTrue(fixture.sessionManager.state.value is AppAuthState.Authenticated)
        assertEquals(2, fixture.http.profileStore.vault.profiles.size)
    }

    @Test
    fun `dismissing leaves the session alone`() = runTest {
        val fixture = fixture { accepted() }
        fixture.viewModel.request()

        fixture.viewModel.dismiss()

        assertEquals(SignOutUiState(), fixture.viewModel.uiState.value)
        assertEquals(0, fixture.logoutRequests)
        assertTrue(fixture.sessionManager.state.value is AppAuthState.Authenticated)
    }

    @Test
    fun `confirming reports pending while the revoke is in flight`() = runTest {
        val release = CompletableDeferred<Unit>()
        val fixture = fixture {
            release.await()
            accepted()
        }
        fixture.viewModel.request()

        fixture.viewModel.confirm()
        yield()

        assertEquals(SignOutUiState(confirming = true, pending = true), fixture.viewModel.uiState.value)
        assertTrue(fixture.sessionManager.state.value is AppAuthState.Authenticated)

        release.complete(Unit)
        yield()

        assertEquals(SignOutUiState(), fixture.viewModel.uiState.value)
        val state = fixture.sessionManager.state.value as AppAuthState.ChooseProfile
        assertEquals(listOf("Ana"), state.profiles.map { it.name })
    }

    @Test
    fun `a second confirm while one is in flight makes one request`() = runTest {
        val release = CompletableDeferred<Unit>()
        val fixture = fixture {
            release.await()
            accepted()
        }
        fixture.viewModel.request()
        fixture.viewModel.confirm()
        yield()

        fixture.viewModel.confirm()
        fixture.viewModel.confirm()
        release.complete(Unit)
        yield()

        assertEquals(1, fixture.logoutRequests)
    }

    @Test
    fun `a pending sign-out cannot be reopened`() = runTest {
        val release = CompletableDeferred<Unit>()
        val fixture = fixture {
            release.await()
            accepted()
        }
        fixture.viewModel.request()
        fixture.viewModel.confirm()
        yield()

        fixture.viewModel.request()

        assertTrue(fixture.viewModel.uiState.value.pending)
        release.complete(Unit)
        yield()
    }

    @Test
    fun `a revoke that fails still signs out and warns`() = runTest {
        val fixture = fixture { throw IOException("offline") }
        fixture.viewModel.request()

        fixture.viewModel.confirm()
        yield()

        // Reset regardless, so signing back in does not find the dialog still up.
        assertEquals(SignOutUiState(), fixture.viewModel.uiState.value)
        val state = fixture.sessionManager.state.value as AppAuthState.ChooseProfile
        assertEquals(SessionManager.UNDELIVERED_REVOKE_NOTICE, state.notice)
        // The other profile is untouched even on the failure path.
        assertEquals(listOf("Ana"), state.profiles.map { it.name })
        assertNull(fixture.http.credentials.current())
    }

    @Test
    fun `dismissing does not abandon a revoke already in flight`() = runTest {
        val release = CompletableDeferred<Unit>()
        val fixture = fixture {
            release.await()
            accepted()
        }
        fixture.viewModel.request()
        fixture.viewModel.confirm()
        yield()

        // Back is live while pending: the request cannot be recalled, so it must still finish.
        fixture.viewModel.dismiss()
        assertFalse(fixture.viewModel.uiState.value.confirming)
        release.complete(Unit)
        yield()

        assertEquals(1, fixture.logoutRequests)
        assertTrue(fixture.sessionManager.state.value is AppAuthState.ChooseProfile)
        assertNull(fixture.http.credentials.current())
    }

    @Test
    fun `back during a pending sign-out cannot reopen the confirmation`() = runTest {
        val release = CompletableDeferred<Unit>()
        val fixture = fixture {
            release.await()
            accepted()
        }
        fixture.viewModel.request()
        fixture.viewModel.confirm()
        yield()

        // Back closes the modal, but the local half is already committed: asking again would
        // offer a Cancel that cannot undo anything.
        fixture.viewModel.dismiss()
        fixture.viewModel.request()

        assertFalse(fixture.viewModel.uiState.value.confirming)
        assertTrue(fixture.viewModel.uiState.value.pending)

        release.complete(Unit)
        yield()

        assertEquals(1, fixture.logoutRequests)
        // Cleared once the revoke is done, so signing back in on this instance can ask again.
        assertEquals(SignOutUiState(), fixture.viewModel.uiState.value)
    }

    @Test
    fun `clearing the ViewModel stops waiting without cancelling sign-out cleanup`() = runTest {
        val revokeStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = fixture {
            revokeStarted.complete(Unit)
            release.await()
            accepted()
        }
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
        val viewModel = ViewModelProvider(
            owner,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SignOutViewModel(fixture.sessionManager) as T
            },
        )[SignOutViewModel::class.java]
        viewModel.request()

        viewModel.confirm()
        revokeStarted.await()

        // Local persistence has already crossed the security boundary before the delayed request.
        assertEquals(listOf("Ana"), fixture.http.profileStore.vault.profiles.map { it.name })
        assertNull(fixture.http.credentials.current())

        store.clear()
        release.complete(Unit)
        yield()

        assertEquals(1, fixture.logoutRequests)
        val state = fixture.sessionManager.state.value as AppAuthState.ChooseProfile
        assertEquals(listOf("Ana"), state.profiles.map { it.name })
    }
}
