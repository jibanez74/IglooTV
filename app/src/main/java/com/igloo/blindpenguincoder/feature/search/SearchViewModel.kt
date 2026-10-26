package com.igloo.blindpenguincoder.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.withApiBaseUrl
import com.igloo.blindpenguincoder.data.api.MAX_LIBRARY_PER_PAGE
import com.igloo.blindpenguincoder.data.model.SearchPageData
import com.igloo.blindpenguincoder.data.model.TrackListItem
import com.igloo.blindpenguincoder.data.repository.SearchRepository
import com.igloo.blindpenguincoder.feature.music.AlbumCardUi
import com.igloo.blindpenguincoder.feature.music.MusicianCardUi
import com.igloo.blindpenguincoder.feature.music.TracksEntry
import com.igloo.blindpenguincoder.feature.music.toCardUi
import com.igloo.blindpenguincoder.feature.music.trackListPlayRequest
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.feature.shared.FIRST_PAGE
import com.igloo.blindpenguincoder.feature.shared.LoadedPage
import com.igloo.blindpenguincoder.feature.shared.PagedLoader
import com.igloo.blindpenguincoder.feature.shared.PagedState
import com.igloo.blindpenguincoder.feature.shared.PosterItem
import com.igloo.blindpenguincoder.feature.shared.TAB_SWITCH_DEBOUNCE_MS
import com.igloo.blindpenguincoder.feature.shared.loadedItems
import com.igloo.blindpenguincoder.feature.shared.pageAppendState
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the Search pane draws. */
data class SearchUiState(
    /** The field's text, exactly as typed. */
    val query: String = "",
    /** The trimmed query the results belong to; blank until the first search is sent. */
    val searched: String = "",
    val tab: SearchTab = SearchTab.All,
    /** The All view: every category as one row each, so the header can sum their totals. */
    val all: PagedState<SearchSectionUi> = PagedState(),
    val movies: PagedState<PosterItem> = PagedState(),
    val shows: PagedState<PosterItem> = PagedState(),
    val albums: PagedState<AlbumCardUi> = PagedState(),
    val musicians: PagedState<MusicianCardUi> = PagedState(),
    val tracks: PagedState<TracksEntry> = PagedState(),
) {
    /** The selected tab's list. */
    val selected: PagedState<*>
        get() = when (tab) {
            SearchTab.All -> all
            SearchTab.Movies -> movies
            SearchTab.Shows -> shows
            SearchTab.Albums -> albums
            SearchTab.Musicians -> musicians
            SearchTab.Tracks -> tracks
        }
}

/** What the Search pane needs from its view model, bundled rather than threaded as lambdas. */
data class SearchActions(
    val onQueryChange: (String) -> Unit,
    /** The keyboard's Search action: the query goes out now, not after the debounce. */
    val onSearchNow: () -> Unit,
    val onClear: () -> Unit,
    /** A tab taking focus — debounced by the view model, because a slide crosses every tab. */
    val onSelectTab: (SearchTab) -> Unit,
    /** A press on a tab, or a See-all card: deliberate, so it loads at once. */
    val onPressTab: (SearchTab) -> Unit,
    val onRetryFirstPage: () -> Unit,
    val onRetryAppend: () -> Unit,
    val onLoadMore: () -> Unit,
    val onPlayTrack: (Long) -> Unit,
    val onToggleLike: (Long) -> Unit,
)

/**
 * The Search pane's machine (docs/design-system.md section 11.6): one [PagedLoader] per tab over
 * the query last sent. Typing restarts a [SEARCH_DEBOUNCE_MS] wait; when it lands, every tab is
 * reset to its skeleton and only the selected one fetches — the others load when selected, like
 * the Music pane's. The keyboard's Search action skips the wait. A blank query sends nothing and
 * empties every tab.
 *
 * A row's Play goes through [playRequests]: the rows shown become the queue, starting at the
 * pressed one, which is what the web client does on its search page.
 */
class SearchViewModel(
    private val search: SearchRepository,
    private val serverUrl: ServerUrlProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val playRequestChannel = Channel<MusicPlayRequest>(Channel.CONFLATED)
    val playRequests: Flow<MusicPlayRequest> = playRequestChannel.receiveAsFlow()

    private val searched: String get() = _uiState.value.searched

    private fun <T, R> loader(
        read: (SearchUiState) -> PagedState<R>,
        write: (SearchUiState, PagedState<R>) -> SearchUiState,
        id: (T) -> Long,
        present: (List<T>) -> List<R>,
        fetch: suspend (page: Long) -> ApiResult<LoadedPage<T>>,
    ) = PagedLoader(
        scope = viewModelScope,
        state = _uiState,
        read = read,
        write = write,
        id = id,
        present = present,
        firstCursor = FIRST_PAGE,
        fetch = fetch,
    )

    /** A category's page as the loader wants it, rows drawn by [present]; `page + 1` is the next cursor. */
    private fun <T, R> SearchPageData<T>.toLoadedPage(page: Long, present: (T) -> R): LoadedPage<R> =
        LoadedPage(results.map(present), total, page + 1, pageAppendState(page, totalPages, results.isEmpty()))

    private val allLoader = loader<SearchSectionUi, SearchSectionUi>(
        read = { it.all },
        write = { state, paged -> state.copy(all = paged) },
        id = { it.kind.ordinal.toLong() },
        present = { it },
        fetch = {
            search.searchAll(searched).withApiBaseUrl(serverUrl).map { (data, apiBaseUrl) ->
                val sections = data.toSections(apiBaseUrl)
                LoadedPage(sections, sections.totalMatches(), FIRST_PAGE, AppendState.End)
            }
        },
    )

    private val moviesLoader = loader<PosterItem, PosterItem>(
        read = { it.movies },
        write = { state, paged -> state.copy(movies = paged) },
        id = { it.id },
        present = { it },
        fetch = { page ->
            search.searchMovies(searched, page, PAGE_SIZE).withApiBaseUrl(serverUrl).map { (data, apiBaseUrl) ->
                data.toLoadedPage(page) { movie -> movie.toPosterItem(apiBaseUrl) }
            }
        },
    )

    private val showsLoader = loader<PosterItem, PosterItem>(
        read = { it.shows },
        write = { state, paged -> state.copy(shows = paged) },
        id = { it.id },
        present = { it },
        fetch = { page ->
            search.searchShows(searched, page, PAGE_SIZE).withApiBaseUrl(serverUrl).map { (data, apiBaseUrl) ->
                data.toLoadedPage(page) { show -> show.toPosterItem(apiBaseUrl) }
            }
        },
    )

    private val albumsLoader = loader<AlbumCardUi, AlbumCardUi>(
        read = { it.albums },
        write = { state, paged -> state.copy(albums = paged) },
        id = { it.id },
        present = { it },
        fetch = { page ->
            search.searchAlbums(searched, page, PAGE_SIZE).map { it.toLoadedPage(page) { album -> album.toCardUi() } }
        },
    )

    private val musiciansLoader = loader<MusicianCardUi, MusicianCardUi>(
        read = { it.musicians },
        write = { state, paged -> state.copy(musicians = paged) },
        id = { it.id },
        present = { it },
        fetch = { page ->
            search.searchMusicians(searched, page, PAGE_SIZE).map { it.toLoadedPage(page) { musician -> musician.toCardUi() } }
        },
    )

    private val tracksLoader = loader<TrackListItem, TracksEntry>(
        read = { it.tracks },
        write = { state, paged -> state.copy(tracks = paged) },
        id = { it.id },
        present = { tracks -> tracks.map { it.toSearchEntry() } },
        fetch = { page -> search.searchTracks(searched, page, PAGE_SIZE).map { it.toLoadedPage(page) { track -> track } } },
    )

    private val loaders: Map<SearchTab, PagedLoader<SearchUiState, *, *>> = mapOf(
        SearchTab.All to allLoader,
        SearchTab.Movies to moviesLoader,
        SearchTab.Shows to showsLoader,
        SearchTab.Albums to albumsLoader,
        SearchTab.Musicians to musiciansLoader,
        SearchTab.Tracks to tracksLoader,
    )

    private val selectedLoader: PagedLoader<SearchUiState, *, *> get() = loaders.getValue(_uiState.value.tab)

    private var debounceJob: Job? = null
    private var tabDebounceJob: Job? = null

    /**
     * Every keystroke. The field shows it at once; the search goes out [SEARCH_DEBOUNCE_MS] after
     * the last one, so a word typed on a remote costs one request, not one per letter. Trimming
     * to the query already searched changes nothing; trimming to blank clears the results now.
     */
    fun onQueryChange(text: String) {
        debounceJob?.cancel()
        _uiState.update { it.copy(query = text) }
        val trimmed = text.trim()
        when {
            trimmed.isEmpty() -> clearResults()
            trimmed == searched -> Unit
            else -> debounceJob = viewModelScope.launch {
                delay(SEARCH_DEBOUNCE_MS)
                submit(trimmed)
            }
        }
    }

    /**
     * The keyboard's Search action. A query the debounce has not sent yet goes now; one already
     * answered is left alone, so results on screen never bounce through a skeleton; a failed one
     * is retried.
     */
    fun searchNow() {
        debounceJob?.cancel()
        val trimmed = _uiState.value.query.trim()
        when {
            trimmed.isEmpty() -> Unit
            trimmed != searched -> submit(trimmed)
            selectedLoader.paged.content is IglooRailState.Error ->
                selectedLoader.loadFirstPage(userInitiated = true)
        }
    }

    /** The header's Clear: an empty field and the prompt, on every tab. */
    fun clear() {
        debounceJob?.cancel()
        _uiState.update { it.copy(query = "") }
        clearResults()
    }

    /**
     * A tab taking focus. The highlight moves at once, but a tab with nothing loaded waits
     * [TAB_SWITCH_DEBOUNCE_MS] before requesting, so a slide across the strip puts no throwaway
     * request on the wire.
     */
    fun selectTab(tab: SearchTab) {
        tabDebounceJob?.cancel()
        _uiState.update { it.copy(tab = tab) }
        val loader = loaders.getValue(tab)
        if (loader.isLoaded || searched.isBlank()) return
        tabDebounceJob = viewModelScope.launch {
            delay(TAB_SWITCH_DEBOUNCE_MS)
            loader.ensureLoaded()
        }
    }

    /** A press on a tab, or a See-all card: deliberate, so it loads at once. */
    fun pressTab(tab: SearchTab) {
        tabDebounceJob?.cancel()
        _uiState.update { it.copy(tab = tab) }
        if (searched.isNotBlank()) loaders.getValue(tab).ensureLoaded()
    }

    /** The first-page error card's Retry on the selected tab. */
    fun retryFirstPage() {
        if (searched.isNotBlank()) selectedLoader.loadFirstPage(userInitiated = true)
    }

    /** The selected tab's prefetch. */
    fun loadMore() {
        selectedLoader.loadMore()
    }

    /** The Retry on a failed tail of the selected tab. */
    fun retryAppend() {
        selectedLoader.retryAppend()
    }

    /**
     * A row's Play: the rows on screen become the queue, from the pressed one — the Tracks tab's
     * loaded pages, or the All view's eight.
     */
    fun playTrack(id: Long) {
        val shown = when (_uiState.value.tab) {
            SearchTab.Tracks -> tracksLoader.loaded
            else -> _uiState.value.all.loadedItems().orEmpty()
                .filterIsInstance<SearchSectionUi.Tracks>().firstOrNull()?.tracks.orEmpty()
        }
        trackListPlayRequest(shown, id)?.let { playRequestChannel.trySend(it) }
    }

    private fun submit(trimmed: String) {
        resetResults { it.copy(searched = trimmed) }
        selectedLoader.ensureLoaded()
    }

    private fun clearResults() {
        // Back to All as well: the prompt draws no strip, so a category kept selected under it
        // would send the next search to a tab the user cannot see (found on the Shield).
        resetResults { it.copy(searched = "", tab = SearchTab.All) }
    }

    /**
     * Every tab back to its skeleton under the new [query state][withQuery], in one emission. A
     * tab focus still waiting out its debounce belonged to the old query, so it goes too.
     */
    private fun resetResults(withQuery: (SearchUiState) -> SearchUiState) {
        tabDebounceJob?.cancel()
        val resets = loaders.values.map { it.reset() }
        _uiState.update { state -> resets.fold(withQuery(state)) { next, reset -> reset(next) } }
    }

    private companion object {
        const val PAGE_SIZE = MAX_LIBRARY_PER_PAGE
    }
}

/**
 * How long the field waits after the last keystroke before searching. Long enough that a word
 * typed on the on-screen keyboard is one request; short enough that results appear while the
 * keyboard is still up. Visible to the tests so they wait out exactly this.
 */
internal const val SEARCH_DEBOUNCE_MS = 400L

/** The pane's actions bound to this view model; likes belong to the shared likes view model. */
fun SearchViewModel.actions(onToggleLike: (Long) -> Unit): SearchActions = SearchActions(
    onQueryChange = ::onQueryChange,
    onSearchNow = ::searchNow,
    onClear = ::clear,
    onSelectTab = ::selectTab,
    onPressTab = ::pressTab,
    onRetryFirstPage = ::retryFirstPage,
    onRetryAppend = ::retryAppend,
    onLoadMore = ::loadMore,
    onPlayTrack = ::playTrack,
    onToggleLike = onToggleLike,
)
