package com.igloo.blindpenguincoder.feature.search

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.igloo.blindpenguincoder.feature.music.TrackFocusMemory
import com.igloo.blindpenguincoder.feature.shared.TrackRowColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The remembered cards belong to the query they were focused under. */
class SearchPaneMemoryTest {

    private fun memory() = SearchPaneMemory(
        allScrollState = ScrollState(0),
        gridStates = emptyMap<SearchTab, LazyGridState>(),
        tracksListState = LazyListState(),
        lastFocusedIds = mutableStateMapOf(),
        lastFocusedRailKeys = mutableStateMapOf(),
        lastFocusedSectionState = mutableStateOf(null),
        lastFocusedTrackState = mutableStateOf(null),
        lastFocusedAllTrackState = mutableStateOf(null),
        searchedForState = mutableStateOf(""),
    )

    private fun SearchPaneMemory.rememberEverything() {
        lastFocusedIds[SearchTab.Movies] = 20
        lastFocusedRailKeys[SearchKind.Movies] = 20
        lastFocusedSection = SearchKind.Movies
        lastFocusedTrack = TrackFocusMemory(1, TrackRowColumn.Play)
        lastFocusedAllTrack = TrackFocusMemory(1, TrackRowColumn.Play)
    }

    @Test
    fun `a new query forgets every remembered card`() {
        val memory = memory()
        memory.onSearched("star")
        memory.rememberEverything()

        memory.onSearched("star trek")

        assertTrue(memory.lastFocusedIds.isEmpty())
        assertTrue(memory.lastFocusedRailKeys.isEmpty())
        assertNull(memory.lastFocusedSection)
        assertNull(memory.lastFocusedTrack)
        assertNull(memory.lastFocusedAllTrack)
    }

    @Test
    fun `the same query coming back from Home keeps them`() {
        val memory = memory()
        memory.onSearched("star")
        memory.rememberEverything()

        memory.onSearched("star")

        assertEquals(20L, memory.lastFocusedIds[SearchTab.Movies])
        assertEquals(SearchKind.Movies, memory.lastFocusedSection)
    }
}
