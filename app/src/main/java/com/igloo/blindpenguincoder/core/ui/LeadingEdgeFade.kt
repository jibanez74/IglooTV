package com.igloo.blindpenguincoder.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * Dissolves whatever a horizontal scroll surface — a rail, or the season and genre chip rows —
 * carries past its start inset into the canvas (section 8.3). The surface reaches the logical
 * start edge on purpose; under the shell the spine rests there as a translucent scrim, so a card
 * scrolled past the gutter would otherwise show through between its icons, and on a full-screen
 * page it would be cut off at the physical edge. The fade is opaque across the first two thirds
 * of the start inset (the icon strip and a little past it — at half, the card ending under the
 * strip's edge still read as a stripe beside it on a Shield) and reaches zero exactly at the
 * first item's resting edge, so a resting item — and a focused one, whose 1.05x scale overhangs
 * by a few dp — is untouched. It is a paint over the surface, not a clip or a parent padding:
 * both would turn the inset back into a gutter, which section 8.3 forbids.
 */
@Composable
internal fun Modifier.leadingEdgeFade(insetStart: Dp): Modifier {
    if (insetStart <= 0.dp) return this
    val canvas = IglooTheme.colors.background
    val direction = LocalLayoutDirection.current
    return drawWithCache {
        val width = insetStart.toPx()
        val isRtl = direction == LayoutDirection.Rtl
        val left = if (isRtl) size.width - width else 0f
        val brush = Brush.horizontalGradient(
            0f to canvas,
            FADE_OPAQUE_FRACTION to canvas,
            1f to canvas.copy(alpha = 0f),
            startX = if (isRtl) size.width else 0f,
            endX = if (isRtl) left else width,
        )
        val fadeSize = Size(width, size.height)
        onDrawWithContent {
            drawContent()
            drawRect(brush, topLeft = Offset(left, 0f), size = fadeSize)
        }
    }
}

/** How much of the start inset the leading-edge fade covers at full opacity. */
private const val FADE_OPAQUE_FRACTION = 0.65f
