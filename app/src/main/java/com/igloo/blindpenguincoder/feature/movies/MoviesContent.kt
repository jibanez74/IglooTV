package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.shared.MoviePosterItem

/**
 * The four surfaces the Movies pane can draw — [IglooRailState] with the empty case made
 * explicit, so the screen and the focus coordinator branch on one model.
 */
internal sealed interface MoviesContent {
    data object Loading : MoviesContent
    data class Error(val message: String) : MoviesContent
    data class Empty(val filter: MoviesFilter) : MoviesContent
    data class Populated(val items: List<MoviePosterItem>) : MoviesContent
}

internal fun MoviesUiState.toMoviesContent(): MoviesContent = when (val grid = grid) {
    IglooRailState.Loading -> MoviesContent.Loading
    is IglooRailState.Error -> MoviesContent.Error(grid.message)
    is IglooRailState.Loaded -> if (grid.items.isEmpty()) {
        MoviesContent.Empty(filter)
    } else {
        MoviesContent.Populated(grid.items)
    }
}

internal fun MoviesContent.containsMovie(movieId: Long): Boolean =
    this is MoviesContent.Populated && items.any { it.id == movieId }
