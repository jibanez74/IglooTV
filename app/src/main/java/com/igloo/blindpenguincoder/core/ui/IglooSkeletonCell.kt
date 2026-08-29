package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled

/**
 * The card-shaped loading placeholder: a poster-proportioned block over two text stubs, sized
 * to the card it stands in for so content arriving does not shift the layout.
 *
 * Shared by the rails ([IglooMediaRail]) and the paged grids, which need cells of identical
 * proportions in a container that measures its own width — pass [width] as [Dp.Unspecified]
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
        modifier = modifier.then(
            if (cardWidth == Dp.Unspecified) Modifier.fillMaxWidth() else Modifier.width(cardWidth),
        ),
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
