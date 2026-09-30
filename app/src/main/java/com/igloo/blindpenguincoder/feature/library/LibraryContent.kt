package com.igloo.blindpenguincoder.feature.library

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.shared.PaneContent
import com.igloo.blindpenguincoder.feature.shared.PosterItem

/**
 * The six surfaces a library pane can draw — [IglooRailState] with the empty case made explicit
 * and the Genres tab's two "nothing to pick" cases added, so the screen and the focus
 * coordinator branch on one model.
 */
internal sealed interface LibraryContent : PaneContent {
    override val isPopulated: Boolean get() = this is Populated

    /** The two skeleton waits differ only in what they are waiting for. */
    override val isSkeleton: Boolean get() = this is Loading || this is GenresLoading

    override val isCardless: Boolean get() = this is Error || this is Empty || this is NoGenres

    override val isEmpty: Boolean get() = this is Empty

    data object Loading : LibraryContent

    /** The Genres tab before the genre list has settled; whatever pages exist stay hidden. */
    data object GenresLoading : LibraryContent

    /** The Genres tab with a settled list that has nothing in it, or that failed to load. */
    data object NoGenres : LibraryContent

    data class Error(val message: String) : LibraryContent

    data class Empty(val filter: LibraryFilter) : LibraryContent

    data class Populated(val items: List<PosterItem>) : LibraryContent
}

internal fun LibraryUiState.toLibraryContent(): LibraryContent {
    // No filter is the Genres tab with nothing to page. Which of the two surfaces it draws turns
    // on whether the genre list has been asked for yet: an empty list on its own cannot tell a
    // request still in flight from a library with no genres, and only one of those is a failure.
    val filter = filter
        ?: return if (genresLoaded) LibraryContent.NoGenres else LibraryContent.GenresLoading
    return when (val grid = paged.content) {
        IglooRailState.Loading -> LibraryContent.Loading
        is IglooRailState.Error -> LibraryContent.Error(grid.message)
        is IglooRailState.Loaded -> if (grid.items.isEmpty()) {
            LibraryContent.Empty(filter)
        } else {
            LibraryContent.Populated(grid.items)
        }
    }
}

internal fun LibraryContent.containsItem(id: Long): Boolean =
    this is LibraryContent.Populated && items.any { it.id == id }

/**
 * The skeleton anchor's announcement: the genre list is the wait on the Genres tab, the page
 * otherwise.
 */
internal fun LibraryContent.loadingLabel(kind: LibraryKind): String =
    if (this is LibraryContent.GenresLoading) "Loading genres" else kind.loadingLabel

/** How many items the header can say are on screen; unknown while nothing is loaded. */
internal val LibraryContent.loadedCount: Int?
    get() = when (this) {
        is LibraryContent.Populated -> items.size
        is LibraryContent.Empty -> 0
        else -> null
    }
