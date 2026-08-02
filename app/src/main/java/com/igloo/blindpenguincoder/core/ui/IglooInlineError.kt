package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * Inline error card. Placed above the fields it refers to, so an open on-screen
 * keyboard cannot cover it.
 *
 * The live region sits on the message text rather than on a merged container:
 * merging would swallow the action button's own semantics node and make it
 * unreachable to TalkBack.
 */
@Composable
fun IglooInlineError(
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    actionSemanticLabel: String = actionText.orEmpty(),
    onAction: (() -> Unit)? = null,
) {
    val colors = IglooTheme.colors
    val shape = RoundedCornerShape(IglooTheme.radius.lg)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.destructive.copy(alpha = 0.10f))
            .border(width = 1.dp, color = colors.destructive.copy(alpha = 0.25f), shape = shape)
            .padding(IglooTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        IglooText(
            text = message,
            style = IglooTheme.typography.bodyMedium,
            color = colors.destructive,
            modifier = Modifier.semantics {
                liveRegion = LiveRegionMode.Assertive
                error(message)
            },
        )
        if (actionText != null && onAction != null) {
            IglooButton(
                text = actionText,
                onClick = onAction,
                variant = IglooButtonVariant.Ghost,
                modifier = Modifier.fillMaxWidth(),
                semanticLabel = actionSemanticLabel,
            )
        }
    }
}
