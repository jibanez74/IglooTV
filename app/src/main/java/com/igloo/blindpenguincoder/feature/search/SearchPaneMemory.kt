package com.igloo.blindpenguincoder.feature.search

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.igloo.blindpenguincoder.feature.music.TrackFocusMemory

/**
 * What the Search pane keeps across a Home round trip (docs/design-system.md section 6.3):
 * every surface's scroll position and the item focus last rested on, so a return lands where
 * the user left. Held by the host, because the pane's `when` arm has no `SaveableStateHolder`
 * and would forget it all on a destination switch.
 */
@Stable
class SearchPaneMemory(
    val allScrollState: ScrollState,
    private val gridStates: Map<SearchTab, LazyGridState>,
    val tracksListState: LazyListState,
    /** The card last focused on each category tab's grid. */
    val lastFocusedIds: SnapshotStateMap<SearchTab, Long>,
    /** The card last focused in each of the All view's rails. */
    val lastFocusedRailKeys: SnapshotStateMap<SearchKind, Long>,
    private val lastFocusedSectionState: MutableState<SearchKind?>,
    private val lastFocusedTrackState: MutableState<TrackFocusMemory?>,
    private val lastFocusedAllTrackState: MutableState<TrackFocusMemory?>,
    private val searchedForState: MutableState<String>,
) {
    /** The All view's section focus last rested in: where an overlay's Back returns. */
    var lastFocusedSection: SearchKind? by lastFocusedSectionState

    /** The Tracks tab's row and control. */
    var lastFocusedTrack: TrackFocusMemory? by lastFocusedTrackState

    /** The All view's track row and control. */
    var lastFocusedAllTrack: TrackFocusMemory? by lastFocusedAllTrackState

    fun gridState(tab: SearchTab): LazyGridState = gridStates.getValue(tab)

    /**
     * Every remembered card belongs to the query it was focused under: a new [searched] forgets
     * them, so the entry from the tab strip is the new results' first card, not a card an earlier
     * search left mid-grid. The same query coming back from Home keeps them.
     */
    fun onSearched(searched: String) {
        if (searched == searchedForState.value) return
        searchedForState.value = searched
        lastFocusedIds.clear()
        lastFocusedRailKeys.clear()
        lastFocusedSection = null
        lastFocusedTrack = null
        lastFocusedAllTrack = null
    }

    suspend fun scrollToTop(tab: SearchTab) {
        when (tab) {
            SearchTab.All -> allScrollState.scrollTo(0)
            SearchTab.Tracks -> tracksListState.scrollToItem(0)
            else -> gridState(tab).scrollToItem(0)
        }
    }
}

@Composable
internal fun rememberSearchPaneMemory(): SearchPaneMemory {
    val allScrollState = rememberScrollState()
    val gridStates = GRID_TABS.associateWith { rememberLazyGridState() }
    val tracksListState = rememberLazyListState()
    val lastFocusedIds = rememberSaveable(saver = enumKeyedSaver(SearchTab.entries)) { mutableStateMapOf() }
    val lastFocusedRailKeys = rememberSaveable(saver = enumKeyedSaver(SearchKind.entries)) { mutableStateMapOf() }
    val lastFocusedSection = rememberSaveable { mutableStateOf<SearchKind?>(null) }
    val lastFocusedTrack = rememberSaveable(stateSaver = TrackFocusMemory.Saver) {
        mutableStateOf<TrackFocusMemory?>(null)
    }
    val lastFocusedAllTrack = rememberSaveable(stateSaver = TrackFocusMemory.Saver) {
        mutableStateOf<TrackFocusMemory?>(null)
    }
    val searchedFor = rememberSaveable { mutableStateOf("") }
    return remember {
        SearchPaneMemory(
            allScrollState = allScrollState,
            gridStates = gridStates,
            tracksListState = tracksListState,
            lastFocusedIds = lastFocusedIds,
            lastFocusedRailKeys = lastFocusedRailKeys,
            lastFocusedSectionState = lastFocusedSection,
            lastFocusedTrackState = lastFocusedTrack,
            lastFocusedAllTrackState = lastFocusedAllTrack,
            searchedForState = searchedFor,
        )
    }
}

private val GRID_TABS = listOf(SearchTab.Movies, SearchTab.Shows, SearchTab.Albums, SearchTab.Musicians)

/** Saves an enum-keyed id map as name/id pairs; a key a newer build renamed loses its memory. */
private fun <K : Enum<K>> enumKeyedSaver(entries: List<K>) =
    listSaver<SnapshotStateMap<K, Long>, Any>(
        save = { map -> map.flatMap { (key, id) -> listOf(key.name, id) } },
        restore = { saved ->
            mutableStateMapOf<K, Long>().apply {
                saved.chunked(2).forEach { (name, id) ->
                    entries.firstOrNull { it.name == name }?.let { put(it, id as Long) }
                }
            }
        },
    )
