package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme

@Composable
fun Modifier.focusRing(
    focused: Boolean,
    radius: Dp,
    hasError: Boolean = false,
): Modifier {
    val colors = IglooTheme.colors
    val focus = IglooTheme.focus
    return border(
        width = if (focused) focus.ringWidth else focus.restWidth,
        // Focus stays glacier everywhere; the destructive tint only marks an
        // errored control that does not currently hold focus.
        color = when {
            focused -> colors.ring
            hasError -> colors.destructive
            else -> colors.border
        },
        shape = RoundedCornerShape(radius),
    )
}
