package com.igloo.blindpenguincoder.feature.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import com.igloo.blindpenguincoder.core.ui.formatCount
import com.igloo.blindpenguincoder.feature.shared.ChipSpec
import com.igloo.blindpenguincoder.feature.shared.SelectableChipRow

/**
 * The Genres tab's picker: one chip per genre, with counts (docs/design-system.md section
 * 11.4). Only composed while the Genres tab is selected and has a list to show. The row itself
 * is the shared [SelectableChipRow]; this maps genres onto it and wires its edges.
 *
 * Focus contract: every chip goes up to the selected tab and down to the pane's content anchor
 * (whatever the grid state put there — entry card, skeleton anchor, error Retry, or the empty
 * box); the first chip exits left to the navigation spine and the last chip's right edge is
 * pinned. The selected chip carries [genreRowRequester] so the grid's first row lands back on
 * it; if the selected genre ever vanishes from a refreshed list, the requester falls back to the
 * first chip so the grid's `up` edge always resolves.
 */
@Composable
internal fun LibraryGenreRow(
    kind: LibraryKind,
    genres: List<LibraryGenre>,
    selected: LibraryFilter.Genre?,
    contentInset: PaddingValues,
    genreRowRequester: FocusRequester,
    navigationRequester: FocusRequester,
    tabRowRequester: FocusRequester,
    contentStartRequester: FocusRequester,
    onSelectGenre: (LibraryFilter.Genre) -> Unit,
    onFocusChanged: (String, Boolean) -> Unit,
) {
    // Genre identity is the id: a renamed tag on a refreshed list must not deselect the chip.
    val chips = remember(kind, genres) {
        genres.map { genre ->
            val count = formatCount(genre.count)
            ChipSpec(
                key = genre.id,
                text = "${genre.tag} · $count",
                semanticLabel = "${genre.tag}, $count ${kind.noun(genre.count)}",
                actionLabel = "Show ${genre.tag} ${kind.plural}",
                testTag = "${kind.tagPrefix}_genre_${genre.id}",
            )
        }
    }
    SelectableChipRow(
        chips = chips,
        selectedKey = selected?.id,
        anchorRequester = genreRowRequester,
        upRequester = tabRowRequester,
        downRequester = contentStartRequester,
        leftRequester = navigationRequester,
        contentInset = contentInset,
        rowTag = "${kind.tagPrefix}_genre_row",
        onPress = { id ->
            genres.firstOrNull { it.id == id }
                ?.let { genre -> onSelectGenre(LibraryFilter.Genre(genre.id, genre.tag)) }
        },
        onChipFocusChanged = onFocusChanged,
    )
}
