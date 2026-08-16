package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.recessedPrimary
import com.igloo.blindpenguincoder.core.design.recessedPrimaryContent

enum class IglooButtonVariant { Primary, Ghost, Destructive }

/**
 * [restingFill] and [contentColor] exist for chrome sitting over media (the details hero, the
 * future player controls): a Ghost button's transparent ground and token text are licensed only
 * on a token canvas, so over a backdrop the caller passes the section 3.2 black ground and
 * white content. While [restingFill] is set it also holds through focus — the ring, glow, and
 * scale carry the signal — because the standard `card @ 0.72` focus fill tracks the theme,
 * which over media is exactly what section 3.2 forbids.
 *
 * [stateDescription] and [actionLabel] make a toggle announce properly: "Watched, button,
 * marked as watched — double tap to remove from watched" instead of a bare label.
 *
 * [recessed] steps a `Primary` fill back while a sibling in the same row holds focus, so the
 * focused control is the strongest thing on screen rather than the resting one. It is presentation
 * only — a recessed button is still enabled and still announces nothing about being recessed.
 *
 * [labelVariants] reserves width for every label a toggle can show, so flipping between them is
 * a repaint rather than a relayout shoving the row's siblings. The variants are laid out
 * invisibly in the button's own style — a fixed width would drift the moment the labels are
 * localised — and never reach the semantics tree.
 */
@Composable
fun IglooButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: IglooButtonVariant = IglooButtonVariant.Primary,
    enabled: Boolean = true,
    semanticLabel: String = text,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    restingFill: Color? = null,
    contentColor: Color? = null,
    stateDescription: String? = null,
    actionLabel: String? = null,
    recessed: Boolean = false,
    labelVariants: List<String> = emptyList(),
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    val background = when (variant) {
        IglooButtonVariant.Primary -> when {
            !enabled -> colors.primary.copy(alpha = 0.4f)
            recessed -> colors.recessedPrimary()
            else -> colors.primary
        }
        IglooButtonVariant.Ghost -> when {
            restingFill != null -> restingFill
            focused -> colors.card.copy(alpha = 0.72f)
            else -> Color.Transparent
        }
        IglooButtonVariant.Destructive ->
            if (enabled) colors.destructive else colors.destructive.copy(alpha = 0.4f)
    }
    val foreground = contentColor ?: when (variant) {
        IglooButtonVariant.Primary ->
            if (enabled && recessed) colors.recessedPrimaryContent() else colors.primaryForeground
        IglooButtonVariant.Ghost -> colors.foreground
        IglooButtonVariant.Destructive -> colors.destructiveForeground
    }

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
                enabled = enabled,
                onClick = onClick,
            )
            .clearAndSetSemantics {
                contentDescription = semanticLabel
                role = Role.Button
                if (!enabled) disabled()
                if (stateDescription != null) this.stateDescription = stateDescription
                onClick(label = actionLabel ?: semanticLabel) {
                    if (enabled) onClick()
                    enabled
                }
            }
            .padding(horizontal = IglooTheme.spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.sm),
        ) {
            if (icon != null) {
                Image(
                    imageVector = icon,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(iconTint ?: foreground),
                    modifier = Modifier.size(IglooTheme.icons.md),
                )
            }
            Box(contentAlignment = Alignment.Center) {
                labelVariants.filter { it != text }.forEach { reserved ->
                    IglooText(
                        text = reserved,
                        style = IglooTheme.typography.bodyLarge,
                        color = Color.Transparent,
                        maxLines = 1,
                    )
                }
                IglooText(
                    text = text,
                    style = IglooTheme.typography.bodyLarge,
                    color = foreground,
                    maxLines = 1,
                )
            }
        }
    }
}
