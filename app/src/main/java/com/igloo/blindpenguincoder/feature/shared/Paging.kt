package com.igloo.blindpenguincoder.feature.shared

import com.igloo.blindpenguincoder.core.ui.IglooRailState

/**
 * What sits below the last loaded row of an infinite list (docs/design-system.md section
 * 11.4); the tail is the only place the user ever sees paging.
 *
 * [Idle] and [Loading] both draw the same skeletons, which is what keeps the surface from
 * jumping: the tail's height is identical before and during a request, so firing a prefetch
 * never reflows the surface under a focused cell.
 */
sealed interface AppendState {
    /** More pages exist and nothing is in flight. */
    data object Idle : AppendState
    data object Loading : AppendState
    data class Error(val message: String) : AppendState
    /** The last page has been loaded; there is no tail to draw. */
    data object End : AppendState
}

/**
 * How long a tab taking focus waits before it fetches. Tabs select on focus, so a slide across
 * the strip lands on every tab in between; without this each pass-over puts a request on the
 * wire and flips the header's label on its way past. Visible to the tests so they can wait out
 * exactly this rather than a number that has to be kept in step by hand.
 */
internal const val TAB_SWITCH_DEBOUNCE_MS = 300L

/** How long an all-duplicates page holds a paging walk back before the cursor advances. */
internal const val DUPLICATE_PAGE_BACKOFF_MS = 250L

/** The first `page` of every page-numbered list route. */
internal const val FIRST_PAGE = 1L

/** A superseded request's Loading tail must not outlive the request it belonged to. */
internal fun AppendState.resetIfLoading(): AppendState =
    if (this == AppendState.Loading) AppendState.Idle else this

/**
 * `total_pages` is authoritative, but an empty page stops the list regardless: a library
 * shrinking between requests can return nothing for page N while still claiming more exist.
 */
internal fun pageAppendState(page: Long, totalPages: Long, empty: Boolean): AppendState =
    if (page >= totalPages || empty) AppendState.End else AppendState.Idle

/**
 * One infinite list's pages: what the pane shows, its tail, and the server's count. A Music tab
 * keeps one each, so switching back is instant and can never fail; a library pane keeps one for
 * whichever list its tab and genre name.
 */
data class PagedState<T>(
    val content: IglooRailState<T> = IglooRailState.Loading,
    val append: AppendState = AppendState.Idle,
    /** The server's count for this list; null until it is known. */
    val total: Long? = null,
    /** Bumped after every successful append, even when every returned id was already loaded. */
    val appendGeneration: Int = 0,
    /**
     * Bumped whenever the list is replaced wholesale rather than appended to. The screen scrolls
     * to top and re-anchors focus on a change; an append never bumps it, so a prefetch never
     * moves the user. A state field rather than a one-shot event because scrolling to the top is
     * idempotent and must survive a recomposition mid-refresh, where an event would be lost.
     */
    val contentGeneration: Int = 0,
)

/** The loaded rows, or null while the list still shows a skeleton or an error card. */
internal fun <T> PagedState<T>.loadedItems(): List<T>? = (content as? IglooRailState.Loaded)?.items
