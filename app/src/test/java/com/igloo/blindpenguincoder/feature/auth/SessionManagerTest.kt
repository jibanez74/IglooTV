package com.igloo.blindpenguincoder.feature.auth

import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.image.ImageCache
import com.igloo.blindpenguincoder.core.storage.InMemoryPreferencesDataStore
import com.igloo.blindpenguincoder.core.storage.ServerSettingsStore
import com.igloo.blindpenguincoder.data.repository.TEST_SERVER
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.authUserJson
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.testStoredProfile
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
    ): Fixture = fixture(
        storedServerUrl = storedServerUrl,
        scope = scope,
        engineDispatcher = null,
        handler = handler,
    )

    private suspend fun fixture(
        storedServerUrl: String?,
        scope: CoroutineScope,
        engineDispatcher: CoroutineDispatcher?,
        imageCache: ImageCache = ImageCache.None,
        handler: MockRequestHandler,
    ): Fixture {
        val settings = ServerSettingsStore(InMemoryPreferencesDataStore())
        lateinit var fixture: Fixture
        val http = TestHttp(engineDispatcher = engineDispatcher) { request ->
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
            imageCache = imageCache,
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
        // Carried on the state itself: the rail resolves avatars against it rather than
        // reaching for a provider from composition.
        assertEquals(TEST_SERVER, state.serverAddress.apiBaseUrl)
    }

    /**
     * Without an address there is no session, and the http client refuses to build the request
     * before `completeSignIn` ever gets to look — so the address-missing branch inside it is
     * defence in depth rather than a reachable path. What matters either way is asserted here:
     * a sign-in that cannot name its server publishes no session.
     */
    @Test
    fun `a sign-in with no server address publishes no session`() = runTest {
        val fixture = fixture(null, backgroundScope) { jsonResponse(authUserJson()) }
        fixture.http.seedVault(testStoredProfile(), activeUserId = 1)

        val result = fixture.manager.completeSignIn()

        assertTrue(result is SignInResult.Failed)
        assertEquals(AppAuthState.Loading, fixture.manager.state.value)
    }

    @Test
    fun `a single profile with a PIN stops at the PIN gate`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope) {
            jsonResponse(authUserJson(hasPin = true))
        }
        fixture.http.seedVault(testStoredProfile(hasPin = true))

        fixture.manager.restore()

        val state = fixture.manager.state.value as AppAuthState.NeedsPin
        assertEquals("Jose", state.profile.name)
        assertTrue(state.profile.hasPin)
    }

    @Test
    fun `a PIN set elsewhere since the last sign-in is still asked for`() = runTest {
        // The vault says no PIN because that was true when this TV last signed Jose in.
        val fixture = fixture(TEST_SERVER, backgroundScope) {
            jsonResponse(authUserJson(hasPin = true))
        }
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose", hasPin = false),
            testStoredProfile(userId = 2, name = "Ana"),
        )
        fixture.manager.restore()
        val picker = fixture.manager.state.value as AppAuthState.ChooseProfile

        val result = fixture.manager.signInAs(picker.profiles.first { it.userId == 1L })

        assertEquals(SignInResult.PinRequired, result)
        val state = fixture.manager.state.value as AppAuthState.NeedsPin
        assertEquals("Jose", state.profile.name)
    }

    @Test
    fun `nothing is committed while the PIN gate is open`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope) {
            jsonResponse(authUserJson(hasPin = true))
        }
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose", hasPin = false),
            testStoredProfile(userId = 2, name = "Ana"),
            activeUserId = 2,
        )
        fixture.manager.restore()
        val picker = fixture.manager.state.value as AppAuthState.ChooseProfile

        fixture.manager.signInAs(picker.profiles.first { it.userId == 1L })

        // A kill at the keypad must come back to the gate, not into Jose's library.
        assertEquals(2L, fixture.http.profileStore.vault.activeUserId)
        assertFalse(fixture.http.profileStore.vault.profiles.single { it.userId == 1L }.hasPin)
    }

    @Test
    fun `a PIN removed elsewhere is not asked for`() = runTest {
        // The mirror case: the keypad would be a dead end, since the server answers a verify
        // against a user with no PIN with 400 rather than letting anyone through.
        val fixture = fixture(TEST_SERVER, backgroundScope) { jsonResponse(authUserJson()) }
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose", hasPin = true),
            testStoredProfile(userId = 2, name = "Ana"),
        )
        fixture.manager.restore()
        val picker = fixture.manager.state.value as AppAuthState.ChooseProfile

        val result = fixture.manager.signInAs(picker.profiles.first { it.userId == 1L })

        assertEquals(SignInResult.Authenticated, result)
        assertTrue(fixture.manager.state.value is AppAuthState.Authenticated)
        // The stale badge heals on the way through.
        assertFalse(fixture.http.profileStore.vault.profiles.single { it.userId == 1L }.hasPin)
    }

    @Test
    fun `the sign-in behind a verified PIN does not ask for it again`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope) {
            jsonResponse(authUserJson(hasPin = true))
        }
        fixture.http.seedVault(testStoredProfile(hasPin = true))
        fixture.manager.restore()
        assertTrue(fixture.manager.state.value is AppAuthState.NeedsPin)

        // What PinEntryViewModel calls once the server has accepted the digits.
        val result = fixture.manager.completeSignIn()

        assertEquals(SignInResult.Authenticated, result)
        assertTrue(fixture.manager.state.value is AppAuthState.Authenticated)
    }

    @Test
    fun `a PIN set mid-session does not eject whoever is watching`() = runTest {
        var hasPin = false
        val fixture = fixture(TEST_SERVER, backgroundScope) {
            jsonResponse(authUserJson(hasPin = hasPin))
        }
        fixture.http.seedVault(testStoredProfile())
        fixture.manager.restore()
        assertTrue(fixture.manager.state.value is AppAuthState.Authenticated)

        hasPin = true
        fixture.elapsed += 10 * 60 * 1000L
        fixture.manager.revalidateActive()

        assertTrue(fixture.manager.state.value is AppAuthState.Authenticated)
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
        // The notice reaches this arm too. Before NeedsLogin carried one, a revoked *last*
        // profile lost its explanation entirely.
        assertEquals("Jose's session expired. Sign in again.", state.notice)
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
    fun `switching profile focuses the profile handing the remote over`() = runTest {
        // The last-used tile, per docs/design-system.md section 11.1.1 — which right after a
        // switch is whoever just stepped away, so picking someone else is one press either way.
        val fixture = fixture(TEST_SERVER, backgroundScope) {
            jsonResponse(authUserJson(id = 1, name = "Jose"))
        }
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose", lastUsedAtEpochMillis = 10),
            testStoredProfile(userId = 2, name = "Ana", lastUsedAtEpochMillis = 20),
            activeUserId = 2,
        )
        fixture.manager.restore()
        fixture.manager.signInAs(
            (fixture.manager.state.value as AppAuthState.ChooseProfile)
                .profiles.first { it.userId == 1L },
        )

        fixture.manager.switchProfile()

        val state = fixture.manager.state.value as AppAuthState.ChooseProfile
        assertEquals(1L, state.initialFocusUserId)
        assertEquals(setOf("Jose", "Ana"), state.profiles.map { it.name }.toSet())
    }

    @Test
    fun `cancelling a switch waiter cannot strand the session without a credential`() = runTest {
        // The credential is dropped before the gate is published. An Activity recreated in
        // between must not leave the app authenticated with nothing to authenticate with — the
        // next 401 would take the profile off this TV, which a switch must never do.
        val revalidateStarted = CompletableDeferred<Unit>()
        val releaseRevalidate = CompletableDeferred<Unit>()
        var holdTheMutex = false
        val fixture = fixture(
            storedServerUrl = TEST_SERVER,
            scope = backgroundScope,
            engineDispatcher = Dispatchers.Unconfined,
        ) {
            if (holdTheMutex) {
                revalidateStarted.complete(Unit)
                releaseRevalidate.await()
            }
            jsonResponse(authUserJson())
        }
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose"),
            testStoredProfile(userId = 2, name = "Ana"),
        )
        fixture.manager.restore()
        fixture.manager.signInAs(
            (fixture.manager.state.value as AppAuthState.ChooseProfile)
                .profiles.first { it.userId == 1L },
        )

        // Revalidation holds the transition mutex, so the switch behind it is genuinely parked
        // at the moment its caller goes away.
        holdTheMutex = true
        fixture.elapsed += 10 * 60 * 1000L
        val revalidate = launch { fixture.manager.revalidateActive() }
        revalidateStarted.await()
        val waiter = launch { fixture.manager.switchProfile() }
        yield()
        waiter.cancelAndJoin()

        releaseRevalidate.complete(Unit)
        revalidate.join()
        yield()

        val state = fixture.manager.state.value as AppAuthState.ChooseProfile
        assertEquals(setOf("Jose", "Ana"), state.profiles.map { it.name }.toSet())
        assertNull(fixture.http.credentials.current())
    }

    /**
     * Signs in as Jose with Ana also stored, so every sign-out test below is really asking
     * "did this disturb the other profile?" — see docs/design-system.md section 11.2.
     */
    private suspend fun signedInWithASecondProfile(
        scope: CoroutineScope,
        engineDispatcher: CoroutineDispatcher? = null,
        imageCache: ImageCache = ImageCache.None,
        logout: MockRequestHandler,
    ): Fixture {
        val fixture = fixture(
            storedServerUrl = TEST_SERVER,
            scope = scope,
            handler = { request ->
                if (request.url.encodedPath.endsWith("/logout")) {
                    logout(request)
                } else {
                    jsonResponse(authUserJson())
                }
            },
            engineDispatcher = engineDispatcher,
            imageCache = imageCache,
        )
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose"),
            testStoredProfile(userId = 2, name = "Ana"),
        )
        fixture.manager.restore()
        fixture.manager.signInAs(
            (fixture.manager.state.value as AppAuthState.ChooseProfile).profiles.first { it.userId == 1L },
        )
        return fixture
    }

    private fun MockRequestHandleScope.logoutAccepted() =
        jsonResponse("""{"error":false,"message":"bye"}""")

    @Test
    fun `signing out removes only the active profile`() = runTest {
        val fixture = signedInWithASecondProfile(backgroundScope) { logoutAccepted() }

        fixture.manager.logout()

        val state = fixture.manager.state.value as AppAuthState.ChooseProfile
        assertEquals(listOf("Ana"), state.profiles.map { it.name })
        // A delivered revoke says nothing: reaching the picker is the announcement.
        assertNull(state.notice)
    }

    @Test
    fun `signing out leaves the other profile's token usable`() = runTest {
        val bearers = mutableListOf<String?>()
        // Answers as whoever the bearer belongs to, so a token mix-up shows up as a wrong user
        // rather than being papered over by a fixed response.
        val fixture = fixture(TEST_SERVER, backgroundScope) { request ->
            val bearer = request.headers[HttpHeaders.Authorization]
            bearers += bearer
            when {
                request.url.encodedPath.endsWith("/logout") ->
                    jsonResponse("""{"error":false,"message":"bye"}""")
                bearer == "Bearer igd_ana" -> jsonResponse(authUserJson(id = 2, name = "Ana"))
                else -> jsonResponse(authUserJson(id = 1, name = "Jose"))
            }
        }
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose", token = "igd_jose"),
            testStoredProfile(userId = 2, name = "Ana", token = "igd_ana"),
        )
        fixture.manager.restore()
        val picker = fixture.manager.state.value as AppAuthState.ChooseProfile
        fixture.manager.signInAs(picker.profiles.first { it.userId == 1L })
        fixture.manager.logout()

        // Ana was never signed out, so this must not need a re-pair.
        val result = fixture.manager.signInAs(
            (fixture.manager.state.value as AppAuthState.ChooseProfile).profiles.single(),
        )

        assertEquals(SignInResult.Authenticated, result)
        assertEquals("Ana", (fixture.manager.state.value as AppAuthState.Authenticated).user.name)
        assertEquals("Bearer igd_ana", bearers.last())
        // And her token was never the one the revoke carried.
        assertFalse(bearers.dropLast(1).contains("Bearer igd_ana"))
    }

    @Test
    fun `signing out clears the in-memory credential`() = runTest {
        val fixture = signedInWithASecondProfile(backgroundScope) { logoutAccepted() }

        fixture.manager.logout()

        // A revoked token left in the provider would be attached to the next profile's first
        // request, whose 401 would then sign that innocent profile out.
        assertNull(fixture.http.credentials.current())
        assertNull(fixture.http.profiles.activeProfileId)
    }

    @Test
    fun `signing out removes the local credential before a delayed revoke finishes`() = runTest {
        val revokeStarted = CompletableDeferred<String?>()
        val releaseRevoke = CompletableDeferred<Unit>()
        val fixture = signedInWithASecondProfile(
            scope = backgroundScope,
            engineDispatcher = Dispatchers.Unconfined,
        ) { request ->
            revokeStarted.complete(request.headers[HttpHeaders.Authorization])
            releaseRevoke.await()
            logoutAccepted()
        }

        val waiter = launch { fixture.manager.logout() }
        assertEquals("Bearer igd_1", revokeStarted.await())

        assertEquals(listOf("Ana"), fixture.http.profileStore.vault.profiles.map { it.name })
        assertNull(fixture.http.credentials.current())
        assertNull(fixture.http.profiles.activeProfileId)
        // The request is still pending, so the modal may keep reporting progress even though the
        // security boundary has already completed locally.
        assertTrue(fixture.manager.state.value is AppAuthState.Authenticated)

        releaseRevoke.complete(Unit)
        waiter.join()
        assertTrue(fixture.manager.state.value is AppAuthState.ChooseProfile)
    }

    @Test
    fun `signing out clears the image cache before a delayed revoke finishes`() = runTest {
        var cleared = 0
        val revokeStarted = CompletableDeferred<Unit>()
        val releaseRevoke = CompletableDeferred<Unit>()
        val fixture = signedInWithASecondProfile(
            scope = backgroundScope,
            engineDispatcher = Dispatchers.Unconfined,
            imageCache = object : ImageCache {
                override suspend fun clear() {
                    cleared += 1
                }
            },
        ) {
            revokeStarted.complete(Unit)
            releaseRevoke.await()
            logoutAccepted()
        }

        val waiter = launch { fixture.manager.logout() }
        revokeStarted.await()

        // Same NonCancellable block as the vault write: the signed-out profile's avatar is gone
        // from disk before the network gets a chance to take the full timeout.
        assertEquals(1, cleared)

        releaseRevoke.complete(Unit)
        waiter.join()
        assertEquals(1, cleared)
    }

    @Test
    fun `switching profiles keeps the image cache`() = runTest {
        var cleared = 0
        val fixture = signedInWithASecondProfile(
            scope = backgroundScope,
            imageCache = object : ImageCache {
                override suspend fun clear() {
                    cleared += 1
                }
            },
        ) { error("no revoke expected") }

        fixture.manager.switchProfile()

        // Handing the TV over keeps every profile paired, so their avatars stay worth keeping.
        assertEquals(0, cleared)
    }

    @Test
    fun `cancelling a logout waiter cannot retain the credential or cancel cleanup`() = runTest {
        val revokeStarted = CompletableDeferred<Unit>()
        val releaseRevoke = CompletableDeferred<Unit>()
        val fixture = signedInWithASecondProfile(
            scope = backgroundScope,
            engineDispatcher = Dispatchers.Unconfined,
        ) {
            revokeStarted.complete(Unit)
            releaseRevoke.await()
            logoutAccepted()
        }

        val waiter = launch { fixture.manager.logout() }
        revokeStarted.await()
        waiter.cancelAndJoin()

        assertEquals(listOf("Ana"), fixture.http.profileStore.vault.profiles.map { it.name })
        assertNull(fixture.http.credentials.current())

        releaseRevoke.complete(Unit)
        yield()
        val state = fixture.manager.state.value as AppAuthState.ChooseProfile
        assertEquals(listOf("Ana"), state.profiles.map { it.name })
    }

    @Test
    fun `a queued switch and sign-in cannot redirect sign-out to the next profile`() = runTest {
        val revokeStarted = CompletableDeferred<String?>()
        val releaseRevoke = CompletableDeferred<Unit>()
        val fixture = fixture(
            storedServerUrl = TEST_SERVER,
            scope = backgroundScope,
            engineDispatcher = Dispatchers.Unconfined,
            handler = { request ->
                val bearer = request.headers[HttpHeaders.Authorization]
                when {
                    request.url.encodedPath.endsWith("/logout") -> {
                        revokeStarted.complete(bearer)
                        releaseRevoke.await()
                        logoutAccepted()
                    }
                    bearer == "Bearer igd_2" -> jsonResponse(authUserJson(id = 2, name = "Ana"))
                    else -> jsonResponse(authUserJson(id = 1, name = "Jose"))
                }
            },
        )
        fixture.http.seedVault(
            testStoredProfile(userId = 1, name = "Jose", token = "igd_1"),
            testStoredProfile(userId = 2, name = "Ana", token = "igd_2"),
        )
        fixture.manager.restore()
        val picker = fixture.manager.state.value as AppAuthState.ChooseProfile
        val jose = picker.profiles.first { it.userId == 1L }
        val ana = picker.profiles.first { it.userId == 2L }
        fixture.manager.signInAs(jose)

        val logout = launch { fixture.manager.logout() }
        assertEquals("Bearer igd_1", revokeStarted.await())
        val switch = launch { fixture.manager.switchProfile() }
        val signIn = async { fixture.manager.signInAs(ana) }
        yield()

        assertFalse(switch.isCompleted)
        assertFalse(signIn.isCompleted)
        assertEquals(listOf("Ana"), fixture.http.profileStore.vault.profiles.map { it.name })

        releaseRevoke.complete(Unit)
        logout.join()
        switch.join()
        assertEquals(SignInResult.Authenticated, signIn.await())
        val state = fixture.manager.state.value as AppAuthState.Authenticated
        assertEquals(2L, state.user.id)
        assertEquals(listOf("Ana"), fixture.http.profileStore.vault.profiles.map { it.name })
        assertEquals("igd_2", fixture.http.credentials.current()?.token)
    }

    @Test
    fun `a missing initiating profile skips revoke but still exits authenticated state`() = runTest {
        val fixture = signedInWithASecondProfile(backgroundScope) { logoutAccepted() }
        fixture.http.profileStore.vault = fixture.http.profileStore.vault.copy(
            profiles = fixture.http.profileStore.vault.profiles.filterNot { it.userId == 1L },
        )
        val beforeLogout = fixture.requestCount

        fixture.manager.logout()

        assertEquals(beforeLogout, fixture.requestCount)
        assertNull(fixture.http.credentials.current())
        val state = fixture.manager.state.value as AppAuthState.ChooseProfile
        assertEquals(listOf("Ana"), state.profiles.map { it.name })
        assertNull(state.notice)
    }

    @Test
    fun `a late rejection of the signed-out profile does not end the next session`() = runTest {
        val fixture = signedInWithASecondProfile(backgroundScope) { logoutAccepted() }
        fixture.manager.logout()
        fixture.manager.signInAs(
            (fixture.manager.state.value as AppAuthState.ChooseProfile).profiles.single(),
        )

        // A slow request belonging to Jose lands after Ana is watching.
        fixture.http.authEvents.signalUnauthorized(1L)
        yield()

        assertTrue(fixture.manager.state.value is AppAuthState.Authenticated)
        assertEquals(listOf("Ana"), fixture.http.profileStore.vault.profiles.map { it.name })
    }

    @Test
    fun `a sign-out the server says is already gone is still a clean sign-out`() = runTest {
        val fixture = signedInWithASecondProfile(backgroundScope) {
            jsonResponse("""{"error":true,"message":"gone"}""", HttpStatusCode.Unauthorized)
        }

        fixture.manager.logout()

        val state = fixture.manager.state.value as AppAuthState.ChooseProfile
        assertEquals(listOf("Ana"), state.profiles.map { it.name })
        // A 401 is what this request wanted. Not "Jose's session expired. Sign in again."
        assertNull(state.notice)
    }

    @Test
    fun `a sign-out the server never hears still drops the profile and warns`() = runTest {
        val fixture = signedInWithASecondProfile(backgroundScope) { throw IOException("offline") }

        fixture.manager.logout()

        val state = fixture.manager.state.value as AppAuthState.ChooseProfile
        // Local state is clean either way: a credential must never be stranded on a shared TV.
        assertEquals(listOf("Ana"), state.profiles.map { it.name })
        assertEquals(SessionManager.UNDELIVERED_REVOKE_NOTICE, state.notice)
    }

    @Test
    fun `signing out the last profile warns on the sign-in screen`() = runTest {
        val fixture = fixture(TEST_SERVER, backgroundScope) { request ->
            if (request.url.encodedPath.endsWith("/logout")) {
                throw IOException("offline")
            } else {
                jsonResponse(authUserJson())
            }
        }
        fixture.http.seedVault(testStoredProfile())
        fixture.manager.restore()

        fixture.manager.logout()

        // No profile left to pick, so the notice has to reach this arm too.
        val state = fixture.manager.state.value as AppAuthState.NeedsLogin
        assertEquals(SessionManager.UNDELIVERED_REVOKE_NOTICE, state.notice)
    }

    @Test
    fun `a rejected sign-out reports no lost session`() = runTest {
        val fixture = signedInWithASecondProfile(backgroundScope) {
            jsonResponse("""{"error":true,"message":"gone"}""", HttpStatusCode.Unauthorized)
        }
        val signals = mutableListOf<Long?>()
        backgroundScope.launch { fixture.http.authEvents.unauthorized.collect { signals += it } }
        yield()

        fixture.manager.logout()
        yield()

        // Not merely "the wrong message was suppressed": the event must never be emitted, because
        // the bus keeps one slot and would drop a genuine 401 to make room for this one.
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `signing out after the session already ended does nothing`() = runTest {
        val fixture = signedInWithASecondProfile(backgroundScope) { logoutAccepted() }
        fixture.manager.logout()
        val afterFirst = fixture.requestCount
        val state = fixture.manager.state.value

        fixture.manager.logout()

        assertEquals(afterFirst, fixture.requestCount)
        assertEquals(state, fixture.manager.state.value)
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
