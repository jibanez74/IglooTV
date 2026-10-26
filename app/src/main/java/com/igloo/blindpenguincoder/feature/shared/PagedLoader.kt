package com.igloo.blindpenguincoder.feature.shared

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.orKeepContent
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One fetched page: the rows in server order plus what the tail needs to know. */
class LoadedPage<T>(
    val rows: List<T>,
    val total: Long,
    val nextCursor: Long,
    val append: AppendState,
)

/**
 * One infinite list's paging machine — cursor, loaded rows, job and generation — writing its
 * [PagedState] into one field of a view model's state [S] through [read] and [write], so a view
 * model with several lists (the Music pane's tabs, the Search pane's categories) keeps one of
 * these per list and the paging rules are spelled once.
 *
 * [T] is the row the endpoint returns, kept in [loaded] in server order; [R] is what the list
 * draws, derived from every loaded row by [present] — the identity for a grid of cards, the
 * letter-headed entries for a track list, which is why an append re-presents the whole list
 * rather than growing it. Lists page by `page`/`per_page` or by `limit`/`offset`; [fetch] is
 * the only place they differ.
 *
 * The first-page hooks let a view model keep its own whole-state flags in step — the Music
 * pane's `refreshing` and `notice` — without this class knowing them.
 */
class PagedLoader<S, T, R>(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<S>,
    private val read: (S) -> PagedState<R>,
    private val write: (S, PagedState<R>) -> S,
    private val id: (T) -> Long,
    private val present: (List<T>) -> List<R>,
    private val firstCursor: Long,
    private val fetch: suspend (cursor: Long) -> ApiResult<LoadedPage<T>>,
    private val onFirstPageStarted: (S, userInitiated: Boolean) -> S = { s, _ -> s },
    private val onFirstPageLoaded: (S) -> S = { it },
    private val onFirstPageFailed: (S, message: String, hadContent: Boolean) -> S = { s, _, _ -> s },
) {
    /** The next `page` (1-based) or the next `offset`, whichever the endpoint pages by. */
    private var cursor = firstCursor
    private val seenIds = mutableSetOf<Long>()
    private var job: Job? = null
    private var generation = 0

    /** Every row shown so far, in server order; what a play request is built from. */
    var loaded: List<T> = emptyList()
        private set

    val paged: PagedState<R> get() = read(state.value)
    val isLoaded: Boolean get() = paged.content is IglooRailState.Loaded
    private val isBusy: Boolean get() = job?.isActive == true

    private fun update(transform: (PagedState<R>) -> PagedState<R>, then: (S) -> S = { it }) {
        state.update { current -> then(write(current, transform(read(current)))) }
    }

    /** Page 1 for a list with nothing shown; one with pages or an error card is left alone. */
    fun ensureLoaded() {
        if (paged.content !is IglooRailState.Loading) return
        if (isBusy) return
        loadFirstPage(userInitiated = false)
    }

    fun loadFirstPage(userInitiated: Boolean) {
        job?.cancel()
        val startedIn = ++generation
        update({ it.copy(append = it.append.resetIfLoading()) }) {
            onFirstPageStarted(it, userInitiated)
        }
        job = scope.launch {
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
                    }, onFirstPageLoaded)
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
                        onFirstPageFailed(it, message, hadContent)
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

    /**
     * Forgets every page: the list goes back to its skeleton with nothing in flight, and the
     * next [ensureLoaded] fetches afresh. A response to an earlier request is dropped. The
     * generations stay monotonic so a screen's memory of them never meets a repeat.
     *
     * The skeleton comes back as a state change for the caller to apply, so a view model
     * resetting several lists does it in one update rather than one emission per list.
     */
    fun reset(): (S) -> S {
        job?.cancel()
        job = null
        generation += 1
        cursor = firstCursor
        seenIds.clear()
        loaded = emptyList()
        return { current ->
            val paged = read(current)
            write(
                current,
                PagedState(
                    appendGeneration = paged.appendGeneration,
                    // Rows or an error card giving way to a skeleton is a replacement the screen
                    // must re-anchor on, exactly as a page landing is: the focused node disposes.
                    contentGeneration = if (paged.content !is IglooRailState.Loading) paged.contentGeneration + 1 else paged.contentGeneration,
                ),
            )
        }
    }

    private fun appendNextPage() {
        val startedIn = generation
        update({ it.copy(append = AppendState.Loading) })
        job = scope.launch {
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
