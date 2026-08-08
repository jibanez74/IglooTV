package com.igloo.blindpenguincoder.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooBrandMark
import com.igloo.blindpenguincoder.core.ui.IglooText
import com.igloo.blindpenguincoder.core.ui.iglooSurface

/** Full-bleed auth canvas with a single centered card; no nav chrome. */
@Composable
fun AuthSurface(
    title: String,
    subtitle: String,
    cardWidth: Dp = IglooTheme.layout.authCardWidth,
    content: @Composable () -> Unit,
) {
    val colors = IglooTheme.colors
    val layout = IglooTheme.layout

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(colors.background, colors.muted, colors.background),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // The auth canvas is a full-screen non-shell surface, so it owes the overscan safe area
        // (section 2.5) — not spacing.xl, which is both narrower and shrinks at Compact.
        val availableWidth = (maxWidth - layout.safeAreaHorizontal * 2).coerceAtLeast(0.dp)
        val availableHeight = (maxHeight - layout.safeAreaVertical * 2).coerceAtLeast(0.dp)
        val shape = RoundedCornerShape(IglooTheme.radius.xl)

        Box(
            modifier = Modifier
                .width(minOf(cardWidth, availableWidth))
                .heightIn(max = availableHeight)
                .iglooSurface(radius = IglooTheme.radius.xl, fill = colors.card),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .verticalScroll(rememberScrollState())
                    .padding(IglooTheme.spacing.xl),
                verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(IglooTheme.spacing.md),
                ) {
                    IglooBrandMark()
                    Column {
                        IglooText(
                            text = title,
                            style = IglooTheme.typography.titleMedium,
                            color = colors.cardForeground,
                            modifier = Modifier.semantics { heading() },
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
}
