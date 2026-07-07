package com.igloo.blindpenguincoder.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooText

/** Full-bleed auth canvas with a single centered card; no nav chrome. */
@Composable
fun AuthSurface(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    val colors = IglooTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(colors.background, colors.muted, colors.background),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(IglooTheme.radius.xl))
                .background(colors.card)
                .border(
                    width = 1.dp,
                    color = colors.border,
                    shape = RoundedCornerShape(IglooTheme.radius.xl),
                )
                .padding(IglooTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(IglooTheme.radius.lg))
                        .background(colors.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    IglooText(
                        text = "I",
                        style = IglooTheme.typography.titleMedium,
                        color = colors.primaryForeground,
                    )
                }
                Column {
                    IglooText(
                        text = title,
                        style = IglooTheme.typography.titleMedium,
                        color = colors.cardForeground,
                    )
                    IglooText(
                        text = subtitle,
                        style = IglooTheme.typography.bodyMedium,
                        color = colors.mutedForeground,
                    )
                }
            }
            content()
        }
    }
}
