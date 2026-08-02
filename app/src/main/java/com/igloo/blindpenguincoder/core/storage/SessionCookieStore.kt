package com.igloo.blindpenguincoder.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The backend session cookie. The value is a secret: never log or display it. */
@Serializable
data class StoredCookie(
    val name: String,
    val value: String,
    val host: String,
    val path: String,
    val expiresEpochMillis: Long?,
    val secure: Boolean,
    val httpOnly: Boolean,
) {
    override fun toString(): String = "StoredCookie(name=$name, host=$host, value=***)"
}

interface SessionCookieStore {
    suspend fun read(): StoredCookie?
    suspend fun write(cookie: StoredCookie)
    suspend fun clear()
}

/**
 * Stores the cookie encrypted via [cipher]; the Keystore calls block, so every
 * method hops to [dispatcher]. Anything unreadable — a blob written under a
 * replaced key, or the plaintext left by an earlier build — is erased and read
 * as "no session", which sends the user back to sign in.
 */
class DataStoreSessionCookieStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: SecretCipher,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SessionCookieStore {

    override suspend fun read(): StoredCookie? = withContext(dispatcher) {
        val blob = dataStore.data.first()[SESSION_COOKIE] ?: return@withContext null
        val json = cipher.decrypt(blob)
        if (json == null) {
            clear()
            return@withContext null
        }
        try {
            Json.decodeFromString<StoredCookie>(json)
        } catch (_: SerializationException) {
            clear()
            null
        }
    }

    override suspend fun write(cookie: StoredCookie) {
        withContext(dispatcher) {
            // A device that cannot encrypt keeps no session; never fall back to plaintext.
            val blob = cipher.encrypt(Json.encodeToString(cookie))
            if (blob == null) {
                dataStore.edit { it.remove(SESSION_COOKIE) }
            } else {
                dataStore.edit { it[SESSION_COOKIE] = blob }
            }
        }
    }

    override suspend fun clear() {
        withContext(dispatcher) { dataStore.edit { it.remove(SESSION_COOKIE) } }
    }

    private companion object {
        val SESSION_COOKIE = stringPreferencesKey("session_cookie")
    }
}
