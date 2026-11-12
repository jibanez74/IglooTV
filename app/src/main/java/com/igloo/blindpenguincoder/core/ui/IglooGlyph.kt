package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * A decorative glyph in one colour. It carries no description: the control or text it sits in
 * says what it means, so it never reaches TalkBack.
 */
@Composable
internal fun IglooGlyph(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = IglooTheme.icons.md,
) {
    Image(
        imageVector = icon,
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}
