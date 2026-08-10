package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * Empty state, minimal variant (docs/design-system.md section 10): a faded icon and one
 * message line. Hosts that need the empty state itself to hold focus wrap it and own the
 * semantics; standalone, the live region announces the emptiness when it appears.
 */
@Composable
fun IglooEmpty(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        Image(
            imageVector = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(colors.mutedForeground.copy(alpha = 0.6f)),
            modifier = Modifier.size(IglooTheme.icons.lg),
        )
        IglooText(
            text = message,
            style = IglooTheme.typography.bodyMedium,
            color = colors.mutedForeground,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
