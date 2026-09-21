package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.shared.MoviePosterItem
import com.igloo.blindpenguincoder.feature.shared.PaneContent

/**
 * The six surfaces the Movies pane can draw — [IglooRailState] with the empty case made explicit
 * and the Genres tab's two "nothing to pick" cases added, so the screen and the focus
 * coordinator branch on one model.
 */
internal sealed interface MoviesContent : PaneContent {
    override val isPopulated: Boolean get() = this is Populated

    /** The two skeleton waits differ only in what they are waiting for. */
    override val isSkeleton: Boolean get() = this is Loading || this is GenresLoading

    override val isCardless: Boolean get() = this is Error || this is Empty || this is NoGenres

    override val isEmpty: Boolean get() = this is Empty

    data object Loading : MoviesContent

    /** The Genres tab before the genre list has settled; whatever pages exist stay hidden. */
    data object GenresLoading : MoviesContent

    /** The Genres tab with a settled list that has nothing in it, or that failed to load. */
    data object NoGenres : MoviesContent

    data class Error(val message: String) : MoviesContent

    data class Empty(val filter: MoviesFilter) : MoviesContent

    data class Populated(val items: List<MoviePosterItem>) : MoviesContent
}

internal fun MoviesUiState.toMoviesContent(): MoviesContent {
    // No filter is the Genres tab with nothing to page. Which of the two surfaces it draws turns
    // on whether the genre list has been asked for yet: an empty list on its own cannot tell a
    // request still in flight from a library with no genres, and only one of those is a failure.
    val filter = filter
        ?: return if (genresLoaded) MoviesContent.NoGenres else MoviesContent.GenresLoading
    return when (val grid = grid) {
        IglooRailState.Loading -> MoviesContent.Loading
        is IglooRailState.Error -> MoviesContent.Error(grid.message)
        is IglooRailState.Loaded -> if (grid.items.isEmpty()) {
            MoviesContent.Empty(filter)
        } else {
            MoviesContent.Populated(grid.items)
        }
    }
}

internal fun MoviesContent.containsMovie(movieId: Long): Boolean =
    this is MoviesContent.Populated && items.any { it.id == movieId }
