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
import org.junit.Test

class DataStoreSessionCookieStoreTest {

    private val key = stringPreferencesKey("session_cookie")

    private val cookie = StoredCookie(
        name = "session",
        value = "super-secret-token",
        host = "igloo.test",
        path = "/",
        expiresEpochMillis = null,
        secure = false,
        httpOnly = true,
    )

    private fun store(
        dataStore: InMemoryPreferencesDataStore,
        cipher: FakeSecretCipher = FakeSecretCipher(),
    ) = DataStoreSessionCookieStore(dataStore, cipher, Dispatchers.Unconfined)

    @Test
    fun `a written cookie round trips`() = runTest {
        val data = InMemoryPreferencesDataStore()
        val store = store(data)

        store.write(cookie)

        assertEquals(cookie, store.read())
    }

    @Test
    fun `the persisted value is not the plaintext cookie`() = runTest {
        val data = InMemoryPreferencesDataStore()
        store(data).write(cookie)

        val persisted = data.data.first()[key]

        assertNotNull(persisted)
        assertFalse(persisted!!.contains("super-secret-token"))
    }

    @Test
    fun `an undecryptable value is erased and reads as no session`() = runTest {
        val data = InMemoryPreferencesDataStore()
        // What the previous build left behind: the cookie JSON in the clear.
        data.edit { it[key] = """{"name":"session","value":"super-secret-token"}""" }
        val store = store(data, FakeSecretCipher(failDecrypt = true))

        assertNull(store.read())
        assertNull(data.data.first()[key])
    }

    @Test
    fun `a value that decrypts to malformed json is erased`() = runTest {
        val data = InMemoryPreferencesDataStore()
        val cipher = FakeSecretCipher()
        data.edit { it[key] = cipher.encrypt("not json")!! }
        val store = store(data, cipher)

        assertNull(store.read())
        assertNull(data.data.first()[key])
    }

    @Test
    fun `nothing is stored when encryption fails`() = runTest {
        val data = InMemoryPreferencesDataStore()
        val store = store(data, FakeSecretCipher(failEncrypt = true))

        store.write(cookie)

        assertNull(data.data.first()[key])
        assertNull(store.read())
    }

    @Test
    fun `clear removes the stored value`() = runTest {
        val data = InMemoryPreferencesDataStore()
        val store = store(data)
        store.write(cookie)

        store.clear()

        assertNull(data.data.first()[key])
        assertNull(store.read())
    }
}
