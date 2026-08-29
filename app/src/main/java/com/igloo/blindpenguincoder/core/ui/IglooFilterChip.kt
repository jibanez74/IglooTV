package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.stateDescription
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * One choice in a row of mutually exclusive filters (docs/design-system.md section 9.1).
 *
 * Selection and focus are different signals and must compose rather than compete: selected is a
 * `primary` fill that holds whether or not the chip is focused, and focus is the section 6.1
 * ring/scale/glow drawn around whatever fill the chip already has. An unselected chip rests as a
 * hairline outline (the ring's resting border) and picks up the Ghost focus fill so its label
 * never sits on bare canvas while focused.
 *
 * Not an [IglooButton] variant on purpose: a selected-state fill on an *unfocused* node is
 * outside the button contract, and threading it through would bloat a primitive four screens
 * already depend on.
 *
 * [semanticLabel] replaces the drawn [text] for TalkBack — "Action · 26" reads better spoken as
 * "Action, 26 movies" — and the selected chip announces "Selected" via state description, the
 * same toggle contract the buttons use.
 */
@Composable
fun IglooFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    semanticLabel: String,
    modifier: Modifier = Modifier,
    actionLabel: String = semanticLabel,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    val background = when {
        selected -> colors.primary
        focused -> colors.card.copy(alpha = 0.72f)
        else -> Color.Transparent
    }
    val foreground = if (selected) colors.primaryForeground else colors.foreground

    Box(
        modifier = modifier
            .heightIn(min = IglooTheme.sizes.controlHeight)
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.lg,
                fill = background,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                contentDescription = semanticLabel
                role = Role.Button
                if (selected) stateDescription = SELECTED_STATE_DESCRIPTION
                onClick(label = actionLabel) {
                    onClick()
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

private const val SELECTED_STATE_DESCRIPTION = "Selected"
