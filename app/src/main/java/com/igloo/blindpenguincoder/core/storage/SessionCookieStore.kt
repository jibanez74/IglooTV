package com.igloo.blindpenguincoder.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
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

class DataStoreSessionCookieStore(private val dataStore: DataStore<Preferences>) : SessionCookieStore {

    override suspend fun read(): StoredCookie? {
        val encoded = dataStore.data.first()[SESSION_COOKIE] ?: return null
        return try {
            Json.decodeFromString<StoredCookie>(encoded)
        } catch (_: SerializationException) {
            null
        }
    }

    override suspend fun write(cookie: StoredCookie) {
        val encoded = Json.encodeToString(cookie)
        dataStore.edit { it[SESSION_COOKIE] = encoded }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(SESSION_COOKIE) }
    }

    private companion object {
        val SESSION_COOKIE = stringPreferencesKey("session_cookie")
    }
}
