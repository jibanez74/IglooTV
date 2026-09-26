package com.igloo.blindpenguincoder.feature.library

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.feature.shared.PosterItem

/**
 * Which list the grid shows. The three are the same paged, title-ordered shape server-side;
 * the filter only picks the endpoint.
 */
sealed interface LibraryFilter {
    data object All : LibraryFilter
    data object Liked : LibraryFilter
    data class Genre(val id: Long, val tag: String) : LibraryFilter
}

/**
 * The index's sections (docs/design-system.md section 11.4), mirroring the web client's tab
 * strip with Liked standing in for Playlists until playlists have a screen of their own.
 * [Genres] hosts a picker; the other two are a list each. Which of them a pane offers is
 * [LibrarySource.tabs]: Liked only where the backend keeps likes.
 */
enum class LibraryTab { All, Genres, Liked }

/**
 * The list a tab and genre pair names. Null is the Genres tab with nothing to choose from: there
 * is no endpoint for it, so nothing is fetched and the screen draws a placeholder instead. Read
 * once over the requested pair ([LibraryUiState.filter]) and once over the committed pair (the
 * view model's appends), which mean different things and must not be confused.
 */
internal fun filterFor(tab: LibraryTab, genre: LibraryFilter.Genre?): LibraryFilter? = when (tab) {
    LibraryTab.All -> LibraryFilter.All
    LibraryTab.Liked -> LibraryFilter.Liked
    LibraryTab.Genres -> genre
}

/** Everything a library pane draws. */
data class LibraryUiState(
    /** Which library this is; the screen derives its wording, tags and glyph from it. */
    val kind: LibraryKind,
    /**
     * The strip's sections, fixed per source: All · Genres, plus Liked where the backend keeps
     * likes.
     */
    val tabs: List<LibraryTab>,
    /** Count for the current [filter]: library-wide stats for All, the pages' `total` otherwise. */
    val total: Long? = null,
    /**
     * The requested tab — it highlights the moment focus lands on it. It snaps back to the last
     * committed one if the switch's first page fails, so a selected tab never lies about the
     * grid beneath it.
     */
    val tab: LibraryTab = LibraryTab.All,
    /**
     * The Genres tab's choice. Remembered across tab switches so coming back lands on the same
     * genre; null until the genre list has landed, or when it is empty.
     */
    val genre: LibraryFilter.Genre? = null,
    /** Title direction for the current list — the only sort the backend offers. */
    val sort: SortOrder = SortOrder.Ascending,
    /**
     * All genres with counts; empty before the first success or after an authoritative empty
     * success, and stale only over a failed re-read.
     */
    val genres: List<LibraryGenre> = emptyList(),
    /**
     * True once a genres request has settled, success or failure. Before that the Genres tab
     * draws a loading surface: an empty [genres] on its own cannot tell "not asked yet" from
     * "there are none", and claiming the list is unavailable while it is still in flight is a
     * failure the user never had.
     */
    val genresLoaded: Boolean = false,
    val grid: IglooRailState<PosterItem> = IglooRailState.Loading,
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
    /**
     * Bumped after a successful silent replacement of the shown Liked grid. Only a library with
     * a Liked tab ever bumps it.
     */
    val silentReconcileGeneration: Int = 0,
) {
    /** Which list the grid shows, derived so it can never disagree with [tab] and [genre]. */
    val filter: LibraryFilter?
        get() = filterFor(tab, genre)
}
