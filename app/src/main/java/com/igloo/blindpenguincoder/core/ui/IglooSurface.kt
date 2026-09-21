package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * A panel that is chrome, not a control: clipped, filled, and outlined by the hairline.
 *
 * The counterpart to [focusRing], which owns the same three properties for anything focusable.
 * Splitting them keeps `focus.restWidth` as the one hairline in the app while making it obvious
 * at a call site whether a surface can take focus — a non-focusable that reached for `focusRing`
 * would silently carry a ring and a scale it can never show.
 */
@Composable
fun Modifier.iglooSurface(
    radius: Dp,
    fill: Color,
    border: Color = IglooTheme.colors.border,
    borderWidth: Dp = IglooTheme.focus.restWidth,
): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape)
        .background(fill, shape)
        .border(borderWidth, border, shape)
}
