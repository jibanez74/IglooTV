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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooFilterChip
import com.igloo.blindpenguincoder.core.ui.integerCountFormat
import com.igloo.blindpenguincoder.core.ui.movieNoun
import com.igloo.blindpenguincoder.core.ui.withRequester
import com.igloo.blindpenguincoder.data.model.MovieGenreWithCount

/**
 * The Genres tab's picker: one chip per genre, with counts (docs/design-system.md section
 * 11.4). Only composed while the Genres tab is selected and has a list to show.
 *
 * A plain [Row] under [horizontalScroll], deliberately not a `LazyRow`: a library's genre list
 * is bounded (tens, not thousands), and keeping every chip composed keeps every focus requester
 * permanently attached — the grid's `up = genreRowRequester` can never target a disposed node,
 * and no rail-style entry-key machinery is needed. A focused chip brings itself into view.
 * Revisit with a `LazyRow` only if genre counts ever invalidate that assumption.
 *
 * Focus contract: every chip goes up to the selected tab and down to the pane's content anchor
 * (whatever the grid state put there — entry card, skeleton anchor, error Retry, or the empty
 * box); the first chip exits left to the navigation spine and the last chip's right edge is
 * pinned. The selected chip carries [genreRowRequester] so the grid's first row lands back on
 * it; if the selected genre ever vanishes from a refreshed list, the requester falls back to the
 * first chip so the grid's `up` edge always resolves.
 */
@Composable
internal fun MoviesGenreRow(
    genres: List<MovieGenreWithCount>,
    selected: MoviesFilter.Genre?,
    contentInset: PaddingValues,
    genreRowRequester: FocusRequester,
    navigationRequester: FocusRequester,
    tabRowRequester: FocusRequester,
    contentStartRequester: FocusRequester,
    onSelectGenre: (MoviesFilter.Genre) -> Unit,
    onFocusChanged: (String, Boolean) -> Unit,
) {
    val direction = LocalLayoutDirection.current
    val chips = remember(genres) {
        genres.map { genre ->
            val count = integerCountFormat.format(genre.movieCount)
            MoviesGenreChipSpec(
                genre = MoviesFilter.Genre(genre.genreId, genre.genreTag),
                text = "${genre.genreTag} · $count",
                semanticLabel = "${genre.genreTag}, $count ${movieNoun(genre.movieCount)}",
                actionLabel = "Show ${genre.genreTag} movies",
                testTag = "movies_genre_${genre.genreId}",
            )
        }
    }
    // Genre identity is the id: a renamed tag on a refreshed list must not deselect the chip.
    // The anchor must always attach somewhere the row actually renders; the first chip does.
    val anchorId = selected?.id?.takeIf { id -> chips.any { it.genre.id == id } }
        ?: chips.first().genre.id

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
            .testTag("movies_genre_row"),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        chips.forEachIndexed { index, chip ->
            IglooFilterChip(
                text = chip.text,
                selected = chip.genre.id == selected?.id,
                onClick = { onSelectGenre(chip.genre) },
                semanticLabel = chip.semanticLabel,
                actionLabel = chip.actionLabel,
                modifier = Modifier
                    .withRequester(genreRowRequester.takeIf { chip.genre.id == anchorId })
                    .onFocusChanged { onFocusChanged(chip.testTag, it.isFocused) }
                    .focusProperties {
                        // The tab row and the grid are siblings of this scroll surface, so both
                        // vertical edges are wired rather than resolved spatially.
                        up = tabRowRequester
                        down = contentStartRequester
                        if (index == 0) left = navigationRequester
                        if (index == chips.lastIndex) right = FocusRequester.Cancel
                    }
                    .testTag(chip.testTag),
            )
        }
    }
}

private data class MoviesGenreChipSpec(
    val genre: MoviesFilter.Genre,
    val text: String,
    val semanticLabel: String,
    val actionLabel: String,
    val testTag: String,
)
