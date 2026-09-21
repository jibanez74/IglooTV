package com.igloo.blindpenguincoder.feature.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.orKeepContent
import com.igloo.blindpenguincoder.data.api.MovieApi
import com.igloo.blindpenguincoder.data.model.MovieGenreWithCount
import com.igloo.blindpenguincoder.data.model.MovieLibraryItem
import com.igloo.blindpenguincoder.data.model.MoviesLibraryData
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.data.repository.MovieRepository
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.feature.shared.DUPLICATE_PAGE_BACKOFF_MS
import com.igloo.blindpenguincoder.feature.shared.FIRST_PAGE
import com.igloo.blindpenguincoder.feature.shared.TAB_SWITCH_DEBOUNCE_MS
import com.igloo.blindpenguincoder.feature.shared.pageAppendState
import com.igloo.blindpenguincoder.feature.shared.resetIfLoading
import com.igloo.blindpenguincoder.feature.shared.MoviePosterItem
import com.igloo.blindpenguincoder.feature.shared.moviePosterItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Which list the grid shows. The three are the same paged, title-ordered shape server-side;
 * the filter only picks the endpoint.
 */
sealed interface MoviesFilter {
    data object All : MoviesFilter
    data object Liked : MoviesFilter
    data class Genre(val id: Long, val tag: String) : MoviesFilter
}

/**
 * The index's three sections (docs/design-system.md section 11.4), mirroring the web client's
 * tab strip with Liked standing in for Playlists until playlists have a screen of their own.
 * [Genres] hosts a picker; the other two are a list each.
 */
enum class MoviesTab { All, Genres, Liked }

/** Everything the Movies pane draws. */
data class MoviesUiState(
    /** Count for the current [filter]: library-wide stats for All, the pages' `total` otherwise. */
    val totalMovies: Long? = null,
    /**
     * The requested tab — it highlights the moment focus lands on it. It snaps back to the last
     * committed one if the switch's first page fails, so a selected tab never lies about the
     * grid beneath it.
     */
    val tab: MoviesTab = MoviesTab.All,
    /**
     * The Genres tab's choice. Remembered across tab switches so coming back lands on the same
     * genre; null until the genre list has landed, or when it is empty.
     */
    val genre: MoviesFilter.Genre? = null,
    /** Title direction for the current list — the only sort the backend offers. */
    val sort: SortOrder = SortOrder.Ascending,
    /**
     * All movie genres with counts; empty before the first success or after an authoritative
     * empty success, and stale only over a failed re-read.
     */
    val genres: List<MovieGenreWithCount> = emptyList(),
    /**
     * True once a genres request has settled, success or failure. Before that the Genres tab
     * draws a loading surface: an empty [genres] on its own cannot tell "not asked yet" from
     * "there are none", and claiming the list is unavailable while it is still in flight is a
     * failure the user never had.
     */
    val genresLoaded: Boolean = false,
    val grid: IglooRailState<MoviePosterItem> = IglooRailState.Loading,
    val append: AppendState = AppendState.Idle,
    /** True from a Refresh press until page 1 resolves; swaps the button's label. */
    val refreshing: Boolean = false,
    /** A refresh that failed with content still on screen — a notice, not an error card. */
    val notice: String? = null,
    /** Bumped after every successful append, even when every returned id was already loaded. */
    val appendGeneration: Int = 0,
    /**
     * Bumped whenever the list is replaced wholesale rather than appended to. The screen scrolls
     * to top and re-anchors focus on a change; an append never bumps it, so a prefetch never
     * moves the user. A state field rather than a one-shot event because scrolling to the top is
     * idempotent and must survive a recomposition mid-refresh, where an event would be lost.
     */
    val contentGeneration: Int = 0,
    /** Bumped after a successful silent replacement of the shown Liked grid. */
    val silentReconcileGeneration: Int = 0,
) {
    /**
     * Which list the grid shows, derived so it can never disagree with [tab] and [genre]. Null
     * is the Genres tab with nothing to choose from: there is no endpoint for it, so nothing is
     * fetched and the screen draws a placeholder instead.
     */
    val filter: MoviesFilter?
        get() = when (tab) {
            MoviesTab.All -> MoviesFilter.All
            MoviesTab.Liked -> MoviesFilter.Liked
            MoviesTab.Genres -> genre
        }
}

/**
 * The library grid's paging machine.
 *
 * Pages accumulate in [MoviesUiState.grid]; the cursor itself stays private because the screen
 * only ever needs to know whether more exist, never which page it is on. The backend caps
 * `per_page` at [MovieApi.MAX_LIBRARY_PER_PAGE] and offers no sort field, so page size is fixed
 * and [MoviesUiState.sort] is only a direction. All three sources — library, one genre, liked —
 * share this one paging machine; [fetchPage] is the only place they differ.
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
     * The tab, genre and sort whose page 1 last landed — what the grid actually shows. Appends
     * page these, and a failed switch reverts the requested [MoviesUiState.tab]/`genre`/`sort`
     * to them.
     */
    private var committedTab: MoviesTab = MoviesTab.All
    private var committedGenre: MoviesFilter.Genre? = null
    private var committedSort: SortOrder = SortOrder.Ascending

    /**
     * Ids already on screen. A–Z paging over a library that is being scanned shifts rows between
     * pages, and a repeated id is a duplicate key — a hard crash in `LazyVerticalGrid`, not a
     * visual glitch — so this is required, not defensive.
     */
    private val seenIds = mutableSetOf<Long>()

    /** One slot for both the first page and appends, so a refresh cancels a prefetch mid-flight. */
    private var pageJob: Job? = null

    /** The tab whose page-one request has not left the focus debounce yet. */
    private var pendingDebounceTab: MoviesTab? = null

    /**
     * One slot each for the side loads too: [refresh] fires on every lifecycle START, and two
     * overlapping responses would otherwise race — the older one landing last and winning.
     */
    private var statsJob: Job? = null
    private var genresJob: Job? = null

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
        loadGenres()
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
        // Page one goes first: from the Genres placeholder it has nothing to request, and the
        // genre list landing afterwards is what fetches — one request, not two.
        val onThePlaceholder = _uiState.value.filter == null
        loadFirstPage(userInitiated = true)
        loadStats()
        // On the placeholder the genres round trip is the only request this press makes, so it
        // carries the Refreshing label that page one had nothing to attach it to.
        loadGenres(userInitiated = onThePlaceholder)
    }

    /**
     * A tab taking focus. Re-selecting the current tab is a no-op rather than a surprise
     * refresh — which is also what makes focus landing on the selected tab free. Entering
     * Genres resolves the remembered genre against the current list, falling back to the first
     * genre; with no list yet there is nothing to fetch and the screen shows a placeholder until
     * [loadGenres] lands.
     *
     * The highlight moves at once but the fetch waits [TAB_SWITCH_DEBOUNCE_MS]: a slide across
     * the strip lands on every tab in between, and a tab the d-pad is only passing over must not
     * put a request on the wire, flip the Refreshing label, or re-announce the count.
     */
    fun selectTab(tab: MoviesTab) = switchTab(tab, delayMs = TAB_SWITCH_DEBOUNCE_MS)

    /**
     * A press on a tab — TalkBack's click action, and the retry after a failed switch reverted
     * the selection out from under a focused tab. Deliberate rather than a pass-over, so it
     * skips [selectTab]'s delay. If that focus landing is still pending, the press replaces its
     * delayed job; once the request starts, another press is a no-op.
     */
    fun pressTab(tab: MoviesTab) {
        if (tab == committedTab) return
        if (tab == _uiState.value.tab && pendingDebounceTab != tab) return
        switchTab(tab, delayMs = 0L)
    }

    private fun switchTab(tab: MoviesTab, delayMs: Long) {
        val pendingPress = delayMs == 0L && pendingDebounceTab == tab
        if (tab == _uiState.value.tab && !pendingPress) return
        if (tab != _uiState.value.tab) {
            _uiState.update {
                it.copy(
                    tab = tab,
                    genre = if (tab == MoviesTab.Genres) {
                        it.genre.resolveAgainst(it.genres)
                    } else {
                        it.genre
                    },
                )
            }
        }
        loadFirstPage(userInitiated = true, delayMs = delayMs)
    }

    /** A genre chip press on the Genres tab. Re-pressing the selected chip is a no-op. */
    fun selectGenre(genre: MoviesFilter.Genre) {
        val state = _uiState.value
        if (state.tab == MoviesTab.Genres && state.genre?.id == genre.id) return
        _uiState.update { it.copy(tab = MoviesTab.Genres, genre = genre) }
        loadFirstPage(userInitiated = true)
    }

    /** The header's Sort. Flips the direction and re-reads page 1 of the current filter. */
    fun toggleSort() {
        // Nothing to sort and nothing to request: flipping the label here would leave the header
        // claiming a direction the committed grid under the placeholder is not in.
        if (_uiState.value.filter == null) return
        _uiState.update {
            it.copy(
                sort = when (it.sort) {
                    SortOrder.Ascending -> SortOrder.Descending
                    SortOrder.Descending -> SortOrder.Ascending
                },
            )
        }
        loadFirstPage(userInitiated = true)
    }

    /**
     * The details overlay committed a like toggle. A shown Liked grid is now stale, so it is
     * re-read silently — the overlay is still open above it, so the screen distinguishes this
     * reconciliation from a user-driven replacement before deciding whether focus needs repair.
     */
    fun onLikeCommitted() {
        val state = _uiState.value
        if (state.tab != MoviesTab.Liked) return
        if (state.grid !is IglooRailState.Loaded) return
        loadFirstPage(userInitiated = false, silent = true)
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
        if (_uiState.value.append != AppendState.Idle) return
        appendNextPage()
    }

    /** The Retry on a failed tail; re-requests the same page that failed. */
    fun retryAppend() {
        if (pageJob?.isActive == true) return
        if (_uiState.value.append !is AppendState.Error) return
        appendNextPage()
    }

    /**
     * [silent] is the Liked reconcile under the details overlay: no refreshing label, no
     * notice, and — critically — no [MoviesUiState.contentGeneration] bump, because the shell
     * stays composed beneath the overlay. Its separate silent generation lets the screen repair
     * focus only if the overlay has already closed and the focused movie disappears. A silent
     * failure keeps the existing content and messaging.
     */
    private fun loadFirstPage(
        userInitiated: Boolean,
        silent: Boolean = false,
        delayMs: Long = 0L,
    ) {
        // Cancelled and superseded before the endpoint check below can bail: a page in flight
        // belongs to the list the user is leaving, and one that has resumed past its last
        // suspension point would otherwise still commit — or, on a failure, revert the very tab
        // that just took focus.
        pageJob?.cancel()
        pendingDebounceTab = null
        val startedIn = ++generation
        val requested = _uiState.value
        val tab = requested.tab
        val genre = requested.genre
        val sort = requested.sort
        // The Genres tab with no genre to show has no endpoint: nothing is requested, so the
        // last committed list stays intact under the placeholder and no request is outstanding.
        val filter = requested.filter ?: run {
            _uiState.update {
                it.copy(append = it.append.resetIfLoading(), refreshing = false)
            }
            return
        }
        // A delayed switch holds the chrome back with the request: the Refreshing label must not
        // flip, and the count must not re-announce, for a tab the d-pad is only passing over.
        if (delayMs == 0L) applyFirstPageRequestState(userInitiated, silent)
        else pendingDebounceTab = tab
        pageJob = viewModelScope.launch {
            if (delayMs > 0L) {
                delay(delayMs)
                if (startedIn != generation) return@launch
                pendingDebounceTab = null
                applyFirstPageRequestState(userInitiated, silent)
            }
            val result = fetchPage(filter, sort, FIRST_PAGE)
            if (startedIn != generation) return@launch
            when (result) {
                is ApiResult.Success -> {
                    val apiBaseUrl = apiBaseUrlOrNull() ?: return@launch
                    val items = result.value.toPosterItems(apiBaseUrl)
                    nextPage = FIRST_PAGE + 1
                    seenIds.clear()
                    seenIds += items.map { it.id }
                    committedTab = tab
                    committedGenre = genre
                    committedSort = sort
                    _uiState.update {
                        it.copy(
                            totalMovies = result.value.total,
                            grid = IglooRailState.Loaded(items),
                            append = result.value.appendStateFor(FIRST_PAGE),
                            refreshing = false,
                            notice = if (silent) it.notice else null,
                            contentGeneration = if (silent) {
                                it.contentGeneration
                            } else {
                                it.contentGeneration + 1
                            },
                            silentReconcileGeneration = if (silent) {
                                it.silentReconcileGeneration + 1
                            } else {
                                it.silentReconcileGeneration
                            },
                        )
                    }
                }
                is ApiResult.Failure -> {
                    if (silent) return@launch
                    val message = result.error.toLibraryDisplayMessage()
                    _uiState.update {
                        val hadContent = it.grid is IglooRailState.Loaded
                        it.copy(
                            // The grid still shows the committed list, so the selection snaps
                            // back to it — a tab must never claim a list the grid isn't in.
                            tab = committedTab,
                            genre = when {
                                // Not the Genres tab: the remembered genre is not what this
                                // failure is about, so it survives untouched.
                                committedTab != MoviesTab.Genres -> committedGenre
                                // A genres response can have dropped the committed genre while
                                // this request was out. Reviving it would strand the tab on a
                                // genre with no chip to change it; the placeholder is honest.
                                it.genres.none { listed -> listed.genreId == committedGenre?.id } ->
                                    null
                                else -> committedGenre
                            },
                            sort = committedSort,
                            grid = IglooRailState.Error(message).orKeepContent(it.grid),
                            append = it.append.resetIfLoading(),
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

    /** The chrome a first-page request puts up, raised with the request rather than before it. */
    private fun applyFirstPageRequestState(userInitiated: Boolean, silent: Boolean) {
        when {
            silent -> _uiState.update {
                it.copy(append = it.append.resetIfLoading(), refreshing = false)
            }
            userInitiated -> _uiState.update {
                it.copy(append = it.append.resetIfLoading(), refreshing = true, notice = null)
            }
        }
    }

    private fun appendNextPage() {
        val page = nextPage
        val startedIn = generation
        // Appends page the *committed* list — the one the grid actually shows — never the
        // requested one, which may belong to a switch that hasn't landed.
        val filter = committedFilter() ?: return
        _uiState.update { it.copy(append = AppendState.Loading) }
        pageJob = viewModelScope.launch {
            val result = fetchPage(filter, committedSort, page)
            // A refresh can land between the request and its response; appending then would
            // resurrect a page belonging to a list that no longer exists.
            if (startedIn != generation) return@launch
            when (result) {
                is ApiResult.Success -> {
                    val apiBaseUrl = apiBaseUrlOrNull() ?: return@launch
                    val fresh = result.value
                        .toPosterItems(apiBaseUrl)
                        .filterNot { it.id in seenIds }
                    val tail = result.value.appendStateFor(page)
                    // A library mid-rescan can return a page whose every id is already on
                    // screen. The walk still advances, but only after a beat: the prefetch
                    // effect re-arms on the generation bump below, and without the pause it
                    // would chase every remaining page at line rate with the grid never growing.
                    if (fresh.isEmpty() && tail == AppendState.Idle) {
                        delay(DUPLICATE_PAGE_BACKOFF_MS)
                        if (startedIn != generation) return@launch
                    }
                    val loaded = _uiState.value.grid as? IglooRailState.Loaded ?: return@launch
                    // The cursor moves only once the write below is guaranteed; advanced any
                    // earlier, a dropped page would be unrecoverable without a full reload.
                    nextPage = page + 1
                    seenIds += fresh.map { it.id }
                    _uiState.update {
                        it.copy(
                            totalMovies = result.value.total,
                            grid = IglooRailState.Loaded(loaded.items + fresh),
                            append = tail,
                            appendGeneration = it.appendGeneration + 1,
                        )
                    }
                }
                // Never a wipe: the loaded pages stay on screen and the tail becomes a Retry.
                is ApiResult.Failure -> _uiState.update {
                    it.copy(append = AppendState.Error(result.error.toLibraryDisplayMessage()))
                }
            }
        }
    }

    /** Null only before any page has landed, when there is no append to make anyway. */
    private fun committedFilter(): MoviesFilter? = when (committedTab) {
        MoviesTab.All -> MoviesFilter.All
        MoviesTab.Liked -> MoviesFilter.Liked
        MoviesTab.Genres -> committedGenre
    }

    /** The one place the three sources differ: which endpoint serves the page. */
    private suspend fun fetchPage(
        filter: MoviesFilter,
        sort: SortOrder,
        page: Long,
    ): ApiResult<MoviesLibraryData> = when (filter) {
        MoviesFilter.All -> movies.moviesLibrary(page, PAGE_SIZE, sort)
        MoviesFilter.Liked -> movies.likedMovies(page, PAGE_SIZE, sort)
        is MoviesFilter.Genre -> movies.genreMovies(filter.id, page, PAGE_SIZE, sort)
    }

    /**
     * The header count. A failed re-read leaves the shown number alone — a moment of bad wifi as
     * the TV wakes must not blank a count the grid below it still agrees with. The stat is
     * library-wide, so it only applies to the All view; a filtered count comes from that view's
     * own pages, and a slow stats response must not overwrite it.
     */
    private fun loadStats() {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            val result = movies.movieStats()
            if (result is ApiResult.Success) {
                _uiState.update {
                    if (it.tab == MoviesTab.All) {
                        it.copy(totalMovies = result.value.totalMovies)
                    } else {
                        it
                    }
                }
            }
        }
    }

    /**
     * The Genres tab's picker. Same stance as [loadStats]: a failure keeps whatever was shown
     * last and never reports. A successful response is authoritative, including an empty list.
     * A landing list re-resolves the remembered genre (a renamed tag follows the list; a
     * vanished genre falls back to the first), and an active Genres tab fetches page one when
     * that changes the selected id.
     *
     * Either outcome settles [MoviesUiState.genresLoaded], because either one ends the window in
     * which the tab is still waiting rather than out of genres. [userInitiated] is the Refresh
     * press made from the placeholder, where this is the only request the press produces and so
     * the only one that can carry its label.
     */
    private fun loadGenres(userInitiated: Boolean = false) {
        genresJob?.cancel()
        if (userInitiated) _uiState.update { it.copy(refreshing = true, notice = null) }
        genresJob = viewModelScope.launch {
            val result = movies.movieGenres()
            if (result !is ApiResult.Success) {
                _uiState.update { it.copy(genresLoaded = true) }
                if (userInitiated) clearRefreshing()
                return@launch
            }
            val previousGenreId = _uiState.value.genre?.id
            _uiState.update {
                it.copy(
                    genres = result.value,
                    genre = it.genre.resolveAgainst(result.value),
                    genresLoaded = true,
                )
            }
            val resolved = _uiState.value
            if (
                resolved.tab == MoviesTab.Genres &&
                resolved.genre != null &&
                resolved.genre.id != previousGenreId
            ) {
                // Page one owns the label from here, whether or not anything was pressed.
                loadFirstPage(userInitiated = userInitiated)
            } else if (userInitiated) {
                clearRefreshing()
            }
        }
    }

    private fun clearRefreshing() = _uiState.update { it.copy(refreshing = false) }

    /**
     * The genre the Genres tab should show given [genres]: the remembered one if it is still
     * listed (by id, with the list's current tag), else the first, else nothing. Failures never
     * call this resolver, so only a successful empty list clears the remembered choice.
     */
    private fun MoviesFilter.Genre?.resolveAgainst(
        genres: List<MovieGenreWithCount>,
    ): MoviesFilter.Genre? {
        if (genres.isEmpty()) return null
        val match = genres.firstOrNull { it.genreId == this?.id } ?: genres.first()
        return MoviesFilter.Genre(match.genreId, match.genreTag)
    }

    /**
     * The address clears only while the session tears down, and the session-scoped store is
     * about to drop this view model with it — so a response caught in that window is discarded
     * rather than crashing the scope on [ServerUrlProvider.require].
     */
    private fun apiBaseUrlOrNull(): String? = serverUrl.current.value?.apiBaseUrl

    private fun MoviesLibraryData.appendStateFor(page: Long): AppendState =
        pageAppendState(page, totalPages, movies.isEmpty())

    private fun MoviesLibraryData.toPosterItems(apiBaseUrl: String): List<MoviePosterItem> =
        movies.map { it.toPosterItem(apiBaseUrl) }

    private fun MovieLibraryItem.toPosterItem(apiBaseUrl: String): MoviePosterItem =
        moviePosterItem(
            id = id,
            title = title,
            posterPath = posterPath,
            year = year,
            apiBaseUrl = apiBaseUrl,
        )

    private companion object {
        const val PAGE_SIZE = MovieApi.MAX_LIBRARY_PER_PAGE
    }
}
