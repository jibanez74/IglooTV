package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * One announced line about something that already happened — what a gate says after an action
 * the user cannot retry from here. See design-system.md section 10.
 *
 * A live region rather than a focusable node: it is not actionable, so a dead stop between the
 * screen and its controls would be hostile with a remote, but TalkBack still has to speak it.
 * Deliberately not an [IglooInlineError] — that card promises a Retry, and the thing this
 * describes is over.
 */
@Composable
fun IglooNotice(
    text: String,
    modifier: Modifier = Modifier,
) {
    IglooText(
        text = text,
        style = IglooTheme.typography.bodyMedium,
        color = IglooTheme.colors.mutedForeground,
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}
