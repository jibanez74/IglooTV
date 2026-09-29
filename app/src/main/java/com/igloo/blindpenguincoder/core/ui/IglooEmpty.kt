package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * Empty state, minimal variant (docs/design-system.md section 10): a faded icon and one
 * message line. Visual only — [IglooFocusableEmpty] owns the semantics.
 */
@Composable
private fun IglooEmpty(
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
        )
    }
}

/**
 * [IglooEmpty] as a pane's focus anchor. Focusable deliberately: when the empty state is the
 * pane's only content, an unfocusable node would leave the pane with no anchor and break the
 * shell's focus model. One TalkBack node, announcing [message] politely.
 */
@Composable
internal fun IglooFocusableEmpty(
    anchorModifier: Modifier,
    icon: ImageVector,
    message: String,
    contentPadding: Dp,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .focusRing(focused = focused, radius = IglooTheme.radius.lg)
            .then(anchorModifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clearAndSetSemantics {
                contentDescription = message
                liveRegion = LiveRegionMode.Polite
            }
            .padding(contentPadding),
        contentAlignment = contentAlignment,
    ) {
        IglooEmpty(icon = icon, message = message)
    }
}
