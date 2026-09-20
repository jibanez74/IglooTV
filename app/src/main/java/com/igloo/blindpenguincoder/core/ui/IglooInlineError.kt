package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * Inline error card. Placed above the fields it refers to, so an open on-screen
 * keyboard cannot cover it.
 *
 * The live region sits on the message text rather than on a merged container:
 * merging would swallow the action button's own semantics node and make it
 * unreachable to TalkBack.
 *
 * [liveRegionMode] is `Assertive` for a form, where the error is the only thing
 * that changed and the user is waiting on it. Hosts that can show several of these
 * at once — the home rails fail independently — pass `Polite` instead, so the
 * announcements queue rather than cutting each other off.
 */
@Composable
fun IglooInlineError(
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    actionSemanticLabel: String = actionText.orEmpty(),
    onAction: (() -> Unit)? = null,
    actionModifier: Modifier = Modifier,
    liveRegionMode: LiveRegionMode = LiveRegionMode.Assertive,
) {
    val colors = IglooTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .iglooSurface(
                radius = IglooTheme.radius.lg,
                fill = colors.destructive.copy(alpha = 0.10f),
                border = colors.destructive.copy(alpha = 0.25f),
            )
            .padding(IglooTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
    ) {
        IglooText(
            text = message,
            style = IglooTheme.typography.bodyMedium,
            color = colors.destructive,
            modifier = Modifier.semantics {
                liveRegion = liveRegionMode
                error(message)
            },
        )
        if (actionText != null && onAction != null) {
            IglooButton(
                text = actionText,
                onClick = onAction,
                variant = IglooButtonVariant.Ghost,
                // Caller's modifier first: a width it sets has to bound the fill, not lose to it.
                modifier = actionModifier.fillMaxWidth(),
                semanticLabel = actionSemanticLabel,
            )
        }
    }
}
