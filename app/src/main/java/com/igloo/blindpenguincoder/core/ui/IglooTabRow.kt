package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
 * same section 6.1 focus treatment as every other control in the app, and built without
 * [Modifier.iglooSurface] — which every other panel uses — because its `clip` would cut the
 * focused tab's glow at the row's bounds.
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
            .background(colors.muted.copy(alpha = 0.50f), shape)
            .border(IglooTheme.focus.restWidth, colors.border.copy(alpha = 0.50f), shape)
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
 * confirm. [onPress] selects too — that is TalkBack's click action, and the way back after a
 * failed switch reverted the selection out from under a focused tab. It defaults to [onSelect]
 * and is worth splitting only when a press must be treated as more deliberate than a pass-over.
 *
 * Selection and focus compose as on [IglooFilterChip], via the shared [SelectablePill]: selected
 * is the `primary` fill that holds while unfocused, focus is the section 6.1 ring over whatever
 * fill the tab has. No focus scale — the tab sits inside the row's border, which a lifted tab
 * would overlap. The focused-but-unselected treatment exists for exactly one moment: a failed
 * switch that reverted the selection under a tab the user is still standing on.
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
    onPress: () -> Unit = onSelect,
    semanticLabel: String = text,
    actionLabel: String = "Show $text",
) {
    val colors = IglooTheme.colors
    SelectablePill(
        text = text,
        selected = selected,
        onPress = onPress,
        radius = IglooTheme.radius.md,
        // The row's padding on each side brings the strip to controlHeight.
        minHeight = IglooTheme.sizes.controlHeight - IglooTheme.spacing.xs * 2,
        focusedForeground = colors.foreground,
        restForeground = colors.mutedForeground,
        modifier = modifier,
        scaleOnFocus = false,
        onFocusChanged = { focused -> if (focused && !selected) onSelect() },
    ) {
        contentDescription = semanticLabel
        role = Role.Tab
        if (selected) this.selected = true
        onClick(label = actionLabel) {
            onPress()
            true
        }
    }
}
