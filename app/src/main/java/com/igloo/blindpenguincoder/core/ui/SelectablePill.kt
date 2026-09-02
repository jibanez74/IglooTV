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
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import com.igloo.blindpenguincoder.core.design.IglooTheme

/**
 * The body shared by [IglooFilterChip] and [IglooTab] (docs/design-system.md section 9.1): one
 * cleared node whose selection and focus **compose rather than compete** — selected is a
 * `primary` fill that holds while unfocused, and focus is the section 6.1 ring drawn around
 * whatever fill the control already has.
 *
 * Not an [IglooButton] variant, for the reason [IglooFilterChip] records: a selected-state fill
 * on an *unfocused* node is outside the button contract. Not public either — the two wrappers
 * name the two shapes the design system authorizes, and a third caller should get a third named
 * wrapper rather than reach for these parameters directly.
 *
 * [semantics] is the whole cleared node: the caller owns the role, the state, and the action
 * label, because those are the only places the two shapes genuinely differ.
 */
@Composable
internal fun SelectablePill(
    text: String,
    selected: Boolean,
    onPress: () -> Unit,
    radius: Dp,
    minHeight: Dp,
    focusedForeground: Color,
    restForeground: Color,
    modifier: Modifier = Modifier,
    scaleOnFocus: Boolean = true,
    onFocusChanged: (Boolean) -> Unit = {},
    semantics: SemanticsPropertyReceiver.() -> Unit,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    val background = when {
        selected -> colors.primary
        focused -> colors.card.copy(alpha = 0.72f)
        else -> Color.Transparent
    }
    val foreground = when {
        selected -> colors.primaryForeground
        focused -> focusedForeground
        else -> restForeground
    }

    Box(
        modifier = modifier
            .heightIn(min = minHeight)
            .focusRing(
                focused = focused,
                radius = radius,
                fill = background,
                scaleOnFocus = scaleOnFocus,
            )
            .onFocusChanged {
                focused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onPress,
            )
            .clearAndSetSemantics(semantics)
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
