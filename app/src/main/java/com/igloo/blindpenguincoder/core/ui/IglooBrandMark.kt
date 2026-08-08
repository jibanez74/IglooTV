package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * The Igloo "I" tile. Hidden from accessibility services — the glyph is a mark, not a word, and
 * TalkBack would otherwise announce a bare letter ahead of whatever it sits beside.
 */
@Composable
fun IglooBrandMark(
    modifier: Modifier = Modifier,
    size: Dp = IglooTheme.sizes.brandTile,
    textStyle: TextStyle = IglooTheme.typography.titleMedium,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(IglooTheme.radius.lg))
            .background(IglooTheme.colors.primary)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        IglooText(
            text = "I",
            style = textStyle,
            color = IglooTheme.colors.primaryForeground,
        )
    }
}
