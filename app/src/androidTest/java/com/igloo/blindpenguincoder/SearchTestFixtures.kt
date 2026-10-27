package com.igloo.blindpenguincoder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.music.AlbumCardUi
import com.igloo.blindpenguincoder.feature.music.MusicianCardUi
import com.igloo.blindpenguincoder.feature.music.TracksEntry
import com.igloo.blindpenguincoder.feature.music.toRowUi
import com.igloo.blindpenguincoder.feature.search.SearchActions
import com.igloo.blindpenguincoder.feature.search.SearchSectionUi
import com.igloo.blindpenguincoder.feature.search.SearchTab
import com.igloo.blindpenguincoder.feature.search.SearchUiState
import com.igloo.blindpenguincoder.feature.search.VoiceSearch
import com.igloo.blindpenguincoder.feature.search.VoiceSearchEvent
import com.igloo.blindpenguincoder.feature.search.VoiceSearchOutcome
import com.igloo.blindpenguincoder.feature.search.VoiceSearchSession
import com.igloo.blindpenguincoder.feature.search.sectionTitle
import com.igloo.blindpenguincoder.feature.search.SearchKind
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.feature.shared.PagedState
import com.igloo.blindpenguincoder.feature.shared.PosterItem

/**
 * The All view for "help": every category with more matches than rows, so each rail ends in a
 * See-all card and the tracks column in a See-all button; the rows are the other panes' own
 * fixtures so the cards carry the tags they have there.
 */
internal val testSearchSections: List<SearchSectionUi> = listOf(
    SearchSectionUi.Movies(testMovieGridItems.take(3), total = 12),
    SearchSectionUi.Shows(testShowGridItems.take(2), total = 2),
    SearchSectionUi.Albums(testMusicAlbums.take(2), total = 5),
    SearchSectionUi.Musicians(testMusicians.take(2), total = 3),
    SearchSectionUi.Tracks(
        rows = testLibraryTracks.take(3).mapIndexed { index, track ->
            track.toRowUi(prefix = sectionTitle(SearchKind.Tracks, 7).takeIf { index == 0 })
        },
        tracks = testLibraryTracks.take(3),
        total = 7,
    ),
)

/** The track rows of the Tracks tab: ranked, so no letter headers. */
internal val testSearchTrackEntries: List<TracksEntry> =
    testLibraryTracks.map { TracksEntry.Track(it.toRowUi()) }

/** "help" searched and every tab loaded, the All view selected. */
internal fun testSearchState(
    query: String = "help",
    searched: String = query.trim(),
    tab: SearchTab = SearchTab.All,
    all: PagedState<SearchSectionUi> = PagedState(IglooRailState.Loaded(testSearchSections), append = AppendState.End, total = 29),
    movies: PagedState<PosterItem> = PagedState(IglooRailState.Loaded(testMovieGridItems), total = 40, append = AppendState.End),
    shows: PagedState<PosterItem> = PagedState(IglooRailState.Loaded(testShowGridItems), total = 40, append = AppendState.End),
    albums: PagedState<AlbumCardUi> = PagedState(IglooRailState.Loaded(testMusicAlbums), total = 30, append = AppendState.End),
    musicians: PagedState<MusicianCardUi> = PagedState(IglooRailState.Loaded(testMusicians), total = 30, append = AppendState.End),
    tracks: PagedState<TracksEntry> = PagedState(IglooRailState.Loaded(testSearchTrackEntries), total = 5, append = AppendState.End),
) = SearchUiState(
    query = query,
    searched = searched,
    tab = tab,
    all = all,
    movies = movies,
    shows = shows,
    albums = albums,
    musicians = musicians,
    tracks = tracks,
)

internal val inertSearchActions = SearchActions(
    onQueryChange = {},
    onSearchNow = {},
    onClear = {},
    onVoiceSearch = {},
    onSelectTab = {},
    onPressTab = {},
    onRetryFirstPage = {},
    onRetryAppend = {},
    onLoadMore = {},
    onPlayTrack = {},
    onToggleLike = {},
)

/**
 * The speech recogniser under instrumentation: a microphone cannot be driven by UiAutomation,
 * so a suite counts [starts] and [cancels] and hands the pane events with [deliver], on the UI
 * thread, as the real recogniser would. [available] off composes no button, the Fire TV
 * configuration.
 */
internal class FakeVoiceSearch(override val available: Boolean = true) : VoiceSearch {
    var starts = 0
        private set
    var cancels = 0
        private set
    private var onEvent: ((VoiceSearchEvent) -> Unit)? = null
    private var listening = false

    @Composable
    override fun rememberSession(onEvent: (VoiceSearchEvent) -> Unit): VoiceSearchSession {
        SideEffect { this.onEvent = onEvent }
        return remember {
            object : VoiceSearchSession {
                override fun start() { starts += 1 }

                // As the real session: a cancel while listening reports itself.
                override fun cancel() {
                    cancels += 1
                    if (listening) deliver(VoiceSearchEvent.Done(VoiceSearchOutcome.Cancelled))
                }
            }
        }
    }

    fun deliver(event: VoiceSearchEvent) {
        listening = event is VoiceSearchEvent.Listening || (listening && event is VoiceSearchEvent.Partial)
        checkNotNull(onEvent) { "no Search pane has asked for a session" }(event)
    }
}
