package com.igloo.blindpenguincoder.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ServerSettingsStore(private val dataStore: DataStore<Preferences>) {

    val serverUrl: Flow<String?> = dataStore.data.map { it[SERVER_URL] }

    suspend fun save(url: String) {
        dataStore.edit { it[SERVER_URL] = url }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(SERVER_URL) }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
    }
}
