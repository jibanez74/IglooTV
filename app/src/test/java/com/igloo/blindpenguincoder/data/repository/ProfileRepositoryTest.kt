package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.network.ActiveCredential
import com.igloo.blindpenguincoder.core.network.BearerTokenProvider
import com.igloo.blindpenguincoder.core.storage.FakeProfileStore
import com.igloo.blindpenguincoder.core.storage.ProfileVault
import com.igloo.blindpenguincoder.data.model.AuthUser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRepositoryTest {

    private val store = FakeProfileStore()
    private val credentials = BearerTokenProvider()
    private var now = 100L
    private val repository = ProfileRepository(store, credentials, clock = { now })

    private fun user(
        id: Long = 1,
        name: String = "Jose",
        hasPin: Boolean = false,
        avatar: String? = null,
    ) = AuthUser(
        id = id,
        name = name,
        isAdmin = false,
        avatar = avatar,
        hasPin = hasPin,
    )

    @Test
    fun `committing a pending token creates the profile and activates it`() = runTest {
        repository.setPending("igd_new")

        val result = repository.commitSignIn(user(id = 7, hasPin = true, avatar = "https://x/a.png"))

        assertNull(result.replacedToken)
        val stored = store.vault.profiles.single()
        assertEquals(7L, stored.userId)
        assertEquals("igd_new", stored.token)
        assertEquals("https://x/a.png", stored.avatarUrl)
        assertTrue(stored.hasPin)
        assertEquals(100L, stored.lastUsedAtEpochMillis)
        assertEquals(7L, store.vault.activeUserId)
        assertNull(store.vault.pendingToken)
        assertEquals(ActiveCredential(7, "igd_new"), credentials.current())
        assertEquals(7L, repository.activeProfileId)
    }

    /**
     * The vault keeps what the server sent. Resolution happens at render against the origin in
     * use then, so a profile survives the TV being pointed at a different address.
     */
    @Test
    fun `an uploaded avatar path is stored unresolved`() = runTest {
        repository.setPending("igd_new")

        repository.commitSignIn(user(id = 7, avatar = "/api/static/avatars/7.jpg"))

        assertEquals("/api/static/avatars/7.jpg", store.vault.profiles.single().avatarUrl)
    }

    @Test
    fun `re-pairing the same user replaces the token and reports the old one`() = runTest {
        repository.setPending("igd_first")
        repository.commitSignIn(user(id = 7))
        repository.setPending("igd_second")

        val result = repository.commitSignIn(user(id = 7, name = "Jose Renamed"))

        assertEquals("igd_first", result.replacedToken)
        val stored = store.vault.profiles.single()
        assertEquals("igd_second", stored.token)
        assertEquals("Jose Renamed", stored.name)
    }

    @Test
    fun `committing without a pending token refreshes the existing profile`() = runTest {
        repository.setPending("igd_first")
        repository.commitSignIn(user(id = 7))
        now = 500

        val result = repository.commitSignIn(user(id = 7, name = "Jose", hasPin = true))

        assertNull(result.replacedToken)
        val stored = store.vault.profiles.single()
        assertEquals("igd_first", stored.token)
        assertTrue(stored.hasPin)
        assertEquals(500L, stored.lastUsedAtEpochMillis)
    }

    @Test
    fun `profiles are listed most recently used first and carry no token`() = runTest {
        repository.setPending("igd_one")
        repository.commitSignIn(user(id = 1, name = "Jose"))
        now = 300
        repository.setPending("igd_two")
        repository.commitSignIn(user(id = 2, name = "Ana"))

        val state = repository.load()

        assertEquals(listOf("Ana", "Jose"), state.profiles.map { it.name })
        assertEquals(2L, state.lastActiveUserId)
        assertFalse(state.hasPendingToken)
    }

    @Test
    fun `activating an unknown profile changes nothing`() = runTest {
        repository.setPending("igd_one")
        repository.commitSignIn(user(id = 1))

        val activated = repository.activate(99)

        assertFalse(activated)
        assertEquals(ActiveCredential(1, "igd_one"), credentials.current())
    }

    @Test
    fun `activating a pending token reports whether there was one`() = runTest {
        assertFalse(repository.activatePending())

        repository.setPending("igd_half_paired")
        repository.deactivate()

        assertTrue(repository.activatePending())
        assertEquals(ActiveCredential(null, "igd_half_paired"), credentials.current())
    }

    @Test
    fun `clearing a pending token leaves committed profiles alone`() = runTest {
        repository.setPending("igd_one")
        repository.commitSignIn(user(id = 1))
        repository.setPending("igd_abandoned")

        repository.clearPending()

        assertNull(store.vault.pendingToken)
        assertEquals(1, store.vault.profiles.size)
        assertNull(credentials.current())
    }

    @Test
    fun `removing a profile keeps the others`() = runTest {
        repository.setPending("igd_one")
        repository.commitSignIn(user(id = 1, name = "Jose"))
        repository.setPending("igd_two")
        repository.commitSignIn(user(id = 2, name = "Ana"))

        repository.remove(2)

        assertEquals(listOf("Jose"), store.vault.profiles.map { it.name })
        assertNull(store.vault.activeUserId)
        assertNull(credentials.current())
        assertNull(repository.activeProfileId)
    }

    @Test
    fun `removing an inactive profile leaves the active credential in place`() = runTest {
        repository.setPending("igd_one")
        repository.commitSignIn(user(id = 1, name = "Jose"))
        repository.setPending("igd_two")
        repository.commitSignIn(user(id = 2, name = "Ana"))

        repository.remove(1)

        assertEquals(ActiveCredential(2, "igd_two"), credentials.current())
        assertEquals(2L, store.vault.activeUserId)
    }

    @Test
    fun `sign-out removal returns the requested profile token and preserves the active profile`() =
        runTest {
            repository.setPending("igd_one")
            repository.commitSignIn(user(id = 1, name = "Jose"))
            repository.setPending("igd_two")
            repository.commitSignIn(user(id = 2, name = "Ana"))

            val removed = repository.removeForSignOut(1)

            assertEquals(ActiveCredential(1, "igd_one"), removed)
            assertEquals(listOf("Ana"), store.vault.profiles.map { it.name })
            assertEquals(2L, store.vault.activeUserId)
            assertEquals(ActiveCredential(2, "igd_two"), credentials.current())
            assertEquals(2L, repository.activeProfileId)
        }

    @Test
    fun `sign-out removal clears only the requested active credential`() = runTest {
        repository.setPending("igd_one")
        repository.commitSignIn(user(id = 1, name = "Jose"))
        repository.setPending("igd_two")
        repository.commitSignIn(user(id = 2, name = "Ana"))

        val removed = repository.removeForSignOut(2)

        assertEquals(ActiveCredential(2, "igd_two"), removed)
        assertEquals(listOf("Jose"), store.vault.profiles.map { it.name })
        assertNull(store.vault.activeUserId)
        assertNull(credentials.current())
        assertNull(repository.activeProfileId)
    }

    @Test
    fun `deactivating keeps the profile paired`() = runTest {
        repository.setPending("igd_one")
        repository.commitSignIn(user(id = 1))

        repository.deactivate()

        assertEquals(1, store.vault.profiles.size)
        assertNull(credentials.current())
    }

    @Test
    fun `clearing everything drops profiles and any half-finished pairing`() = runTest {
        repository.setPending("igd_one")
        repository.commitSignIn(user(id = 1))
        repository.setPending("igd_abandoned")

        repository.clearAll()

        assertEquals(ProfileVault(), store.vault)
        assertNull(credentials.current())
    }
}
