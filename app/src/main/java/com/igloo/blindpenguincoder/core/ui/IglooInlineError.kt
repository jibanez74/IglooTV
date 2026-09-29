package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

/**
 * A screen's full-screen error: the card centred in the safe area, its one action pinned in every
 * direction. It is the only thing on screen, so Assertive is safe and right — the user just asked
 * for this page and is waiting on it (section 10) — and the pinning matters because the host is
 * still composed underneath: a spatial search that escaped would strand focus on something nobody
 * can see, with no way back to the action. Back belongs to the host.
 */
@Composable
internal fun IglooPinnedError(
    message: String,
    actionText: String,
    actionSemanticLabel: String,
    actionRequester: FocusRequester,
    onAction: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(IglooTheme.layout.safeAreaHorizontal),
        contentAlignment = Alignment.Center,
    ) {
        IglooInlineError(
            message = message,
            actionText = actionText,
            actionSemanticLabel = actionSemanticLabel,
            onAction = onAction,
            actionModifier = Modifier
                .focusRequester(actionRequester)
                .pinnedToScreen(),
            modifier = Modifier.width(IglooTheme.layout.dialogWidth),
        )
    }
}
