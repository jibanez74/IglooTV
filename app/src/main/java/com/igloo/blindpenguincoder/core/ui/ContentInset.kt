package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection

/**
 * The pane's gutter applied as padding on the sides only (design-system section 8.3): what a
 * heading, a text plate or a non-scrolling state owes to the overscan inset, while its vertical
 * values stay with the surface that scrolls.
 */
@Composable
fun Modifier.insetHorizontally(contentInset: PaddingValues): Modifier {
    val direction = LocalLayoutDirection.current
    return padding(
        start = contentInset.calculateStartPadding(direction),
        end = contentInset.calculateEndPadding(direction),
    )
}
