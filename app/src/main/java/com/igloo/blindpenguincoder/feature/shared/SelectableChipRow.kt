package com.igloo.blindpenguincoder.feature.shared

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooFilterChip
import com.igloo.blindpenguincoder.core.ui.leadingEdgeFade
import com.igloo.blindpenguincoder.core.ui.withRequester

/** One chip of a [SelectableChipRow]: what it draws, what TalkBack hears, and its identity. */
internal data class ChipSpec(
    val key: Long,
    val text: String,
    val semanticLabel: String,
    val actionLabel: String,
    val testTag: String,
)

/**
 * A row of mutually exclusive filter chips that select on press — the Genres tab's genre picker
 * (section 11.4) and the show page's season picker (section 11.4.3).
 *
 * A plain [Row] under [horizontalScroll], deliberately not a `LazyRow`: the lists are bounded
 * (tens, not thousands), and keeping every chip composed keeps every focus requester permanently
 * attached — a neighbour's `up = anchorRequester` can never target a disposed node, and no
 * rail-style entry-key machinery is needed. A focused chip brings itself into view.
 *
 * Focus contract: every chip goes up to [upRequester] and down to [downRequester], the first
 * chip exits left to [leftRequester] and the last chip's right edge is pinned. The selected chip
 * carries [anchorRequester] so the content below lands back on it; if the selected key ever
 * vanishes from a refreshed list, the requester falls back to the first chip so a neighbour's
 * edge always resolves.
 */
@Composable
internal fun SelectableChipRow(
    chips: List<ChipSpec>,
    selectedKey: Long?,
    anchorRequester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester,
    leftRequester: FocusRequester,
    contentInset: PaddingValues,
    rowTag: String,
    onPress: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onChipFocusChanged: (String, Boolean) -> Unit = { _, _ -> },
) {
    val direction = LocalLayoutDirection.current
    // The anchor must always attach somewhere the row actually renders; the first chip does.
    val anchorKey = selectedKey?.takeIf { key -> chips.any { it.key == key } }
        ?: chips.first().key

    val insetStart = contentInset.calculateStartPadding(direction)
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Painted on the surface, not the scrolled content, so a chip scrolled past the
            // gutter dissolves under the spine the way a rail's card does, instead of being cut
            // off at the physical edge (Friends' ten seasons at 4K, 2026-10-19).
            .leadingEdgeFade(insetStart)
            // The gutter sits inside the scroll surface (section 8.3): the modifier order puts
            // the padding in the scrolled content, so the last chip can scroll past the panel
            // edge instead of stopping short of it.
            .horizontalScroll(rememberScrollState())
            .padding(
                start = insetStart,
                end = contentInset.calculateEndPadding(direction),
            )
            .testTag(rowTag),
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        chips.forEachIndexed { index, chip ->
            IglooFilterChip(
                text = chip.text,
                selected = chip.key == selectedKey,
                onClick = { onPress(chip.key) },
                semanticLabel = chip.semanticLabel,
                actionLabel = chip.actionLabel,
                modifier = Modifier
                    .withRequester(anchorRequester.takeIf { chip.key == anchorKey })
                    .onFocusChanged { onChipFocusChanged(chip.testTag, it.isFocused) }
                    .focusProperties {
                        // The rows above and below are siblings of this scroll surface, so both
                        // vertical edges are wired rather than resolved spatially.
                        up = upRequester
                        down = downRequester
                        if (index == 0) left = leftRequester
                        if (index == chips.lastIndex) right = FocusRequester.Cancel
                    }
                    .testTag(chip.testTag),
            )
        }
    }
}
