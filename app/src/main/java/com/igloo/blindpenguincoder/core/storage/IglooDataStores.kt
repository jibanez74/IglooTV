package com.igloo.blindpenguincoder.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("igloo_settings")
val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore("igloo_session")
