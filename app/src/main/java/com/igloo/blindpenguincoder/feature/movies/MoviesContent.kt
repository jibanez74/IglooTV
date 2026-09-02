package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.shared.MoviePosterItem

/**
 * The five surfaces the Movies pane can draw — [IglooRailState] with the empty case made
 * explicit and the Genres tab's "nothing to pick" case added, so the screen and the focus
 * coordinator branch on one model.
 */
internal sealed interface MoviesContent {
    data object Loading : MoviesContent

    /** The Genres tab with no genre list to choose from; whatever pages exist stay hidden. */
    data object NoGenres : MoviesContent
    data class Error(val message: String) : MoviesContent
    data class Empty(val filter: MoviesFilter) : MoviesContent
    data class Populated(val items: List<MoviePosterItem>) : MoviesContent
}

internal fun MoviesUiState.toMoviesContent(): MoviesContent {
    val filter = filter ?: return MoviesContent.NoGenres
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
