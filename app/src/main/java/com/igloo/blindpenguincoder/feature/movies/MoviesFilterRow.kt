package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooFilterChip
import com.igloo.blindpenguincoder.core.ui.withRequester
import com.igloo.blindpenguincoder.data.model.MovieGenreWithCount

/**
 * The Movies index's filter chips: All · Liked · one chip per genre, with counts
 * (docs/design-system.md section 11.4).
 *
 * A plain [Row] under [horizontalScroll], deliberately not a `LazyRow`: a library's genre list
 * is bounded (tens, not thousands), and keeping every chip composed keeps every focus requester
 * permanently attached — the grid's `up = filterRowRequester` can never target a disposed node,
 * and no rail-style entry-key machinery is needed. A focused chip brings itself into view.
 * Revisit with a `LazyRow` only if genre counts ever invalidate that assumption.
 *
 * All and Liked render before the genres fetch lands, so the row's height is set immediately
 * and genre chips appearing later never reflow the grid under a focused cell. A failed genres
 * fetch simply leaves the row at All + Liked — the view model degrades it silently.
 *
 * Focus contract: every chip goes up to Refresh and down to the pane's content anchor (whatever
 * the grid state put there — entry card, skeleton anchor, error Retry, or the empty box); the
 * first chip exits left to the navigation spine and the last chip's right edge is pinned. The
 * selected chip carries [filterRowRequester] so the grid's first row lands back on it; if the
 * selected genre ever vanishes from a refreshed list, the requester falls back to All so the
 * grid's `up` edge always resolves.
 */
@Composable
internal fun MoviesFilterRow(
    genres: List<MovieGenreWithCount>,
    selected: MoviesFilter,
    contentInset: PaddingValues,
    filterRowRequester: FocusRequester,
    navigationRequester: FocusRequester,
    refreshRequester: FocusRequester,
    contentStartRequester: FocusRequester,
    onSelectFilter: (MoviesFilter) -> Unit,
) {
    val direction = LocalLayoutDirection.current
    val chips = remember(genres) {
        buildList {
            add(
                MoviesFilterChipSpec(
                    filter = MoviesFilter.All,
                    text = "All",
                    semanticLabel = "All movies",
                    actionLabel = "Show all movies",
                    testTag = "movies_filter_all",
                ),
            )
            add(
                MoviesFilterChipSpec(
                    filter = MoviesFilter.Liked,
                    text = "Liked",
                    semanticLabel = "Liked movies",
                    actionLabel = "Show liked movies",
                    testTag = "movies_filter_liked",
                ),
            )
            genres.forEach { genre ->
                val count = NUMBER_FORMAT.format(genre.movieCount)
                add(
                    MoviesFilterChipSpec(
                        filter = MoviesFilter.Genre(genre.genreId, genre.genreTag),
                        text = "${genre.genreTag} · $count",
                        semanticLabel = "${genre.genreTag}, $count ${plural(genre.movieCount)}",
                        actionLabel = "Show ${genre.genreTag} movies",
                        testTag = "movies_filter_genre_${genre.genreId}",
                    ),
                )
            }
        }
    }
    // The anchor must always attach somewhere the row actually renders; All always does.
    val anchor = if (chips.any { it.filter.matches(selected) }) selected else MoviesFilter.All

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The gutter sits inside the scroll surface (section 8.3): the modifier order puts
            // the padding in the scrolled content, so the last chip can scroll past the panel
            // edge instead of stopping short of it.
            .horizontalScroll(rememberScrollState())
            .padding(
                start = contentInset.calculateStartPadding(direction),
                end = contentInset.calculateEndPadding(direction),
            )
            .testTag("movies_filter_row"),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        chips.forEachIndexed { index, chip ->
            IglooFilterChip(
                text = chip.text,
                selected = chip.filter.matches(selected),
                onClick = { onSelectFilter(chip.filter) },
                semanticLabel = chip.semanticLabel,
                actionLabel = chip.actionLabel,
                modifier = Modifier
                    .withRequester(filterRowRequester.takeIf { chip.filter.matches(anchor) })
                    .focusProperties {
                        // The header and the grid are siblings of this scroll surface, so both
                        // vertical edges are wired rather than resolved spatially.
                        up = refreshRequester
                        down = contentStartRequester
                        if (index == 0) left = navigationRequester
                        if (index == chips.lastIndex) right = FocusRequester.Cancel
                    }
                    .testTag(chip.testTag),
            )
        }
    }
}

private data class MoviesFilterChipSpec(
    val filter: MoviesFilter,
    val text: String,
    val semanticLabel: String,
    val actionLabel: String,
    val testTag: String,
)

/** Genre identity is the id: a renamed tag on a refreshed list must not deselect the chip. */
private fun MoviesFilter.matches(selected: MoviesFilter): Boolean = when {
    this is MoviesFilter.Genre && selected is MoviesFilter.Genre -> id == selected.id
    else -> this == selected
}
