package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled

/**
 * The card-shaped loading placeholder: a poster-proportioned block over two text stubs, sized
 * to the card it stands in for so content arriving does not shift the layout.
 *
 * Shared by the rails ([IglooMediaRail]) and the paged grids, which need cells of identical
 * proportions in a container that measures its own width — pass [cardWidth] as [Dp.Unspecified]
 * there and the cell fills its grid cell instead of taking the rail's fixed card width.
 */
@Composable
internal fun IglooSkeletonCell(
    focused: Boolean,
    cardAspect: Float,
    cardWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    val stubShape = RoundedCornerShape(IglooTheme.radius.sm)
    Column(
        modifier = modifier.cardWidth(cardWidth),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(cardAspect)
                .focusRing(
                    focused = focused,
                    radius = IglooTheme.radius.lg,
                    fill = colors.muted,
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .height(14.dp.scaled())
                .background(colors.muted, stubShape),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(10.dp.scaled())
                .background(colors.muted, stubShape),
        )
    }
}

/**
 * The one skeleton cell that is real to focus and TalkBack: the anchor a loading surface keeps
 * alive so the pane always has somewhere to land, announcing [loadingLabel] politely. The other
 * cells of the surface are [IglooSkeletonTextureCell]s.
 */
@Composable
internal fun IglooSkeletonAnchorCell(
    anchorModifier: Modifier,
    loadingLabel: String,
    cardAspect: Float,
    cardWidth: Dp,
) {
    var focused by remember { mutableStateOf(false) }
    IglooSkeletonCell(
        focused = focused,
        cardAspect = cardAspect,
        cardWidth = cardWidth,
        modifier = anchorModifier
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .semantics {
                contentDescription = loadingLabel
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/** The rest of a skeleton surface: pure texture, invisible to both focus and TalkBack. */
@Composable
internal fun IglooSkeletonTextureCell(
    cardAspect: Float,
    cardWidth: Dp,
    modifier: Modifier = Modifier,
) {
    IglooSkeletonCell(
        focused = false,
        cardAspect = cardAspect,
        cardWidth = cardWidth,
        modifier = modifier.semantics { hideFromAccessibility() },
    )
}
