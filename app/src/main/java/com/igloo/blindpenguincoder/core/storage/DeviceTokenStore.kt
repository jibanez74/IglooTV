package com.igloo.blindpenguincoder.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Holds the long-lived device bearer token. The value is a secret: never log or display it. */
interface DeviceTokenStore {
    suspend fun read(): String?
    suspend fun write(token: String)
    suspend fun clear()
}

/**
 * Stores the token encrypted via [cipher]; the Keystore calls block, so every
 * method hops to [dispatcher]. Anything unreadable — a blob written under a
 * replaced key — is erased and read as "no token", which sends the user back
 * to pairing.
 */
class DataStoreDeviceTokenStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DeviceTokenStore {

    override suspend fun read(): String? = withContext(dispatcher) {
        var blob: String? = null
        dataStore.edit { preferences ->
            blob = preferences[DEVICE_TOKEN]
            preferences.remove(LEGACY_SESSION_COOKIE)
        }
        val encryptedToken = blob ?: return@withContext null
        val token = cipher.decrypt(encryptedToken)
        if (token == null) {
            clear()
            return@withContext null
        }
        token
    }

    override suspend fun write(token: String) {
        withContext(dispatcher) {
            // A device that cannot encrypt keeps no token; never fall back to plaintext.
            val blob = cipher.encrypt(token)
            dataStore.edit { preferences ->
                preferences.remove(LEGACY_SESSION_COOKIE)
                if (blob == null) {
                    preferences.remove(DEVICE_TOKEN)
                } else {
                    preferences[DEVICE_TOKEN] = blob
                }
            }
        }
    }

    override suspend fun clear() {
        withContext(dispatcher) {
            dataStore.edit { preferences ->
                preferences.remove(DEVICE_TOKEN)
                preferences.remove(LEGACY_SESSION_COOKIE)
            }
        }
    }

    private companion object {
        val DEVICE_TOKEN = stringPreferencesKey("device_token")
        val LEGACY_SESSION_COOKIE = stringPreferencesKey("session_cookie")
    }
}
