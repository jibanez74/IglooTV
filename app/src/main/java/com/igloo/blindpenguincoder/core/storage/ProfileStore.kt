package com.igloo.blindpenguincoder.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.igloo.blindpenguincoder.core.network.IglooJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString

/** Persists the [ProfileVault]. Everything it holds is secret: never log or display it. */
interface ProfileStore {
    suspend fun read(): ProfileVault

    /** Applies [transform] to the stored vault and returns the result. */
    suspend fun update(transform: (ProfileVault) -> ProfileVault): ProfileVault
}

/**
 * Stores the vault encrypted via [cipher]; the Keystore calls block, so every method
 * hops to [dispatcher]. Anything unreadable — a blob written under a replaced key, or
 * one that no longer parses — is erased and read as an empty vault, which sends the
 * user back to pairing.
 *
 * [update] is the only write path so decrypt, modify, and encrypt all happen inside a
 * single edit, keeping read-modify-write atomic.
 */
class DataStoreProfileStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProfileStore {

    override suspend fun read(): ProfileVault = update { it }

    override suspend fun update(transform: (ProfileVault) -> ProfileVault): ProfileVault =
        withContext(dispatcher) {
            var result = ProfileVault()
            dataStore.edit { preferences ->
                val blob = preferences[PROFILES]
                val stored = blob?.let(::decodeVault)
                result = transform(stored ?: ProfileVault())
                // An unreadable blob is rewritten even when nothing changed, so corrupt
                // ciphertext never survives a read.
                if (stored != null && result == stored) return@edit

                if (result == EMPTY) {
                    preferences.remove(PROFILES)
                    return@edit
                }
                // A device that cannot encrypt keeps nothing; never fall back to plaintext.
                val encrypted = cipher.encrypt(IglooJson.encodeToString(result))
                if (encrypted == null) {
                    preferences.remove(PROFILES)
                    result = EMPTY
                } else {
                    preferences[PROFILES] = encrypted
                }
            }
            result
        }

    private fun decodeVault(blob: String): ProfileVault? {
        val json = cipher.decrypt(blob) ?: return null
        return try {
            IglooJson.decodeFromString<ProfileVault>(json)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        val EMPTY = ProfileVault()
        val PROFILES = stringPreferencesKey("profiles")
    }
}
