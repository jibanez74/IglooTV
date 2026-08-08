package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.storage.InMemoryPreferencesDataStore
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.repository.TEST_SERVER
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.authUserJson
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.testStoredProfile
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {

    private class Fixture(val manager: SessionManager, val http: TestHttp) {
        var requestCount = 0
        var elapsed = 0L
    }

    private suspend fun fixture(
        storedServerUrl: String?,
        scope: CoroutineScope,
        handler: MockRequestHandler,
    ): Fixture {
        val settings = ServerSettingsStore(InMemoryPreferencesDataStore())
        lateinit var fixture: Fixture
        val http = TestHttp { request ->
            fixture.requestCount += 1
            handler(request)
        }
        http.serverUrl.set(null)
        val manager = SessionManager(
            authRepository = http.authRepository,
            profiles = http.profiles,
            settings = settings,
            serverUrl = http.serverUrl,
            authEvents = http.authEvents,
            scope = scope,
            elapsed = { fixture.elapsed },
        )
        fixture = Fixture(manager, http)
        if (storedServerUrl != null) settings.save(storedServerUrl)
        return fixture
    }

    private fun noRequests(): MockRequestHandler = { error("no request expected") }

    @Test
    fun `no stored server leads to NeedsServer`() = runTest {
        val fixture = fixture(null, backgroundScope, noRequests())

        fixture.manager.restore()

        assertEquals(
            AppAuthState.NeedsServer(firstRun = true),
            fixture.manager.state.value,
        )
        assertNull(fixture.http.serverUrl.current.value)
    }

    @Test
    fun `no stored profile leads to NeedsLogin without any network call`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope, noRequests())

        fixture.manager.restore()

        val state = fixture.manager.state.value as AppAuthState.NeedsLogin
        assertEquals(TEST_SERVER, state.serverAddress.apiBaseUrl)
        assertNull(state.restoreError)
        assertFalse(state.canCancel)
        assertEquals(0, fixture.requestCount)
    }

    @Test
    fun `a single profile without a PIN signs in straight away`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope) { jsonResponse(authUserJson()) }
        fixture.http.seedVault(testStoredProfile())

        fixture.manager.restore()

        val state = fixture.manager.state.value as AppAuthState.Authenticated
        assertEquals("Jose", state.user.name)
        assertEquals(1, fixture.requestCount)
        assertEquals(TEST_SERVER, fixture.http.serverUrl.current.value?.apiBaseUrl)
    }

    @Test
    fun `a single profile with a PIN stops at the PIN gate`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope, noRequests())
        fixture.http.seedVault(testStoredProfile(hasPin = true))

        fixture.manager.restore()

        val state = fixture.manager.state.value as AppAuthState.NeedsPin
        assertEquals("Jose", state.profile.name)
        assertEquals(0, fixture.requestCount)
    }

    @Test
    fun `several profiles show the picker without touching the network`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope, noRequests())
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose", lastUsedAtEpochMillis = 10),
            testStoredProfile(userId = 2, name = "Ana", lastUsedAtEpochMillis = 20),
            activeUserId = 2,
        )

        fixture.manager.restore()

        val state = fixture.manager.state.value as AppAuthState.ChooseProfile
        assertEquals(listOf("Ana", "Jose"), state.profiles.map { it.name })
        assertEquals(2L, state.initialFocusUserId)
        assertEquals(0, fixture.requestCount)
    }

    @Test
    fun `a pending token resumes an interrupted pairing`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope) { jsonResponse(authUserJson()) }
        fixture.http.seedVault(pendingToken = "igd_pending")

        fixture.manager.restore()

        assertTrue(fixture.manager.state.value is AppAuthState.Authenticated)
        assertNull(fixture.http.profileStore.vault.pendingToken)
        assertEquals(1, fixture.http.profileStore.vault.profiles.size)
    }

    @Test
    fun `a revoked single profile is forgotten and lands on NeedsLogin`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope) {
            jsonResponse("""{"error":true,"message":"expired"}""", HttpStatusCode.Unauthorized)
        }
        fixture.http.seedVault(testStoredProfile())

        fixture.manager.restore()

        val state = fixture.manager.state.value as AppAuthState.NeedsLogin
        assertEquals("http://igloo.test:8080", state.serverAddress.origin)
        assertTrue(fixture.http.profileStore.vault.profiles.isEmpty())
    }

    @Test
    fun `an unreachable server keeps the profile and offers a retry`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope) { throw IOException("no route to host") }
        fixture.http.seedVault(testStoredProfile())

        fixture.manager.restore()

        // The picker, not sign-in: the profile is still valid, so offering to pair again
        // would mint a second device for the same person.
        val state = fixture.manager.state.value as AppAuthState.ChooseProfile
        assertEquals(AppError.Network, state.restoreError)
        assertEquals(1, fixture.http.profileStore.vault.profiles.size)
    }

    @Test
    fun `signing in as a revoked profile removes only that profile`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope) {
            jsonResponse("""{"error":true,"message":"expired"}""", HttpStatusCode.Unauthorized)
        }
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose"),
            testStoredProfile(userId = 2, name = "Ana"),
        )
        fixture.manager.restore()

        val result = fixture.manager.signInAs(
            (fixture.manager.state.value as AppAuthState.ChooseProfile).profiles.first { it.userId == 1L },
        )

        assertEquals(SignInResult.Revoked, result)
        val state = fixture.manager.state.value as AppAuthState.ChooseProfile
        assertEquals(listOf("Ana"), state.profiles.map { it.name })
        assertNotNull(state.notice)
    }

    @Test
    fun `a transient failure keeps the picker up and keeps the profile`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope) { throw IOException("offline") }
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose"),
            testStoredProfile(userId = 2, name = "Ana"),
        )
        fixture.manager.restore()
        val picker = fixture.manager.state.value as AppAuthState.ChooseProfile

        val result = fixture.manager.signInAs(picker.profiles.first())

        assertTrue(result is SignInResult.Failed)
        assertTrue(fixture.manager.state.value is AppAuthState.ChooseProfile)
        assertEquals(2, fixture.http.profileStore.vault.profiles.size)
    }

    @Test
    fun `a mid-session rejection of the active profile returns to the picker`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope) { jsonResponse(authUserJson()) }
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose"),
            testStoredProfile(userId = 2, name = "Ana"),
        )
        fixture.manager.restore()
        fixture.manager.signInAs(
            (fixture.manager.state.value as AppAuthState.ChooseProfile).profiles.first { it.userId == 1L },
        )

        fixture.manager.onActiveSessionRevoked()

        val state = fixture.manager.state.value as AppAuthState.ChooseProfile
        assertEquals(listOf("Ana"), state.profiles.map { it.name })
    }

    @Test
    fun `a rejection arriving after the session ended is ignored`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope, noRequests())
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose"),
            testStoredProfile(userId = 2, name = "Ana"),
        )
        fixture.manager.restore()

        // The picker is showing: nobody is signed in, so there is nothing to revoke.
        fixture.manager.onActiveSessionRevoked()

        assertEquals(2, fixture.http.profileStore.vault.profiles.size)
        assertTrue(fixture.manager.state.value is AppAuthState.ChooseProfile)
    }

    @Test
    fun `switching profile keeps every profile paired`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope) { jsonResponse(authUserJson()) }
        fixture.http.seedVault(testStoredProfile())
        fixture.manager.restore()

        fixture.manager.switchProfile()

        assertEquals(1, fixture.http.profileStore.vault.profiles.size)
        assertNull(fixture.http.profiles.activeProfileId)
        assertTrue(fixture.manager.state.value is AppAuthState.ChooseProfile)
    }

    @Test
    fun `signing out removes only the active profile`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope) { request ->
            if (request.url.encodedPath.endsWith("/logout")) {
                jsonResponse("""{"error":false,"message":"bye"}""")
            } else {
                jsonResponse(authUserJson())
            }
        }
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose"),
            testStoredProfile(userId = 2, name = "Ana"),
        )
        fixture.manager.restore()
        fixture.manager.signInAs(
            (fixture.manager.state.value as AppAuthState.ChooseProfile).profiles.first { it.userId == 1L },
        )

        fixture.manager.logout()

        val state = fixture.manager.state.value as AppAuthState.ChooseProfile
        assertEquals(listOf("Ana"), state.profiles.map { it.name })
    }

    @Test
    fun `adding a profile offers a way back and drops any half-finished pairing`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope, noRequests())
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose"),
            testStoredProfile(userId = 2, name = "Ana"),
        )
        fixture.manager.restore()
        // An earlier attempt at adding a user left a token nobody claimed.
        fixture.http.profileStore.vault =
            fixture.http.profileStore.vault.copy(pendingToken = "igd_abandoned")

        fixture.manager.addProfile()

        val state = fixture.manager.state.value as AppAuthState.NeedsLogin
        assertTrue(state.canCancel)
        assertNull(fixture.http.profileStore.vault.pendingToken)
    }

    @Test
    fun `cancelling add-a-profile returns to the picker`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope, noRequests())
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose"),
            testStoredProfile(userId = 2, name = "Ana"),
        )
        fixture.manager.restore()
        fixture.manager.addProfile()

        fixture.manager.cancelAddProfile()

        val state = fixture.manager.state.value as AppAuthState.ChooseProfile
        assertEquals(2, state.profiles.size)
    }

    @Test
    fun `revalidation inside the interval makes no request`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope) { jsonResponse(authUserJson()) }
        fixture.http.seedVault(testStoredProfile())
        fixture.manager.restore()
        val afterRestore = fixture.requestCount

        fixture.manager.revalidateActive()

        assertEquals(afterRestore, fixture.requestCount)
    }

    @Test
    fun `revalidation after the interval refreshes the user without leaving the shell`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope) {
            jsonResponse(authUserJson(name = "Jose Renamed"))
        }
        fixture.http.seedVault(testStoredProfile())
        fixture.manager.restore()
        fixture.elapsed += 10 * 60 * 1000L

        fixture.manager.revalidateActive()

        val state = fixture.manager.state.value as AppAuthState.Authenticated
        assertEquals("Jose Renamed", state.user.name)
        assertEquals("Jose Renamed", fixture.http.profileStore.vault.profiles.single().name)
    }

    @Test
    fun `revalidation offline leaves the session alone`() = runTest {
        var offline = false
        val fixture = fixture(TEST_SERVER, backgroundScope) {
            if (offline) throw IOException("offline") else jsonResponse(authUserJson())
        }
        fixture.http.seedVault(testStoredProfile())
        fixture.manager.restore()
        fixture.elapsed += 10 * 60 * 1000L
        offline = true

        fixture.manager.revalidateActive()

        assertTrue(fixture.manager.state.value is AppAuthState.Authenticated)
        assertEquals(1, fixture.http.profileStore.vault.profiles.size)
    }

    @Test
    fun `revalidation rejected by the server returns to sign-in`() = runTest {
        var revoked = false
        val fixture = fixture(TEST_SERVER, backgroundScope) {
            if (revoked) {
                jsonResponse("""{"error":true,"message":"gone"}""", HttpStatusCode.Unauthorized)
            } else {
                jsonResponse(authUserJson())
            }
        }
        fixture.http.seedVault(testStoredProfile())
        fixture.manager.restore()
        fixture.elapsed += 10 * 60 * 1000L
        revoked = true

        fixture.manager.revalidateActive()

        assertTrue(fixture.manager.state.value is AppAuthState.NeedsLogin)
        assertTrue(fixture.http.profileStore.vault.profiles.isEmpty())
    }

    @Test
    fun `invalid stored api base wipes the vault and returns to fresh setup`() = runTest {
        val fixture = fixture("http://igloo.test:8080/not-api", backgroundScope, noRequests())
        fixture.http.seedVault(testStoredProfile())

        fixture.manager.restore()

        // firstRun stays false: this user had a server and deserves the reconnect prompt, not
        // the first-run welcome.
        assertEquals(AppAuthState.NeedsServer(firstRun = false), fixture.manager.state.value)
        assertTrue(fixture.http.profileStore.vault.profiles.isEmpty())
        assertNull(fixture.http.serverUrl.current.value)
    }
}
