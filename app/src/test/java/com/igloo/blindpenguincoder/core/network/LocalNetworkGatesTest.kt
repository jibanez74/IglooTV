package com.igloo.blindpenguincoder.core.network

import com.igloo.blindpenguincoder.core.storage.InMemoryPreferencesDataStore
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.repository.*
import com.igloo.blindpenguincoder.feature.auth.AppAuthState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalNetworkGatesTest {
    @Test
    fun `public API redirects cannot reach a LAN destination before permission grant`() = runTest {
        var granted = false
        var requests = 0
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val permission = LocalNetworkPermissionCoordinator(
            { granted }, { true }, {}, { it.contains("igloo.local") }, dispatcher,
        )
        val client = createIglooHttpClient(
            BearerTokenProvider(), AuthEventBus(),
            MockEngine(MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler { request ->
                    requests++
                    if (request.url.host == "public.example") {
                        respond("", HttpStatusCode.Found, headersOf("Location", "https://igloo.local/api"))
                    } else {
                        respond("ok")
                    }
                }
            }),
            permission,
        )
        val pending = async { client.get("https://public.example/api") }
        runCurrent()
        assertEquals(1, requests)
        assertEquals("igloo.local", permission.state.value.blockedHost)
        granted = true
        permission.refresh()
        pending.await()
        assertEquals(2, requests)
        client.close()
    }

    @Test
    fun `API request cannot reach engine before grant and resumes exactly once`() = runTest {
        var granted = false
        var calls = 0
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val permission = LocalNetworkPermissionCoordinator(
            { granted }, { true }, {}, { true }, dispatcher,
        )
        val client = createIglooHttpClient(
            BearerTokenProvider(), AuthEventBus(),
            MockEngine(MockEngineConfig().apply {
                this.dispatcher = dispatcher
                addHandler { calls++; respond("ok") }
            }),
            permission,
        )
        val pending = async { client.get("https://igloo.local/api/health") }
        runCurrent()
        assertEquals(0, calls)
        assertFalse(pending.isCompleted)
        permission.onPermissionResult()
        runCurrent()
        assertEquals(0, calls)
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(pending.isCompleted)
        granted = true
        permission.refresh()
        pending.await()
        permission.refresh()
        runCurrent()
        assertEquals(1, calls)
        client.close()
    }

    @Test
    fun `saved restoration retains credential and server while denied then signs in once`() = runTest {
        var granted = false
        var calls = 0
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val permission = LocalNetworkPermissionCoordinator({ granted }, { true }, {}, { true }, dispatcher)
        val http = TestHttp(engineDispatcher = dispatcher) { calls++; jsonResponse(authUserJson()) }
        http.seedVault(testStoredProfile())
        val originalVault = http.profileStore.vault
        val settings = ServerSettingsStore(InMemoryPreferencesDataStore())
        settings.save(TEST_SERVER)
        http.serverUrl.set(null)
        val manager = http.sessionManager(backgroundScope, settings, networkAccess = permission)
        val pending = async { manager.restoreOnLaunch() }
        runCurrent()
        permission.onPermissionResult()
        runCurrent()
        assertEquals(AppAuthState.Loading, manager.state.value)
        assertEquals(0, calls)
        assertEquals(originalVault, http.profileStore.vault)
        assertEquals(TEST_SERVER, settings.serverUrl.first())
        granted = true
        permission.refresh()
        pending.await()
        manager.restoreOnLaunch()
        assertTrue(manager.state.value is AppAuthState.Authenticated)
        assertEquals(1, calls)
        assertEquals("igd_1", http.profileStore.vault.profiles.single().token)
        http.client.close()
    }

    @Test
    fun `server connection waits before probe timeout and preserves saved profiles on denial`() = runTest {
        var granted = false
        var probes = 0
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val permission = LocalNetworkPermissionCoordinator({ granted }, { true }, {}, { true }, dispatcher)
        val http = TestHttp(engineDispatcher = dispatcher) { error("no authenticated requests") }
        http.seedVault(testStoredProfile())
        val originalVault = http.profileStore.vault
        val settings = ServerSettingsStore(InMemoryPreferencesDataStore())
        settings.save(TEST_SERVER)
        val repository = ServerRepository(
            testServerHealthProbe({ probes++; respond("") }, dispatcher = dispatcher),
            settings, http.serverUrl, http.profiles, permission,
        )
        val pending = async { repository.connect(TEST_SERVER) }
        runCurrent()
        permission.onPermissionResult()
        runCurrent()
        assertEquals(0, probes)
        assertEquals(originalVault, http.profileStore.vault)
        assertEquals(TEST_SERVER, settings.serverUrl.first())
        granted = true
        permission.refresh()
        pending.await()
        assertEquals(1, probes)
        assertEquals(originalVault, http.profileStore.vault)
        http.client.close()
    }
}
