package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme

@Composable
fun Modifier.focusRing(
    focused: Boolean,
    radius: Dp,
): Modifier {
    val colors = IglooTheme.colors
    return border(
        width = if (focused) 3.dp else 1.dp,
        color = if (focused) colors.ring else colors.border,
        shape = RoundedCornerShape(radius),
    )
}
