package com.igloo.blindpenguincoder.core.storage

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.igloo.blindpenguincoder.core.design.UiScale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UiPreferencesStoreTest {

    private val dataStore = InMemoryPreferencesDataStore()
    private val store = UiPreferencesStore(dataStore)

    @Test
    fun `defaults to standard when nothing is stored`() = runTest {
        assertEquals(UiScale.Standard, store.uiScale.first())
    }

    @Test
    fun `every scale round trips`() = runTest {
        UiScale.entries.forEach { scale ->
            store.saveUiScale(scale)
            assertEquals(scale, store.uiScale.first())
        }
    }

    @Test
    fun `a corrupt stored value falls back to standard instead of throwing`() = runTest {
        dataStore.edit { it[stringPreferencesKey("ui_scale")] = "Gigantic" }
        assertEquals(UiScale.Standard, store.uiScale.first())
    }
}
