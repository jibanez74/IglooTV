package com.igloo.blindpenguincoder.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.MEDIA_FALLBACK_TILE
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_SECONDARY
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_TILE_BAND
import com.igloo.blindpenguincoder.core.design.overMedia
import com.igloo.blindpenguincoder.core.design.scaled
import com.igloo.blindpenguincoder.core.ui.ArtworkImage
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.focusRing
import com.igloo.blindpenguincoder.core.ui.formatCount
import com.igloo.blindpenguincoder.core.ui.rememberArtworkState
import com.igloo.blindpenguincoder.core.ui.selectableFilter
import com.igloo.blindpenguincoder.feature.shared.ChipSpec
import com.igloo.blindpenguincoder.feature.shared.SelectableChipRow

/**
 * The Genres tab's picker: one tile per genre, a backdrop from the genre under its name and
 * count (docs/design-system.md section 11.4). Only composed while the Genres tab is selected and
 * has a list to show. The row itself is the shared [SelectableChipRow]; this maps genres onto
 * it, draws each as a [GenreTile] and wires its edges.
 *
 * Focus contract: every tile goes up to the selected tab and down to the pane's content anchor
 * (whatever the grid state put there — entry card, skeleton anchor, error Retry, or the empty
 * box); the first tile exits left to the navigation spine and the last tile's right edge is
 * pinned. The selected tile carries [genreRowRequester] so the grid's first row lands back on
 * it; if the selected genre ever vanishes from a refreshed list, the requester falls back to the
 * first tile so the grid's `up` edge always resolves.
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
    // Genre identity is the id: a renamed tag on a refreshed list must not deselect the tile.
    val chips = remember(kind, genres) {
        genres.map { genre ->
            ChipSpec(
                key = genre.id,
                text = genre.tag,
                semanticLabel = "${genre.tag}, ${genre.countLine(kind)}",
                actionLabel = "Show ${genre.tag} ${kind.plural}",
                testTag = "${kind.tagPrefix}_genre_${genre.id}",
            )
        }
    }
    val genresById = remember(genres) { genres.associateBy { it.id } }
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
            genresById[id]?.let { genre -> onSelectGenre(LibraryFilter.Genre(genre.id, genre.tag)) }
        },
        onChipFocusChanged = onFocusChanged,
    ) { chip, selected, onClick, modifier ->
        val genre = genresById.getValue(chip.key)
        GenreTile(
            name = chip.text,
            countLine = genre.countLine(kind),
            backdropUrl = genre.backdropUrl,
            selected = selected,
            onClick = onClick,
            semanticLabel = chip.semanticLabel,
            actionLabel = chip.actionLabel,
            modifier = modifier,
        )
    }
}

/** `"26 movies"`: what the tile shows under the name and TalkBack hears after it. */
private fun LibraryGenre.countLine(kind: LibraryKind): String =
    "${formatCount(count)} ${kind.noun(count)}"

/**
 * One genre as a media tile (section 11.4): its backdrop fills the tile and the name and count
 * sit in a band along the bottom, opaque behind the words and fading out above them so they keep
 * their contrast on any picture. A genre with no backdrop, or one that fails to load, shows the
 * section 3.2 missing-media navy under the same band, so the row reads alike in both themes —
 * without that tile's glyph: the band already names the tile, and a glyph would sit behind it.
 *
 * Selected, the band turns `primary` and the picture stays above it; a tint over the whole
 * picture would leave the words on whatever the art happened to be. Selection and focus compose
 * as they do on a chip: the band holds while unfocused, the section 6.1 ring arrives with focus.
 *
 * One cleared node, the filter chip's contract: [semanticLabel] for the words, "Selected" as the
 * state, [actionLabel] for the press.
 */
@Composable
private fun GenreTile(
    name: String,
    countLine: String,
    backdropUrl: String?,
    selected: Boolean,
    onClick: () -> Unit,
    semanticLabel: String,
    actionLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    val spacing = IglooTheme.spacing
    var focused by remember { mutableStateOf(false) }
    val art = rememberArtworkState(backdropUrl)
    val band = if (selected) colors.primary else OVER_MEDIA_TILE_BAND
    // The shadow is for words over a picture; on the solid selected band it would only smear.
    val overPicture = !selected

    Box(
        modifier = modifier
            .width(GENRE_TILE_WIDTH.scaled())
            // A minimum, not a fixed height: the tile holds text (section 2.6).
            .heightIn(min = GENRE_TILE_MIN_HEIGHT.scaled())
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.lg,
                fill = MEDIA_FALLBACK_TILE,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                selectableFilter(semanticLabel, selected, actionLabel, onClick)
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        if (art.showsArtwork) ArtworkImage(art, Modifier.matchParentSize())
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(spacing.sm)
                    // The zero stop is the band's own colour: Color.Transparent is black at
                    // zero and would grey the selected band's fade.
                    .background(Brush.verticalGradient(listOf(band.copy(alpha = 0f), band))),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(band)
                    .padding(start = spacing.sm, end = spacing.sm, bottom = spacing.xs),
            ) {
                IglooText(
                    text = name,
                    style = IglooTheme.typography.bodyMedium.overMedia(overPicture),
                    color = if (selected) colors.primaryForeground else Color.White,
                    maxLines = 1,
                )
                IglooText(
                    text = countLine,
                    style = IglooTheme.typography.label.overMedia(overPicture),
                    color = if (selected) {
                        colors.primaryForeground.copy(alpha = SELECTED_COUNT_ALPHA)
                    } else {
                        OVER_MEDIA_SECONDARY
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** Twice as wide as tall: a backdrop's crop that still shows what the picture is of. */
private val GENRE_TILE_WIDTH = 176.dp
private val GENRE_TILE_MIN_HEIGHT = 88.dp

/** The count steps back from the name on the selected band, as it does over the picture. */
private const val SELECTED_COUNT_ALPHA = 0.85f
