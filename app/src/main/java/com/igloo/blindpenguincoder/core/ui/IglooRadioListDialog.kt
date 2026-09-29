package com.igloo.blindpenguincoder.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.rememberOverlayReveal

/**
 * A scrimmed card of [IglooRadioRow]s over one flat, scrolling list, with a pinned Done: the
 * Playback Settings dialog and the player's track and chapter menus. The [IglooConfirmDialog]
 * recipe throughout (section 9.3) — in-tree, one alpha reveal, no exit animation, Back and Done
 * dismiss, focus trapped, and the invoker restores focus in [onDismiss].
 *
 * [rows] draws the rows in list order, giving row `n` the `rowFocus(n)` modifier: up and down
 * walk the list and continue onto Done, and every other direction cancels so focus search
 * terminates inside the card instead of falling through the scrim. Entry focus lands on row
 * [entryIndex]. [footer] sits between the list and Done.
 */
@Composable
internal fun IglooRadioListDialog(
    title: String,
    rowCount: Int,
    entryIndex: Int,
    onDismiss: () -> Unit,
    testTag: String,
    doneTestTag: String,
    paneTitle: String = title,
    footer: @Composable ColumnScope.() -> Unit = {},
    rows: @Composable ColumnScope.(rowFocus: Modifier.(index: Int) -> Modifier) -> Unit,
) {
    val colors = IglooTheme.colors

    // The host gates its own Back handlers while this is composed (section 9.3).
    BackHandler(onBack = onDismiss)

    val reveal by rememberOverlayReveal("radioListReveal")

    val rowRequesters = remember(rowCount) { List(rowCount) { FocusRequester() } }
    val doneRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { rowRequesters.getOrNull(entryIndex)?.requestFocus() }

    val rowFocus: Modifier.(Int) -> Modifier = { index ->
        focusRequester(rowRequesters[index]).focusProperties {
            left = FocusRequester.Cancel
            right = FocusRequester.Cancel
            up = rowRequesters.getOrNull(index - 1) ?: FocusRequester.Cancel
            down = rowRequesters.getOrNull(index + 1) ?: doneRequester
        }
    }

    IglooScrim(
        modifier = Modifier.graphicsLayer { alpha = reveal },
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints {
            val cardWidth = minOf(IglooTheme.layout.dialogWidth, maxWidth)
            val cardMaxHeight = maxHeight - IglooTheme.layout.safeAreaVertical * 2
            Column(
                modifier = Modifier
                    .width(cardWidth)
                    .heightIn(max = cardMaxHeight)
                    .iglooSurface(radius = IglooTheme.radius.xl, fill = colors.card)
                    .padding(IglooTheme.spacing.xl)
                    .semantics {
                        this.paneTitle = paneTitle
                        isTraversalGroup = true
                    }
                    .testTag(testTag),
                verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.lg),
            ) {
                IglooText(
                    text = title,
                    style = IglooTheme.typography.titleMedium,
                    color = colors.cardForeground,
                    modifier = Modifier.semantics { heading() },
                )

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(IglooTheme.spacing.xs),
                ) {
                    rows(rowFocus)
                }

                footer()

                IglooButton(
                    text = "Done",
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(doneRequester)
                        .focusProperties {
                            left = FocusRequester.Cancel
                            right = FocusRequester.Cancel
                            down = FocusRequester.Cancel
                            up = rowRequesters.lastOrNull() ?: FocusRequester.Cancel
                        }
                        .testTag(doneTestTag),
                )
            }
        }
    }
}
