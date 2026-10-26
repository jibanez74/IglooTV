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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/** The remembered cards and scroll positions belong to the query they were left under. */
class SearchPaneMemoryTest {

    private val gridTabs = SearchTab.entries - SearchTab.All - SearchTab.Tracks

    /** Every surface starts scrolled deep, as an earlier query's results left them. */
    private fun memory() = SearchPaneMemory(
        allScrollState = ScrollState(500),
        gridStates = gridTabs.associateWith { LazyGridState(firstVisibleItemIndex = 31) },
        tracksListState = LazyListState(firstVisibleItemIndex = 31),
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
    fun `a new query forgets every remembered card`() = runTest {
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
    fun `the same query coming back from Home keeps them`() = runTest {
        val memory = memory()
        memory.onSearched("star")
        memory.rememberEverything()

        memory.onSearched("star")

        assertEquals(20L, memory.lastFocusedIds[SearchTab.Movies])
        assertEquals(SearchKind.Movies, memory.lastFocusedSection)
    }

    @Test
    fun `a new query scrolls every surface back to the top`() = runTest {
        val memory = memory()

        memory.onSearched("star trek")

        assertEquals(0, memory.allScrollState.value)
        gridTabs.forEach { assertEquals(0, memory.gridState(it).firstVisibleItemIndex) }
        assertEquals(0, memory.tracksListState.firstVisibleItemIndex)
    }

    @Test
    fun `the same query coming back from Home keeps its scroll`() = runTest {
        val memory = memory()

        memory.onSearched("")

        assertEquals(500, memory.allScrollState.value)
        assertEquals(31, memory.gridState(SearchTab.Movies).firstVisibleItemIndex)
    }
}
