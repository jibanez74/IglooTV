package com.igloo.blindpenguincoder.feature.shared

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooInlineError

/**
 * A failed progress save's card: why, and a Retry that re-sends the snapshot. Polite, because the
 * surface around it keeps working and nothing waits on it. [modifier] places the card;
 * [retryModifier] wires the button's focus and tag.
 */
@Composable
internal fun ProgressSyncRetry(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retryModifier: Modifier = Modifier,
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

/** The details headers' retry stop, with pinned sides and explicit vertical focus targets. */
@Composable
internal fun DetailsProgressSyncRetry(
    message: String,
    onRetry: () -> Unit,
    requester: FocusRequester,
    upRequester: FocusRequester?,
    downRequester: FocusRequester?,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ProgressSyncRetry(
        message = message,
        onRetry = onRetry,
        retryModifier = Modifier
            .focusRequester(requester)
            .focusProperties {
                up = upRequester ?: Cancel
                down = downRequester ?: Cancel
                left = Cancel
                right = Cancel
            }
            .onFocusChanged { onFocusChanged(it.isFocused) }
            .testTag("details_progress_retry"),
        modifier = modifier.testTag("details_progress_error"),
    )
}
