package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.rememberOverlayReveal

/**
 * The scrimmed card every in-tree modal sits on (docs/design-system.md section 9.3): one alpha
 * reveal, centred, no wider than the dialog width and no taller than the safe area, announced
 * as a pane titled [paneTitle] and headed by [title]. [modifier] lands on the card, after its
 * semantics, so a test tag names the card itself.
 */
@Composable
internal fun IglooDialogCard(
    title: String,
    modifier: Modifier = Modifier,
    paneTitle: String = title,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reveal by rememberOverlayReveal("dialogReveal")
    IglooScrim(
        modifier = Modifier.graphicsLayer { alpha = reveal },
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints {
            Column(
                modifier = Modifier
                    .width(minOf(IglooTheme.layout.dialogWidth, maxWidth))
                    .heightIn(max = maxHeight - IglooTheme.layout.safeAreaVertical * 2)
                    .iglooSurface(radius = IglooTheme.radius.xl, fill = IglooTheme.colors.card)
                    .padding(IglooTheme.spacing.xl)
                    .semantics {
                        this.paneTitle = paneTitle
                        isTraversalGroup = true
                    }
                    .then(modifier),
                verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
            ) {
                IglooText(
                    text = title,
                    style = IglooTheme.typography.titleMedium,
                    color = IglooTheme.colors.cardForeground,
                    modifier = Modifier.semantics { heading() },
                )
                content()
            }
        }
    }
}
