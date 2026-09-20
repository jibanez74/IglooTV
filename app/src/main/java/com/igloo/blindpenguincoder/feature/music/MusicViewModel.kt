package com.igloo.blindpenguincoder.feature.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.orKeepContent
import com.igloo.blindpenguincoder.data.api.MusicApi
import com.igloo.blindpenguincoder.data.model.MusicStats
import com.igloo.blindpenguincoder.data.model.TrackListItem
import com.igloo.blindpenguincoder.data.repository.MusicRepository
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.feature.movies.TAB_SWITCH_DEBOUNCE_MS
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.queue.MusicQueueController
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

/** The pane's three sections (docs/design-system.md section 11.5); Playlists waits for its own pass. */
enum class MusicTab { Musicians, Albums, Tracks }

/**
 * One tab's pages. Each tab keeps its own, so switching back is instant and a switch can never
 * fail: a tab with nothing yet shows its skeleton, one whose page one failed shows an error
 * card with Retry, and only a Refresh replaces what is shown.
 */
data class PagedState<T>(
    val content: IglooRailState<T> = IglooRailState.Loading,
    val append: AppendState = AppendState.Idle,
    /** The server's count for this list; null until its first page lands. */
    val total: Long? = null,
    /** Bumped after every successful append, even when every returned id was already loaded. */
    val appendGeneration: Int = 0,
    /** Bumped whenever the list is replaced wholesale (Refresh, Retry), never on an append. */
    val contentGeneration: Int = 0,
)

/** Everything the Music pane draws. */
data class MusicUiState(
    val tab: MusicTab = MusicTab.Musicians,
    val musicians: PagedState<MusicianCardUi> = PagedState(),
    val albums: PagedState<AlbumCardUi> = PagedState(),
    val tracks: PagedState<TracksEntry> = PagedState(),
    /** Library-wide counts, the header's number until a tab's own page says otherwise. */
    val stats: MusicStats? = null,
    /** True from a Refresh press until the selected tab's page 1 resolves. */
    val refreshing: Boolean = false,
    /** A refresh that failed with content still on screen — a notice, not an error card. */
    val notice: String? = null,
    /** True while Shuffle all waits for its first batch; the button shows it. */
    val shufflePending: Boolean = false,
) {
    /** The selected tab's count line: its own total once known, the library stat before. */
    val selectedTotal: Long?
        get() = when (tab) {
            MusicTab.Musicians -> musicians.total ?: stats?.totalMusicians
            MusicTab.Albums -> albums.total ?: stats?.totalAlbums
            MusicTab.Tracks -> tracks.total ?: stats?.totalTracks
        }

    /** How many of the selected tab's items are on screen: rows only, never the letter headers. */
    val selectedLoadedCount: Int?
        get() = when (tab) {
            MusicTab.Musicians -> musicians.loadedItems()?.size
            MusicTab.Albums -> albums.loadedItems()?.size
            MusicTab.Tracks -> tracks.loadedItems()?.count { it is TracksEntry.Track }
        }

    private fun <T> PagedState<T>.loadedItems(): List<T>? = (content as? IglooRailState.Loaded)?.items
}

/**
 * The Music pane's paging machine: one cursor, one job and one generation per tab, all three
 * retained for the session so a Home → Music round trip and a tab round trip both land on the
 * pages the user had. Musicians and albums page by `page`/`per_page`, tracks by
 * `limit`/`offset` — [fetchAndApply] is the only place they differ.
 *
 * Playback launches go through [playRequests]: a row's Play and Play all map the loaded rows
 * synchronously, Shuffle all first fetches the server's random batch.
 */
class MusicViewModel(
    private val music: MusicRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MusicUiState())
    val uiState: StateFlow<MusicUiState> = _uiState.asStateFlow()

    private val playRequestChannel = Channel<MusicPlayRequest>(Channel.CONFLATED)
    val playRequests: Flow<MusicPlayRequest> = playRequestChannel.receiveAsFlow()

    private class Pager {
        /** The next `page` (1-based) for musicians and albums, the next `offset` for tracks. */
        var cursor = 0L
        val seenIds = mutableSetOf<Long>()
        var job: Job? = null
        var generation = 0
    }

    private val pagers = MusicTab.entries.associateWith { Pager() }

    /** The Tracks tab's rows in server order — what Play all and a row's Play queue. */
    private var loadedTracks: List<TrackListItem> = emptyList()

    private var statsJob: Job? = null
    private var debounceJob: Job? = null
    private var shuffleJob: Job? = null

    /**
     * The host's start effect. Keeps every loaded tab — a TV waking from standby must not throw
     * away the pages the user scrolled through — and fills only the selected one if it has
     * nothing yet; the others load when they are selected.
     */
    fun refresh() {
        loadStats()
        ensureLoaded(_uiState.value.tab)
    }

    /**
     * The header's Refresh: page 1 of the selected tab, with its content staying on screen
     * while the request is out (the Movies rule), and the stats.
     */
    fun reload() {
        if (_uiState.value.refreshing) return
        loadFirstPage(_uiState.value.tab, userInitiated = true)
        loadStats()
    }

    /**
     * A tab taking focus. The highlight moves at once — the tab's own state is what it shows —
     * but a tab with nothing loaded waits [TAB_SWITCH_DEBOUNCE_MS] before requesting, so a slide
     * across the strip puts no throwaway request on the wire.
     */
    fun selectTab(tab: MusicTab) {
        debounceJob?.cancel()
        _uiState.update { it.copy(tab = tab) }
        if (isLoaded(tab)) return
        debounceJob = viewModelScope.launch {
            delay(TAB_SWITCH_DEBOUNCE_MS)
            ensureLoaded(tab)
        }
    }

    /** A press on a tab — TalkBack's click action: deliberate, so it loads at once. */
    fun pressTab(tab: MusicTab) {
        debounceJob?.cancel()
        _uiState.update { it.copy(tab = tab) }
        ensureLoaded(tab)
    }

    /** The first-page error card's Retry on the selected tab. */
    fun retryFirstPage() {
        loadFirstPage(_uiState.value.tab, userInitiated = true)
    }

    /** The selected tab's prefetch; every idempotence guard lives here, not in the composable. */
    fun loadMore() {
        val tab = _uiState.value.tab
        val pager = pagers.getValue(tab)
        if (pager.job?.isActive == true) return
        val paged = _uiState.value.paged(tab)
        if (paged.content !is IglooRailState.Loaded) return
        if (paged.append != AppendState.Idle) return
        appendNextPage(tab)
    }

    /** The Retry on a failed tail; re-requests the same page that failed. */
    fun retryAppend() {
        val tab = _uiState.value.tab
        if (pagers.getValue(tab).job?.isActive == true) return
        if (_uiState.value.paged(tab).append !is AppendState.Error) return
        appendNextPage(tab)
    }

    /** A row's Play on the Tracks tab: the loaded list from that row (web parity). */
    fun playTrack(id: Long) {
        trackListPlayRequest(loadedTracks, id)?.let { playRequestChannel.trySend(it) }
    }

    /** Play all: the loaded rows now, the rest of the library as the queue plays. */
    fun playAll() {
        val total = _uiState.value.tracks.total ?: loadedTracks.size.toLong()
        playAllRequest(loadedTracks, total)?.let { playRequestChannel.trySend(it) }
    }

    /**
     * Shuffle all: the one asynchronous launch. The button shows the wait; a second press
     * cancels the first; an empty batch or a failure is a notice, not a player.
     */
    fun shuffleAll() {
        if (shuffleJob?.isActive == true) {
            shuffleJob?.cancel()
            _uiState.update { it.copy(shufflePending = false) }
            return
        }
        _uiState.update { it.copy(shufflePending = true, notice = null) }
        shuffleJob = viewModelScope.launch {
            val result = music.shuffleTracks(MusicQueueController.BATCH_SIZE.toLong(), emptyList())
            _uiState.update { it.copy(shufflePending = false) }
            when (result) {
                is ApiResult.Success -> {
                    val request = shuffleAllRequest(result.value.tracks)
                    if (request == null) {
                        _uiState.update { it.copy(notice = "No tracks to shuffle.") }
                    } else {
                        playRequestChannel.trySend(request)
                    }
                }

                is ApiResult.Failure -> _uiState.update {
                    it.copy(notice = "Couldn't start shuffle: " + result.error.toLibraryDisplayMessage())
                }
            }
        }
    }

    private fun isLoaded(tab: MusicTab): Boolean =
        _uiState.value.paged(tab).content is IglooRailState.Loaded

    /** Page 1 for a tab with nothing shown; a tab with pages or an error card is left alone. */
    private fun ensureLoaded(tab: MusicTab) {
        if (_uiState.value.paged(tab).content !is IglooRailState.Loading) return
        if (pagers.getValue(tab).job?.isActive == true) return
        loadFirstPage(tab, userInitiated = false)
    }

    private fun loadFirstPage(tab: MusicTab, userInitiated: Boolean) {
        val pager = pagers.getValue(tab)
        pager.job?.cancel()
        val startedIn = ++pager.generation
        if (userInitiated) {
            _uiState.update { state ->
                state.updatePaged(tab) { it.copy(append = it.append.resetIfLoading()) }
                    .copy(refreshing = true, notice = null)
            }
        }
        pager.job = viewModelScope.launch {
            val result = fetchAndApply(tab, firstPage = true)
            if (startedIn != pager.generation) return@launch
            when (result) {
                is ApiResult.Success -> {
                    val page = result.value
                    pager.cursor = page.nextCursor
                    pager.seenIds.clear()
                    pager.seenIds += page.ids
                    if (tab == MusicTab.Tracks) loadedTracks = page.tracks
                    _uiState.update { state ->
                        state.updatePaged(tab) {
                            it.copy(
                                content = IglooRailState.Loaded(page.items),
                                append = page.append,
                                total = page.total,
                                contentGeneration = it.contentGeneration + 1,
                            )
                        }.copy(refreshing = false, notice = null)
                    }
                }

                is ApiResult.Failure -> {
                    val message = result.error.toLibraryDisplayMessage()
                    _uiState.update { state ->
                        val hadContent = state.paged(tab).content is IglooRailState.Loaded
                        state.updatePaged(tab) {
                            it.copy(
                                content = IglooRailState.Error(message).orKeepContent(it.content),
                                append = it.append.resetIfLoading(),
                            )
                        }.copy(
                            refreshing = false,
                            // With content still on screen the failure is over and Refresh is
                            // one press away: a notice, not a second Retry.
                            notice = message.takeIf { hadContent },
                        )
                    }
                }
            }
        }
    }

    private fun appendNextPage(tab: MusicTab) {
        val pager = pagers.getValue(tab)
        val startedIn = pager.generation
        _uiState.update { state -> state.updatePaged(tab) { it.copy(append = AppendState.Loading) } }
        pager.job = viewModelScope.launch {
            val result = fetchAndApply(tab, firstPage = false)
            // A refresh can land between the request and its response; appending then would
            // resurrect a page belonging to a list that no longer exists.
            if (startedIn != pager.generation) return@launch
            when (result) {
                is ApiResult.Success -> {
                    val page = result.value
                    val freshIds = page.ids.filterNot { it in pager.seenIds }
                    // A library mid-rescan can return a page whose every id is already on
                    // screen; the walk still advances, but only after a beat, or the prefetch
                    // would chase every remaining page at line rate with nothing growing.
                    if (freshIds.isEmpty() && page.append == AppendState.Idle) {
                        delay(DUPLICATE_PAGE_BACKOFF_MS)
                        if (startedIn != pager.generation) return@launch
                    }
                    pager.cursor = page.nextCursor
                    pager.seenIds += freshIds
                    // Letter headers depend on the row before them, so the track list is
                    // re-derived whole from its rows; a grid page is aligned with its ids and
                    // simply grows by the rows not already shown.
                    if (tab == MusicTab.Tracks) {
                        loadedTracks = loadedTracks + page.tracks.filter { it.id in freshIds }
                    }
                    val fresh = if (tab == MusicTab.Tracks) {
                        emptyList()
                    } else {
                        page.items.filterIndexed { index, _ -> page.ids[index] in freshIds }
                    }
                    val rebuiltEntries = if (tab == MusicTab.Tracks) tracksEntries(loadedTracks) else null
                    _uiState.update { state ->
                        state.updatePaged(tab) {
                            val shown = (it.content as? IglooRailState.Loaded)?.items.orEmpty()
                            it.copy(
                                content = IglooRailState.Loaded(rebuiltEntries ?: (shown + fresh)),
                                append = page.append,
                                total = page.total,
                                appendGeneration = it.appendGeneration + 1,
                            )
                        }
                    }
                }

                // Never a wipe: the loaded pages stay on screen and the tail becomes a Retry.
                is ApiResult.Failure -> _uiState.update { state ->
                    state.updatePaged(tab) {
                        it.copy(append = AppendState.Error(result.error.toLibraryDisplayMessage()))
                    }
                }
            }
        }
    }

    /**
     * One fetched page in the shape every tab's paging code reads. For the two grids [items]
     * and [ids] are aligned, so a card can be kept or dropped by its id without knowing its
     * type; the track list's [items] carry letter headers and are used only as a first page —
     * an append re-derives them from [tracks].
     */
    private class Page(
        val items: List<Any>,
        val ids: List<Long>,
        val total: Long,
        val nextCursor: Long,
        val append: AppendState,
        /** Only the Tracks tab fills this; its rows are what a play request is built from. */
        val tracks: List<TrackListItem> = emptyList(),
    )

    /** The one place the three tabs differ: which endpoint, which cursor, which mapping. */
    private suspend fun fetchAndApply(tab: MusicTab, firstPage: Boolean): ApiResult<Page> {
        val pager = pagers.getValue(tab)
        return when (tab) {
            MusicTab.Musicians -> {
                val page = if (firstPage) FIRST_PAGE else pager.cursor
                music.musicians(page, PAGE_SIZE).map { data ->
                    val items = data.musicians.map { it.toCardUi() }
                    Page(
                        items = items,
                        ids = items.map { it.id },
                        total = data.total,
                        nextCursor = page + 1,
                        append = pageAppendState(page, data.totalPages, items.isEmpty()),
                    )
                }
            }

            MusicTab.Albums -> {
                val page = if (firstPage) FIRST_PAGE else pager.cursor
                music.albums(page, PAGE_SIZE).map { data ->
                    val items = data.albums.map { it.toCardUi() }
                    Page(
                        items = items,
                        ids = items.map { it.id },
                        total = data.total,
                        nextCursor = page + 1,
                        append = pageAppendState(page, data.totalPages, items.isEmpty()),
                    )
                }
            }

            MusicTab.Tracks -> {
                val offset = if (firstPage) 0L else pager.cursor
                music.tracks(TRACKS_PAGE_SIZE, offset).map { data ->
                    Page(
                        items = tracksEntries(data.tracks),
                        ids = data.tracks.map { it.id },
                        total = data.total,
                        nextCursor = offset + data.tracks.size,
                        // `has_more` is authoritative, but an empty page stops the list
                        // regardless: a library shrinking between requests can return nothing
                        // for an offset while still claiming more exist.
                        append = if (!data.hasMore || data.tracks.isEmpty()) AppendState.End else AppendState.Idle,
                        tracks = data.tracks,
                    )
                }
            }
        }
    }

    /**
     * The header's numbers. A failed re-read leaves the shown ones alone — a moment of bad wifi
     * as the TV wakes must not blank a count the grid below it still agrees with.
     */
    private fun loadStats() {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            val result = music.musicStats()
            if (result is ApiResult.Success) _uiState.update { it.copy(stats = result.value) }
        }
    }

    private fun pageAppendState(page: Long, totalPages: Long, empty: Boolean): AppendState =
        if (page >= totalPages || empty) AppendState.End else AppendState.Idle

    /** A superseded request's Loading tail must not outlive the request it belonged to. */
    private fun AppendState.resetIfLoading(): AppendState =
        if (this == AppendState.Loading) AppendState.Idle else this

    @Suppress("UNCHECKED_CAST")
    private fun MusicUiState.paged(tab: MusicTab): PagedState<Any> = when (tab) {
        MusicTab.Musicians -> musicians
        MusicTab.Albums -> albums
        MusicTab.Tracks -> tracks
    } as PagedState<Any>

    @Suppress("UNCHECKED_CAST")
    private fun MusicUiState.updatePaged(
        tab: MusicTab,
        transform: (PagedState<Any>) -> PagedState<Any>,
    ): MusicUiState = when (tab) {
        MusicTab.Musicians -> copy(musicians = transform(musicians as PagedState<Any>) as PagedState<MusicianCardUi>)
        MusicTab.Albums -> copy(albums = transform(albums as PagedState<Any>) as PagedState<AlbumCardUi>)
        MusicTab.Tracks -> copy(tracks = transform(tracks as PagedState<Any>) as PagedState<TracksEntry>)
    }

    private companion object {
        const val FIRST_PAGE = 1L
        const val PAGE_SIZE = MusicApi.MAX_PER_PAGE
        const val TRACKS_PAGE_SIZE = 50L

        /** How long an all-duplicates page holds the walk back before the cursor advances. */
        const val DUPLICATE_PAGE_BACKOFF_MS = 250L
    }
}
