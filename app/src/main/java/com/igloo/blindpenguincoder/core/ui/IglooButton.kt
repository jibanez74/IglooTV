package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme

enum class IglooButtonVariant { Primary, Ghost }

@Composable
fun IglooButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: IglooButtonVariant = IglooButtonVariant.Primary,
    enabled: Boolean = true,
    semanticLabel: String = text,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(IglooTheme.radius.lg)
    val background = when (variant) {
        IglooButtonVariant.Primary ->
            if (enabled) colors.primary else colors.primary.copy(alpha = 0.4f)
        IglooButtonVariant.Ghost ->
            if (focused) colors.card.copy(alpha = 0.72f) else Color.Transparent
    }
    val foreground = when (variant) {
        IglooButtonVariant.Primary -> colors.primaryForeground
        IglooButtonVariant.Ghost -> colors.foreground
    }

    Box(
        modifier = modifier
            .height(52.dp)
            .clip(shape)
            .background(background)
            .focusRing(focused = focused, radius = IglooTheme.radius.lg)
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
                onClick(label = semanticLabel) {
                    if (enabled) onClick()
                    enabled
                }
            }
            .padding(horizontal = IglooTheme.spacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        IglooText(
            text = text,
            style = IglooTheme.typography.bodyLarge,
            color = foreground,
            maxLines = 1,
        )
    }
}
