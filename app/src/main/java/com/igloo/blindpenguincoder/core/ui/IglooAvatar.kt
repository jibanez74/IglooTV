package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * The one avatar rendering rule (design-system.md section 11.1.1): remote images are only
 * fetched when the backend gave an absolute URL — `openapi.json` does not define how a
 * relative avatar path resolves — and initials on the primary color cover everything else.
 *
 * No content description: identity is announced by whichever labelled node contains the
 * avatar (a profile tile, the rail's footer), never by the image itself.
 */
@Composable
fun IglooAvatar(
    name: String,
    avatarUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = IglooTheme.typography.titleLarge,
) {
    val colors = IglooTheme.colors
    val frame = modifier
        .size(size)
        .clip(CircleShape)
    val url = avatarUrl?.takeIf {
        it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
    }
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = frame.background(colors.muted),
        )
    } else {
        Box(
            modifier = frame.background(colors.primary),
            contentAlignment = Alignment.Center,
        ) {
            IglooText(
                text = name.take(1).uppercase(),
                style = textStyle,
                color = colors.primaryForeground,
            )
        }
    }
}
