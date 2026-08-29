package com.igloo.blindpenguincoder.feature.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.api.MovieApi
import com.igloo.blindpenguincoder.data.model.MovieLibraryItem
import com.igloo.blindpenguincoder.data.model.MoviesLibraryData
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.data.repository.MovieRepository
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.images.TmdbImageSize
import com.igloo.blindpenguincoder.images.tmdbImageUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A library movie ready to render: nullable wire fields resolved, poster path built into a URL. */
data class MoviesGridItem(
    val id: Long,
    val title: String,
    val year: String?,
    val posterUrl: String?,
)

/**
 * What sits below the last loaded row. The grid is infinite (docs/design-system.md section
 * 11.4), so the tail is the only place the user ever sees paging.
 *
 * [Idle] and [Loading] both draw the same skeletons, which is what keeps the grid from jumping:
 * the tail's height is identical before and during a request, so firing a prefetch never reflows
 * the surface under a focused cell.
 */
sealed interface MoviesAppendState {
    /** More pages exist and nothing is in flight. */
    data object Idle : MoviesAppendState
    data object Loading : MoviesAppendState
    data class Error(val message: String) : MoviesAppendState
    /** The last page has been loaded; there is no tail to draw. */
    data object End : MoviesAppendState
}

/** Everything the Movies pane draws. */
data class MoviesUiState(
    val totalMovies: Long? = null,
    val grid: IglooRailState<MoviesGridItem> = IglooRailState.Loading,
    val append: MoviesAppendState = MoviesAppendState.Idle,
    /** True from a Refresh press until page 1 resolves; swaps the button's label. */
    val refreshing: Boolean = false,
    /** A refresh that failed with content still on screen — a notice, not an error card. */
    val notice: String? = null,
    /**
     * Bumped whenever the list is replaced wholesale rather than appended to. The screen scrolls
     * to top and re-anchors focus on a change; an append never bumps it, so a prefetch never
     * moves the user. A state field rather than a one-shot event because scrolling to the top is
     * idempotent and must survive a recomposition mid-refresh, where an event would be lost.
     */
    val contentGeneration: Int = 0,
)

/**
 * The library grid's paging machine.
 *
 * Pages accumulate in [MoviesUiState.grid]; the cursor itself stays private because the screen
 * only ever needs to know whether more exist, never which page it is on. The backend caps
 * `per_page` at [MovieApi.MAX_LIBRARY_PER_PAGE] and offers no sort field, so page size and
 * [SortOrder.Ascending] are fixed rather than user-facing.
 */
class MoviesViewModel(
    private val movies: MovieRepository,
    private val serverUrl: ServerUrlProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MoviesUiState())
    val uiState: StateFlow<MoviesUiState> = _uiState.asStateFlow()

    /** The page [loadMore] will ask for next; 1 until the first page lands. */
    private var nextPage = FIRST_PAGE

    /**
     * Ids already on screen. A–Z paging over a library that is being scanned shifts rows between
     * pages, and a repeated id is a duplicate key — a hard crash in `LazyVerticalGrid`, not a
     * visual glitch — so this is required, not defensive.
     */
    private val seenIds = mutableSetOf<Long>()

    /** One slot for both the first page and appends, so a refresh cancels a prefetch mid-flight. */
    private var pageJob: Job? = null

    /**
     * Guards a response that has already resumed past its last suspension point and so cannot be
     * cancelled: it checks the generation it started in before touching state.
     */
    private var generation = 0

    /**
     * The host's start effect. Unlike Home's refresh this **keeps a loaded grid**: a TV waking
     * from standby must not throw away the pages the user scrolled through and drop them back at
     * the top. The count is cheap enough to re-read every time.
     */
    fun refresh() {
        loadStats()
        if (_uiState.value.grid !is IglooRailState.Loaded) loadFirstPage(userInitiated = false)
    }

    /**
     * The header's Refresh. The loaded grid **stays on screen** while page 1 is in flight —
     * blanking to skeletons would dispose the focused cell mid-request and drop focus onto the
     * shell's fallback, the exact failure [core.ui.IglooMediaRail]'s state-swap handling exists
     * to prevent. The swap is atomic when the page lands.
     */
    fun reload() {
        if (_uiState.value.refreshing) return
        loadStats()
        loadFirstPage(userInitiated = true)
    }

    /** The first-page error card's Retry. Unlike [reload] there is no content to protect. */
    fun retryFirstPage() {
        loadFirstPage(userInitiated = true)
    }

    /**
     * Ask for the next page. Called from the grid's prefetch trigger, which fires on a scroll
     * threshold and so can fire repeatedly for the same page — every guard that makes this
     * idempotent lives here rather than in the composable, because a focus change, a
     * recomposition and a resize can each re-run the trigger.
     */
    fun loadMore() {
        if (pageJob?.isActive == true) return
        if (_uiState.value.grid !is IglooRailState.Loaded) return
        if (_uiState.value.append != MoviesAppendState.Idle) return
        appendNextPage()
    }

    /** The Retry on a failed tail; re-requests the same page that failed. */
    fun retryAppend() {
        if (pageJob?.isActive == true) return
        if (_uiState.value.append !is MoviesAppendState.Error) return
        appendNextPage()
    }

    private fun loadFirstPage(userInitiated: Boolean) {
        pageJob?.cancel()
        val startedIn = ++generation
        if (userInitiated) _uiState.update { it.copy(refreshing = true, notice = null) }
        pageJob = viewModelScope.launch {
            val result = movies.moviesLibrary(FIRST_PAGE, PAGE_SIZE, SORT)
            if (startedIn != generation) return@launch
            when (result) {
                is ApiResult.Success -> {
                    val items = result.value.toGridItems(serverUrl.require().apiBaseUrl)
                    nextPage = FIRST_PAGE + 1
                    seenIds.clear()
                    seenIds += items.map { it.id }
                    _uiState.update {
                        it.copy(
                            grid = IglooRailState.Loaded(items),
                            append = result.value.appendStateFor(FIRST_PAGE, items),
                            refreshing = false,
                            notice = null,
                            contentGeneration = it.contentGeneration + 1,
                        )
                    }
                }
                is ApiResult.Failure -> {
                    val message = result.error.toLibraryDisplayMessage()
                    _uiState.update {
                        val hadContent = it.grid is IglooRailState.Loaded
                        it.copy(
                            grid = IglooRailState.Error(message).orKeepContent(it.grid),
                            refreshing = false,
                            // With content still on screen the failure is over and Refresh is one
                            // press away, so a notice rather than an error card promising a
                            // second, redundant Retry.
                            notice = message.takeIf { _ -> hadContent },
                        )
                    }
                }
            }
        }
    }

    private fun appendNextPage() {
        val page = nextPage
        val startedIn = generation
        _uiState.update { it.copy(append = MoviesAppendState.Loading) }
        pageJob = viewModelScope.launch {
            val result = movies.moviesLibrary(page, PAGE_SIZE, SORT)
            // A refresh can land between the request and its response; appending then would
            // resurrect a page belonging to a list that no longer exists.
            if (startedIn != generation) return@launch
            when (result) {
                is ApiResult.Success -> {
                    val fresh = result.value
                        .toGridItems(serverUrl.require().apiBaseUrl)
                        .filterNot { it.id in seenIds }
                    nextPage = page + 1
                    seenIds += fresh.map { it.id }
                    _uiState.update { state ->
                        val loaded = state.grid as? IglooRailState.Loaded ?: return@update state
                        state.copy(
                            grid = IglooRailState.Loaded(loaded.items + fresh),
                            append = result.value.appendStateFor(page, result.value.movies),
                        )
                    }
                }
                // Never a wipe: the loaded pages stay on screen and the tail becomes a Retry.
                is ApiResult.Failure -> _uiState.update {
                    it.copy(append = MoviesAppendState.Error(result.error.toLibraryDisplayMessage()))
                }
            }
        }
    }

    /**
     * The header count. A failed re-read leaves the shown number alone — a moment of bad wifi as
     * the TV wakes must not blank a count the grid below it still agrees with.
     */
    private fun loadStats() {
        viewModelScope.launch {
            val result = movies.movieStats()
            if (result is ApiResult.Success) {
                _uiState.update { it.copy(totalMovies = result.value.totalMovies) }
            }
        }
    }

    /**
     * `total_pages` is authoritative, but an empty page stops the grid regardless: a library
     * shrinking between requests can return nothing for page N while still claiming more exist.
     */
    private fun MoviesLibraryData.appendStateFor(
        page: Long,
        pageItems: List<*>,
    ): MoviesAppendState =
        if (page >= totalPages || pageItems.isEmpty()) {
            MoviesAppendState.End
        } else {
            MoviesAppendState.Idle
        }

    private fun MoviesLibraryData.toGridItems(apiBaseUrl: String): List<MoviesGridItem> =
        movies.map { it.toGridItem(apiBaseUrl) }

    private fun MovieLibraryItem.toGridItem(apiBaseUrl: String): MoviesGridItem = MoviesGridItem(
        id = id,
        title = title,
        year = year.orNull()?.toString(),
        // w500 for a poster-sized cell: crisp at TV densities, and the same cache entry the
        // detail screen wants when the card is opened.
        posterUrl = tmdbImageUrl(
            apiBaseUrl = apiBaseUrl,
            size = TmdbImageSize.W500,
            path = posterPath.orNullIfBlank(),
        ),
    )

    /**
     * A failed page-1 load never removes cards that are already on screen — a moment of bad wifi
     * as the TV wakes must not replace a working grid with an error card. Unlike the Home rails
     * this holds for a user-initiated refresh too, because here the failure is reported as a
     * notice beside a Refresh button rather than as a card offering its own Retry. Only a first
     * load, with nothing to protect, shows the error.
     */
    private fun <T> IglooRailState<T>.orKeepContent(
        current: IglooRailState<T>,
    ): IglooRailState<T> =
        if (this is IglooRailState.Error && current is IglooRailState.Loaded) current else this

    private companion object {
        const val FIRST_PAGE = 1L
        const val PAGE_SIZE = MovieApi.MAX_LIBRARY_PER_PAGE
        val SORT = SortOrder.Ascending
    }
}
