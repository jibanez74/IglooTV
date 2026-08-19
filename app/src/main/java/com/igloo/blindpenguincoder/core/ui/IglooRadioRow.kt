package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.scaled

/**
 * One option row of a radio list on a card ground — the [IglooMenu] row recipe plus a leading
 * radio glyph. One merged RadioButton node per row: the glyph is drawn, not announced; TalkBack
 * speaks the label and the selected state.
 *
 * A null [onSelect] is an inert row: still focusable — an unfocusable row mid-list would punch a
 * hole in a hand-wired up/down chain, the same reasoning as [IglooConfirmDialog]'s pending row —
 * but announced disabled with no action, and its label should carry the reason it cannot be
 * chosen (e.g. an image-based subtitle track).
 */
@Composable
fun IglooRadioRow(
    label: String,
    selected: Boolean,
    onSelect: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    val interactive = onSelect != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = IglooTheme.sizes.navItemHeight)
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.lg,
                // Muted, like the menu row: the transparent resting fill would leave a focused
                // row invisible against the card it sits on.
                fill = if (focused) colors.muted else Color.Transparent,
            )
            .then(modifier)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (interactive) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSelect,
                    )
                } else {
                    Modifier.focusable()
                },
            )
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.RadioButton
                this.selected = selected
                if (interactive) {
                    onClick(label = "Select") {
                        onSelect()
                        true
                    }
                } else {
                    disabled()
                }
            }
            .padding(horizontal = IglooTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
    ) {
        RadioGlyph(selected = selected, dimmed = !interactive)
        IglooText(
            text = label,
            style = IglooTheme.typography.bodyMedium,
            color = if (interactive) colors.cardForeground else colors.mutedForeground,
        )
    }
}

/** Drawn-only: outer ring on the border token, inner dot on primary when selected. */
@Composable
private fun RadioGlyph(selected: Boolean, dimmed: Boolean) {
    val ring = if (dimmed) {
        IglooTheme.colors.mutedForeground
    } else if (selected) {
        IglooTheme.colors.primary
    } else {
        IglooTheme.colors.border
    }
    val dot = if (dimmed) IglooTheme.colors.mutedForeground else IglooTheme.colors.primary
    Canvas(modifier = Modifier.size(RADIO_GLYPH_SIZE.scaled())) {
        val stroke = RADIO_RING_WIDTH.toPx()
        drawCircle(color = ring, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
        if (selected) {
            drawCircle(color = dot, radius = size.minDimension * RADIO_DOT_FRACTION)
        }
    }
}

private val RADIO_GLYPH_SIZE = 20.dp

/** Unscaled, like the focus tokens: a hairline ring stays a hairline at every UI scale. */
private val RADIO_RING_WIDTH = 2.dp
private const val RADIO_DOT_FRACTION = 0.25f
