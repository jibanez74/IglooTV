package com.igloo.blindpenguincoder.core.storage

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataStoreProfileStoreTest {

    private val profilesKey = stringPreferencesKey("profiles")
    private val legacyTokenKey = stringPreferencesKey("device_token")
    private val legacyCookieKey = stringPreferencesKey("session_cookie")

    private fun store(
        dataStore: InMemoryPreferencesDataStore,
        cipher: SecretCipher = FakeSecretCipher(),
    ) = DataStoreProfileStore(dataStore, cipher, Dispatchers.Unconfined)

    private val vault = ProfileVault(
        activeUserId = 2,
        pendingToken = "igd_pending",
        profiles = listOf(
            StoredProfile(1, "igd_one", "Jose", avatarUrl = null, hasPin = false, lastUsedAtEpochMillis = 10),
            StoredProfile(2, "igd_two", "Ana", avatarUrl = "https://x/a.png", hasPin = true, lastUsedAtEpochMillis = 20),
        ),
    )

    @Test
    fun `several profiles survive a round trip`() = runTest {
        val data = InMemoryPreferencesDataStore()

        store(data).update { vault }

        assertEquals(vault, store(data).read())
    }

    @Test
    fun `the persisted blob holds neither a token nor a profile name`() = runTest {
        val data = InMemoryPreferencesDataStore()

        store(data).update { vault }

        val blob = data.data.first()[profilesKey]
        assertNotNull(blob)
        assertFalse(blob!!.contains("igd_one"))
        assertFalse(blob.contains("igd_pending"))
        assertFalse(blob.contains("Jose"))
    }

    @Test
    fun `an undecryptable blob is erased and read as empty`() = runTest {
        val data = InMemoryPreferencesDataStore()
        store(data).update { vault }
        val cipher = FakeSecretCipher(failDecrypt = true)

        assertEquals(ProfileVault(), store(data, cipher).read())
        assertNull(data.data.first()[profilesKey])
    }

    @Test
    fun `a blob that no longer parses is erased and read as empty`() = runTest {
        val data = InMemoryPreferencesDataStore()
        val cipher = FakeSecretCipher()
        data.edit { it[profilesKey] = cipher.encrypt("not json at all")!! }

        assertEquals(ProfileVault(), store(data, cipher).read())
        assertNull(data.data.first()[profilesKey])
    }

    @Test
    fun `a device that cannot encrypt stores nothing`() = runTest {
        val data = InMemoryPreferencesDataStore()

        val result = store(data, FakeSecretCipher(failEncrypt = true)).update { vault }

        assertEquals(ProfileVault(), result)
        assertNull(data.data.first()[profilesKey])
    }

    @Test
    fun `an emptied vault removes the key rather than storing an empty one`() = runTest {
        val data = InMemoryPreferencesDataStore()
        store(data).update { vault }

        store(data).update { ProfileVault() }

        assertNull(data.data.first()[profilesKey])
    }

    @Test
    fun `single-token era keys are purged`() = runTest {
        val data = InMemoryPreferencesDataStore()
        data.edit {
            it[legacyTokenKey] = "igd_from_the_old_scheme"
            it[legacyCookieKey] = "session=abc"
        }

        store(data).read()

        val stored = data.data.first()
        assertNull(stored[legacyTokenKey])
        assertNull(stored[legacyCookieKey])
    }

    @Test
    fun `update applies its transform exactly once`() = runTest {
        val data = InMemoryPreferencesDataStore()
        var calls = 0

        store(data).update {
            calls += 1
            vault
        }

        assertEquals(1, calls)
    }

    @Test
    fun `update sees what a previous update wrote`() = runTest {
        val data = InMemoryPreferencesDataStore()
        val store = store(data)
        store.update { vault }

        val result = store.update { it.copy(pendingToken = null) }

        assertNull(result.pendingToken)
        assertEquals(2, result.profiles.size)
        assertTrue(store.read().pendingToken == null)
    }
}
