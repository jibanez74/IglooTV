package com.igloo.blindpenguincoder.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.igloo.blindpenguincoder.core.design.UiScale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** User display preferences. Unrecognized stored values fall back to the default. */
class UiPreferencesStore(private val dataStore: DataStore<Preferences>) {

    val uiScale: Flow<UiScale> = dataStore.data.map { UiScale.fromName(it[UI_SCALE]) }

    suspend fun saveUiScale(scale: UiScale) {
        dataStore.edit { it[UI_SCALE] = scale.name }
    }

    private companion object {
        val UI_SCALE = stringPreferencesKey("ui_scale")
    }
}
