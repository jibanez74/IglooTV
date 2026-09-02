package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * A strip of mutually exclusive sections (docs/design-system.md section 9.1): the web client's
 * tab list rendered as one bordered pill on a `muted` ground, holding [IglooTab]s. Sized to its
 * content, never scrolling — a screen with more tabs than fit the panel has too many tabs.
 *
 * Hand-rolled on Foundation rather than `androidx.tv.material3.TabRow` so the tabs wear the
 * same section 6.1 focus treatment as every other control in the app.
 */
@Composable
fun IglooTabRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = IglooTheme.colors
    val shape = RoundedCornerShape(IglooTheme.radius.lg)
    Row(
        modifier = modifier
            .background(colors.muted.copy(alpha = TAB_ROW_GROUND_ALPHA), shape)
            .border(IglooTheme.focus.restWidth, colors.border.copy(alpha = TAB_ROW_BORDER_ALPHA), shape)
            .padding(IglooTheme.spacing.xs)
            .semantics { selectableGroup() },
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * One section in an [IglooTabRow]. **Selects on focus**: d-pad landing on a tab is the switch,
 * the Android TV convention, so flipping between sections is one press per tab with nothing to
 * confirm. A press selects too — that is TalkBack's click action, and the way back after a
 * failed switch reverted the selection out from under a focused tab.
 *
 * Selection and focus compose as on [IglooFilterChip]: selected is the `primary` fill that holds
 * while unfocused, focus is the section 6.1 ring over whatever fill the tab has. No focus scale —
 * the tab sits inside the row's border, which a lifted tab would overlap.
 *
 * Semantics are one cleared node with [Role.Tab] and `selected` set only when true (never
 * false, which would make TalkBack append "not selected" to every other tab).
 */
@Composable
fun IglooTab(
    text: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    semanticLabel: String = text,
    actionLabel: String = "Show $text",
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    val background = when {
        selected -> colors.primary
        focused -> colors.card.copy(alpha = FOCUSED_FILL_ALPHA)
        else -> Color.Transparent
    }
    val foreground = when {
        selected -> colors.primaryForeground
        focused -> colors.foreground
        else -> colors.mutedForeground
    }

    Box(
        modifier = modifier
            // The row's padding on each side brings the strip to controlHeight.
            .heightIn(min = IglooTheme.sizes.controlHeight - IglooTheme.spacing.xs * 2)
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.md,
                fill = background,
                scaleOnFocus = false,
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && !selected) onSelect()
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
            )
            .clearAndSetSemantics {
                contentDescription = semanticLabel
                role = Role.Tab
                if (selected) this.selected = true
                onClick(label = actionLabel) {
                    onSelect()
                    true
                }
            }
            .padding(horizontal = IglooTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        IglooText(
            text = text,
            style = IglooTheme.typography.bodyMedium,
            color = foreground,
            maxLines = 1,
        )
    }
}

private const val TAB_ROW_GROUND_ALPHA = 0.50f
private const val TAB_ROW_BORDER_ALPHA = 0.50f
private const val FOCUSED_FILL_ALPHA = 0.72f
