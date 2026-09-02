package com.igloo.blindpenguincoder.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
 * never sits on bare canvas while focused. [SelectablePill] carries all of that; the chip is the
 * `controlHeight` / radius `lg` / `Role.Button` shape of it.
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
    SelectablePill(
        text = text,
        selected = selected,
        onPress = onClick,
        radius = IglooTheme.radius.lg,
        minHeight = IglooTheme.sizes.controlHeight,
        // A chip's label is body-weight whether or not it is focused; only selection recolors it.
        focusedForeground = colors.foreground,
        restForeground = colors.foreground,
        modifier = modifier,
    ) {
        contentDescription = semanticLabel
        role = Role.Button
        if (selected) stateDescription = SELECTED_STATE_DESCRIPTION
        onClick(label = actionLabel) {
            onClick()
            true
        }
    }
}

private const val SELECTED_STATE_DESCRIPTION = "Selected"
