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

class DataStoreDeviceTokenStoreTest {

    private val key = stringPreferencesKey("device_token")

    private val token = "igd_super-secret-token"

    private fun store(
        dataStore: InMemoryPreferencesDataStore,
        cipher: FakeSecretCipher = FakeSecretCipher(),
    ) = DataStoreDeviceTokenStore(dataStore, cipher, Dispatchers.Unconfined)

    @Test
    fun `a written token round trips`() = runTest {
        val data = InMemoryPreferencesDataStore()
        val store = store(data)

        store.write(token)

        assertEquals(token, store.read())
    }

    @Test
    fun `the persisted value is not the plaintext token`() = runTest {
        val data = InMemoryPreferencesDataStore()
        store(data).write(token)

        val persisted = data.data.first()[key]

        assertNotNull(persisted)
        assertFalse(persisted!!.contains(token))
    }

    @Test
    fun `an undecryptable value is erased and reads as no token`() = runTest {
        val data = InMemoryPreferencesDataStore()
        data.edit { it[key] = "blob-from-a-replaced-key" }
        val store = store(data, FakeSecretCipher(failDecrypt = true))

        assertNull(store.read())
        assertNull(data.data.first()[key])
    }

    @Test
    fun `nothing is stored when encryption fails`() = runTest {
        val data = InMemoryPreferencesDataStore()
        val store = store(data, FakeSecretCipher(failEncrypt = true))

        store.write(token)

        assertNull(data.data.first()[key])
        assertNull(store.read())
    }

    @Test
    fun `clear removes the stored value`() = runTest {
        val data = InMemoryPreferencesDataStore()
        val store = store(data)
        store.write(token)

        store.clear()

        assertNull(data.data.first()[key])
        assertNull(store.read())
    }
}
