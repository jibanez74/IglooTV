package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.igloo.blindpenguincoder.core.design.IglooTheme

@Composable
fun IglooText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    softWrap: Boolean = true,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
        onTextLayout = onTextLayout,
    )
}

/** The one section heading style: every detail-screen section and every Home rail. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    IglooText(
        text = text,
        style = IglooTheme.typography.titleMedium,
        color = IglooTheme.colors.foreground,
        modifier = modifier.semantics { heading() },
    )
}
