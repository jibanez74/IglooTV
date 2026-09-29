package com.igloo.blindpenguincoder.feature.shared

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooInlineError

/**
 * A failed progress save's card: why, and a Retry that re-sends the snapshot. Polite, because the
 * surface around it keeps working and nothing waits on it. [retryModifier] wires the button's
 * focus and tag; [modifier] places the card.
 */
@Composable
internal fun ProgressSyncRetry(
    message: String,
    onRetry: () -> Unit,
    retryModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    IglooInlineError(
        message = message,
        actionText = "Retry",
        actionSemanticLabel = "Retry saving playback progress",
        onAction = onRetry,
        actionModifier = retryModifier,
        liveRegionMode = LiveRegionMode.Polite,
        modifier = modifier.width(IglooTheme.layout.dialogWidth),
    )
}
