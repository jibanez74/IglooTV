package com.igloo.blindpenguincoder.feature.shared

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.igloo.blindpenguincoder.core.design.OVER_MEDIA_CONTROL_FILL
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.SectionHeading
import com.igloo.blindpenguincoder.core.ui.focusRing

/**
 * The reading-stop treatment for a prose section while a screen reader runs: the About panel's
 * focus-target-only look (ring and fill, no scale — there is no action to promise), pinned
 * horizontal edges, and one cleared announcement that folds the section's heading in, because a
 * heading text node is as unreachable to TV TalkBack as the prose under it. [overMedia] swaps the
 * focused fill for the section 3.2 black ground, which a backdrop behind the stop requires.
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
    overMedia: Boolean = false,
): Modifier {
    val colors = IglooTheme.colors
    return this
        .testTag(tag)
        .focusRing(
            focused = focused,
            radius = radius,
            fill = when {
                !focused -> Color.Transparent
                overMedia -> OVER_MEDIA_CONTROL_FILL
                else -> colors.card.copy(alpha = 0.72f)
            },
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

/** One facts-panel row; absent values never become rows, so the panel renders what it holds. */
data class FactUi(
    val label: String,
    val value: String,
)

/**
 * The fine print: heading outside the focusable panel, so it lines up with the other section
 * headings despite the panel's inner padding, and the rows as one focus stop and one TalkBack
 * node — five two-word rows would be five announcements of nothing much, and the block carries
 * no action to gate. [description] folds the heading in: TV TalkBack follows input focus, so the
 * heading's own text node above is never reached. Reachable but not actionable — content a
 * d-pad can never scroll to may as well not be on the page.
 */
@Composable
internal fun FactsSection(
    heading: String,
    tag: String,
    facts: List<FactUi>,
    description: String,
    requester: FocusRequester,
    upRequester: FocusRequester?,
    valueMaxLines: Int,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
        SectionHeading(heading)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .readingStopTarget(
                    tag = tag,
                    focused = focused,
                    requester = requester,
                    upRequester = upRequester,
                    downRequester = null,
                    onFocusChanged = { focused = it },
                    description = description,
                )
                .padding(IglooTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
        ) {
            facts.forEach { fact ->
                Row(horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm)) {
                    IglooText(
                        text = "${fact.label}:",
                        style = IglooTheme.typography.label,
                        color = colors.mutedForeground,
                        maxLines = 1,
                    )
                    IglooText(
                        text = fact.value,
                        style = IglooTheme.typography.bodyMedium,
                        color = colors.foreground,
                        maxLines = valueMaxLines,
                    )
                }
            }
        }
    }
}
