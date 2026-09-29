package com.igloo.blindpenguincoder.feature.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.orKeepContent
import com.igloo.blindpenguincoder.data.api.MAX_LIBRARY_PER_PAGE
import com.igloo.blindpenguincoder.data.model.MusicStats
import com.igloo.blindpenguincoder.data.model.TrackListItem
import com.igloo.blindpenguincoder.data.repository.MusicRepository
import com.igloo.blindpenguincoder.feature.auth.toFailureNotice
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.feature.shared.DUPLICATE_PAGE_BACKOFF_MS
import com.igloo.blindpenguincoder.feature.shared.FIRST_PAGE
import com.igloo.blindpenguincoder.feature.shared.TAB_SWITCH_DEBOUNCE_MS
import com.igloo.blindpenguincoder.feature.shared.pageAppendState
import com.igloo.blindpenguincoder.feature.shared.resetIfLoading
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
}

/** The tab's rows, or null while it still shows a skeleton or an error card. */
internal fun <T> PagedState<T>.loadedItems(): List<T>? = (content as? IglooRailState.Loaded)?.items

/**
 * The Music pane's paging machine: one [Pager] per tab — cursor, loaded rows, job and
 * generation — all retained for the session so a Home → Music round trip and a tab round trip
 * both land on the pages the user had. Musicians and albums page by `page`/`per_page`, tracks
 * by `limit`/`offset`; each pager's `fetch` is the only place they differ.
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

    /** One fetched page: the rows in server order plus what the tail needs to know. */
    private class Page<T>(
        val rows: List<T>,
        val total: Long,
        val nextCursor: Long,
        val append: AppendState,
    )

    /**
     * One tab's paging state and rules. [T] is the row the endpoint returns, kept in
     * [loaded] in server order; [R] is what the tab draws, derived from every loaded row by
     * [present] — the identity for a grid of cards, the letter-headed entries for the track
     * list, which is why an append re-presents the whole list rather than growing it.
     */
    private inner class Pager<T, R>(
        private val read: (MusicUiState) -> PagedState<R>,
        private val write: (MusicUiState, PagedState<R>) -> MusicUiState,
        private val id: (T) -> Long,
        private val present: (List<T>) -> List<R>,
        private val firstCursor: Long,
        private val fetch: suspend (cursor: Long) -> ApiResult<Page<T>>,
    ) {
        /** The next `page` (1-based) for musicians and albums, the next `offset` for tracks. */
        private var cursor = firstCursor
        private val seenIds = mutableSetOf<Long>()
        private var job: Job? = null
        private var generation = 0

        /** Every row shown so far, in server order; what a play request is built from. */
        var loaded: List<T> = emptyList()
            private set

        val paged: PagedState<R> get() = read(_uiState.value)
        val isLoaded: Boolean get() = paged.content is IglooRailState.Loaded
        val isBusy: Boolean get() = job?.isActive == true

        private fun update(transform: (PagedState<R>) -> PagedState<R>, then: (MusicUiState) -> MusicUiState = { it }) {
            _uiState.update { state -> then(write(state, transform(read(state)))) }
        }

        /** Page 1 for a tab with nothing shown; a tab with pages or an error card is left alone. */
        fun ensureLoaded() {
            if (paged.content !is IglooRailState.Loading) return
            if (isBusy) return
            loadFirstPage(userInitiated = false)
        }

        fun loadFirstPage(userInitiated: Boolean) {
            job?.cancel()
            val startedIn = ++generation
            if (userInitiated) {
                update({ it.copy(append = it.append.resetIfLoading()) }) {
                    it.copy(refreshing = true, notice = null)
                }
            }
            job = viewModelScope.launch {
                val result = fetch(firstCursor)
                if (startedIn != generation) return@launch
                when (result) {
                    is ApiResult.Success -> {
                        val page = result.value
                        cursor = page.nextCursor
                        seenIds.clear()
                        seenIds += page.rows.map(id)
                        loaded = page.rows
                        update({
                            it.copy(
                                content = IglooRailState.Loaded(present(loaded)),
                                append = page.append,
                                total = page.total,
                                contentGeneration = it.contentGeneration + 1,
                            )
                        }) {
                            it.copy(refreshing = false, notice = null)
                        }
                    }

                    is ApiResult.Failure -> {
                        val message = result.error.toLibraryDisplayMessage()
                        val hadContent = isLoaded
                        update({
                            it.copy(
                                content = IglooRailState.Error(message).orKeepContent(it.content),
                                append = it.append.resetIfLoading(),
                            )
                        }) {
                            it.copy(
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

        /** The prefetch; every idempotence guard lives here, not in the composable. */
        fun loadMore() {
            if (isBusy || !isLoaded || paged.append != AppendState.Idle) return
            appendNextPage()
        }

        /** The Retry on a failed tail; re-requests the same page that failed. */
        fun retryAppend() {
            if (isBusy || paged.append !is AppendState.Error) return
            appendNextPage()
        }

        private fun appendNextPage() {
            val startedIn = generation
            update({ it.copy(append = AppendState.Loading) })
            job = viewModelScope.launch {
                val result = fetch(cursor)
                // A refresh can land between the request and its response; appending then would
                // resurrect a page belonging to a list that no longer exists.
                if (startedIn != generation) return@launch
                when (result) {
                    is ApiResult.Success -> {
                        val page = result.value
                        val fresh = page.rows.filterNot { id(it) in seenIds }
                        // A library mid-rescan can return a page whose every id is already on
                        // screen; the walk still advances, but only after a beat, or the prefetch
                        // would chase every remaining page at line rate with nothing growing.
                        if (fresh.isEmpty() && page.append == AppendState.Idle) {
                            delay(DUPLICATE_PAGE_BACKOFF_MS)
                            if (startedIn != generation) return@launch
                        }
                        cursor = page.nextCursor
                        seenIds += fresh.map(id)
                        loaded = loaded + fresh
                        update({
                            it.copy(
                                content = IglooRailState.Loaded(present(loaded)),
                                append = page.append,
                                total = page.total,
                                appendGeneration = it.appendGeneration + 1,
                            )
                        })
                    }

                    // Never a wipe: the loaded pages stay on screen and the tail becomes a Retry.
                    is ApiResult.Failure -> update({
                        it.copy(append = AppendState.Error(result.error.toLibraryDisplayMessage()))
                    })
                }
            }
        }
    }

    private val musiciansPager = Pager<MusicianCardUi, MusicianCardUi>(
        read = { it.musicians },
        write = { state, paged -> state.copy(musicians = paged) },
        id = { it.id },
        present = { it },
        firstCursor = FIRST_PAGE,
        fetch = { page ->
            music.musicians(page, PAGE_SIZE).map { data ->
                val rows = data.musicians.map { it.toCardUi() }
                Page(rows, data.total, page + 1, pageAppendState(page, data.totalPages, rows.isEmpty()))
            }
        },
    )

    private val albumsPager = Pager<AlbumCardUi, AlbumCardUi>(
        read = { it.albums },
        write = { state, paged -> state.copy(albums = paged) },
        id = { it.id },
        present = { it },
        firstCursor = FIRST_PAGE,
        fetch = { page ->
            music.albums(page, PAGE_SIZE).map { data ->
                val rows = data.albums.map { it.toCardUi() }
                Page(rows, data.total, page + 1, pageAppendState(page, data.totalPages, rows.isEmpty()))
            }
        },
    )

    private val tracksPager = Pager<TrackListItem, TracksEntry>(
        read = { it.tracks },
        write = { state, paged -> state.copy(tracks = paged) },
        id = { it.id },
        present = ::tracksEntries,
        firstCursor = 0L,
        fetch = { offset ->
            music.tracks(TRACKS_PAGE_SIZE, offset).map { data ->
                Page(
                    rows = data.tracks,
                    total = data.total,
                    nextCursor = offset + data.tracks.size,
                    // `has_more` is authoritative, but an empty page stops the list regardless:
                    // a library shrinking between requests can return nothing for an offset
                    // while still claiming more exist.
                    append = if (!data.hasMore || data.tracks.isEmpty()) AppendState.End else AppendState.Idle,
                )
            }
        },
    )

    private val pagers: Map<MusicTab, Pager<*, *>> = mapOf(
        MusicTab.Musicians to musiciansPager,
        MusicTab.Albums to albumsPager,
        MusicTab.Tracks to tracksPager,
    )

    private val selectedPager: Pager<*, *> get() = pagers.getValue(_uiState.value.tab)

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
        selectedPager.ensureLoaded()
    }

    /**
     * The header's Refresh: page 1 of the selected tab, with its content staying on screen
     * while the request is out (the Movies rule), and the stats.
     */
    fun reload() {
        if (_uiState.value.refreshing) return
        selectedPager.loadFirstPage(userInitiated = true)
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
        val pager = pagers.getValue(tab)
        if (pager.isLoaded) return
        debounceJob = viewModelScope.launch {
            delay(TAB_SWITCH_DEBOUNCE_MS)
            pager.ensureLoaded()
        }
    }

    /** A press on a tab — TalkBack's click action: deliberate, so it loads at once. */
    fun pressTab(tab: MusicTab) {
        debounceJob?.cancel()
        _uiState.update { it.copy(tab = tab) }
        pagers.getValue(tab).ensureLoaded()
    }

    /** The first-page error card's Retry on the selected tab. */
    fun retryFirstPage() {
        selectedPager.loadFirstPage(userInitiated = true)
    }

    /** The selected tab's prefetch. */
    fun loadMore() {
        selectedPager.loadMore()
    }

    /** The Retry on a failed tail of the selected tab. */
    fun retryAppend() {
        selectedPager.retryAppend()
    }

    /** A row's Play on the Tracks tab: the loaded list from that row (web parity). */
    fun playTrack(id: Long) {
        trackListPlayRequest(tracksPager.loaded, id)?.let { playRequestChannel.trySend(it) }
    }

    /** Play all: the loaded rows now, the rest of the library as the queue plays. */
    fun playAll() {
        val loaded = tracksPager.loaded
        val total = _uiState.value.tracks.total ?: loaded.size.toLong()
        playAllRequest(loaded, total)?.let { playRequestChannel.trySend(it) }
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
                    it.copy(notice = result.error.toFailureNotice("start shuffle"))
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

    private companion object {
        const val PAGE_SIZE = MAX_LIBRARY_PER_PAGE
        const val TRACKS_PAGE_SIZE = 50L
    }
}

/**
 * The pane's actions bound to this view model, so the host wires the pane in one line. Likes
 * belong to the shared track-likes view model, which the host passes in.
 */
fun MusicViewModel.actions(onToggleLike: (Long) -> Unit): MusicActions = MusicActions(
    onRefresh = ::reload,
    onRetryFirstPage = ::retryFirstPage,
    onRetryAppend = ::retryAppend,
    onLoadMore = ::loadMore,
    onSelectTab = ::selectTab,
    onPressTab = ::pressTab,
    onPlayTrack = ::playTrack,
    onPlayAll = ::playAll,
    onShuffleAll = ::shuffleAll,
    onToggleLike = onToggleLike,
)
