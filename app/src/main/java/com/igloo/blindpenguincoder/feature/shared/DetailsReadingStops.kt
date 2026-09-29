package com.igloo.blindpenguincoder.feature.shared

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusRequester.Companion.Cancel
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.focusRing

/**
 * The reading-stop treatment for a prose section while a screen reader runs: the About panel's
 * focus-target-only look (ring and fill, no scale — there is no action to promise), pinned
 * horizontal edges, and one cleared announcement that folds the section's heading in, because a
 * heading text node is as unreachable to TV TalkBack as the prose under it.
 */
@Composable
internal fun Modifier.readingStopTarget(
    tag: String,
    focused: Boolean,
    requester: FocusRequester,
    upRequester: FocusRequester?,
    downRequester: FocusRequester?,
    onFocusChanged: (Boolean) -> Unit,
    description: String,
    // The panel step by default; a row-shaped target passes the control radius instead.
    radius: Dp = IglooTheme.radius.xl,
): Modifier {
    val colors = IglooTheme.colors
    return this
        .testTag(tag)
        .focusRing(
            focused = focused,
            radius = radius,
            fill = if (focused) colors.card.copy(alpha = 0.72f) else Color.Transparent,
            scaleOnFocus = false,
        )
        .focusRequester(requester)
        .focusProperties {
            up = upRequester ?: Cancel
            down = downRequester ?: Cancel
            left = Cancel
            right = Cancel
        }
        .onFocusChanged { onFocusChanged(it.isFocused) }
        .focusable()
        .clearAndSetSemantics { contentDescription = description }
}
