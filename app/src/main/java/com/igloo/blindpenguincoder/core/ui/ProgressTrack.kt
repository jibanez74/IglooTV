package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled

/**
 * The one thin progress bar: a 4dp track of [ground] filled to [fraction] in [fill]. A track
 * flush against an edge — the poster card's, the seek bar's — stays square; a free-standing one
 * in the detail hero passes [rounded].
 */
@Composable
internal fun ProgressTrack(
    fraction: Float,
    ground: Color,
    fill: Color,
    modifier: Modifier = Modifier,
    rounded: Boolean = false,
) {
    val shape = if (rounded) RoundedCornerShape(IglooTheme.radius.pill) else RectangleShape
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp.scaled())
            .clip(shape)
            .background(ground, shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(fill),
        )
    }
}
