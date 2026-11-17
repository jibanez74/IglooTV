package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
    /** Trailing muted text (a timecode, a count) — drawn only, never announced on its own. */
    detail: String? = null,
    /** Spoken sentence when the drawn [label] is not it; same contract as [IglooButton]. */
    semanticLabel: String? = null,
) {
    ChoiceRow(
        label = label,
        onPress = onSelect,
        actionLabel = "Select",
        modifier = modifier,
        detail = detail,
        glyph = { RadioGlyph(selected = selected, dimmed = onSelect == null) },
        leading = null,
    ) {
        contentDescription = semanticLabel ?: label
        role = Role.RadioButton
        this.selected = selected
    }
}

/**
 * One row of a checklist on a card ground — the Watch Together dialog's invitees: [IglooRadioRow]'s
 * row with a check glyph, and an optional [leading] slot (an avatar) between the glyph and the
 * label. One cleared Checkbox node speaking the label and whether it is checked, its press offered
 * as [actionLabel].
 */
@Composable
fun IglooCheckRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    actionLabel: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    ChoiceRow(
        label = label,
        onPress = onToggle,
        actionLabel = actionLabel,
        modifier = modifier,
        detail = null,
        glyph = { CheckGlyph(checked) },
        leading = leading,
    ) {
        contentDescription = label
        role = Role.Checkbox
        toggleableState = ToggleableState(checked)
    }
}

/**
 * The row both lists share: the menu row's height, fill and focus, a drawn [glyph], an optional
 * [leading] slot, the label and an optional trailing [detail]; one cleared node whose own
 * [semantics] say what kind of choice it is. A null [onPress] is the inert row.
 */
@Composable
private fun ChoiceRow(
    label: String,
    onPress: (() -> Unit)?,
    actionLabel: String,
    modifier: Modifier,
    detail: String?,
    glyph: @Composable () -> Unit,
    leading: (@Composable () -> Unit)?,
    semantics: SemanticsPropertyReceiver.() -> Unit,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    val interactive = onPress != null

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
                        interactionSource = null,
                        indication = null,
                        onClick = onPress,
                    )
                } else {
                    Modifier.focusable()
                },
            )
            .clearAndSetSemantics {
                semantics()
                if (interactive) {
                    onClick(label = actionLabel) {
                        onPress()
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
        glyph()
        leading?.invoke()
        IglooText(
            text = label,
            style = IglooTheme.typography.bodyMedium,
            color = if (interactive) colors.cardForeground else colors.mutedForeground,
            modifier = if (detail != null) Modifier.weight(1f) else Modifier,
        )
        if (detail != null) {
            IglooText(
                text = detail,
                style = IglooTheme.typography.bodyMedium,
                color = colors.mutedForeground,
            )
        }
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

/** Drawn-only: a rounded square on the border token, filled primary and ticked when checked. */
@Composable
private fun CheckGlyph(checked: Boolean) {
    val border = IglooTheme.colors.border
    val fill = IglooTheme.colors.primary
    val tick = IglooTheme.colors.primaryForeground
    Canvas(modifier = Modifier.size(RADIO_GLYPH_SIZE.scaled())) {
        val stroke = RADIO_RING_WIDTH.toPx()
        val corner = CornerRadius(size.minDimension * CHECK_CORNER_FRACTION)
        if (checked) {
            drawRoundRect(color = fill, cornerRadius = corner)
            val path = Path().apply {
                moveTo(size.width * 0.25f, size.height * 0.52f)
                lineTo(size.width * 0.43f, size.height * 0.70f)
                lineTo(size.width * 0.76f, size.height * 0.32f)
            }
            drawPath(path, color = tick, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            val inset = stroke / 2
            drawRoundRect(
                color = border,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = corner,
                style = Stroke(stroke),
            )
        }
    }
}

private val RADIO_GLYPH_SIZE = 20.dp

/** Unscaled, like the focus tokens: a hairline ring stays a hairline at every UI scale. */
private val RADIO_RING_WIDTH = 2.dp
private const val RADIO_DOT_FRACTION = 0.25f
private const val CHECK_CORNER_FRACTION = 0.2f
