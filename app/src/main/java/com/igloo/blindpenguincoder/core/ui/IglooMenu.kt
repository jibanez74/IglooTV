package com.igloo.blindpenguincoder.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.rememberOverlayReveal
import com.igloo.blindpenguincoder.core.design.scaled
import kotlin.math.roundToInt

/**
 * One row of an [IglooMenu]. [destructive] rows carry the destructive token pair, and
 * [separatorBefore] draws a silent hairline above the row — the visual grouping the web client
 * gives its own destructive tail.
 */
data class IglooMenuItem(
    val label: String,
    val onSelect: () -> Unit,
    val destructive: Boolean = false,
    val separatorBefore: Boolean = false,
)

/**
 * An anchored menu — a card of focusable rows placed against [anchorBounds], the trigger's
 * bounds in root coordinates. In-tree for the same four reasons as [IglooConfirmDialog]
 * (docs/design-system.md section 9.3), and unscrimmed unlike it: an anchored menu is local
 * chrome, not a page-blocking decision, so the screen stays legible behind it (section 9.1
 * gives the scrim to the rail and the modal only).
 *
 * Host it as the last child of the screen that owns the trigger, fill-size so root coordinates
 * are its own. Focus is trapped: up/down walk the rows, everything else cancels, and the first
 * row takes focus on reveal. The caller restores focus in [onDismiss] — see section 9.3 for why
 * an effect cannot — and must gate its own Back handlers while this is composed.
 */
@Composable
fun IglooMenu(
    title: String,
    items: List<IglooMenuItem>,
    anchorBounds: Rect,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    require(items.isNotEmpty()) { "A menu with no items has nothing to focus." }
    val colors = IglooTheme.colors

    BackHandler(onBack = onDismiss)

    val reveal by rememberOverlayReveal("menuReveal")

    val requesters = remember(items.size) { List(items.size) { FocusRequester() } }
    LaunchedEffect(Unit) { requesters.first().requestFocus() }

    val gap = IglooTheme.spacing.xs
    val safeHorizontal = IglooTheme.layout.safeAreaHorizontal
    val safeVertical = IglooTheme.layout.safeAreaVertical

    Layout(
        content = {
            Column(
                modifier = Modifier
                    .width(MENU_WIDTH.scaled())
                    .graphicsLayer { alpha = reveal }
                    .iglooSurface(
                        radius = IglooTheme.radius.lg,
                        fill = colors.card,
                        border = colors.border,
                    )
                    // Padding on every side so a row's focus ring glows inside the card
                    // instead of being clipped by its surface.
                    .padding(IglooTheme.spacing.xs)
                    .semantics {
                        paneTitle = title
                        isTraversalGroup = true
                    }
                    .testTag("more_menu"),
            ) {
                items.forEachIndexed { index, item ->
                    if (item.separatorBefore) {
                        MenuSeparator()
                    }
                    MenuRow(
                        item = item,
                        modifier = Modifier
                            .focusRequester(requesters[index])
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                                up = requesters.getOrNull(index - 1) ?: FocusRequester.Cancel
                                down = requesters.getOrNull(index + 1) ?: FocusRequester.Cancel
                            }
                            .testTag("more_menu_item_$index"),
                    )
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val card = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gapPx = gap.roundToPx()
        val safeHorizontalPx = safeHorizontal.roundToPx()
        val safeVerticalPx = safeVertical.roundToPx()
        // Right-aligned to the trigger and clamped to the safe area; below the trigger, or above
        // it when the card would breach the bottom inset.
        val maxX = (constraints.maxWidth - safeHorizontalPx - card.width)
            .coerceAtLeast(safeHorizontalPx)
        val x = (anchorBounds.right.roundToInt() - card.width)
            .coerceIn(safeHorizontalPx, maxX)
        val below = anchorBounds.bottom.roundToInt() + gapPx
        val y = if (below + card.height > constraints.maxHeight - safeVerticalPx) {
            (anchorBounds.top.roundToInt() - gapPx - card.height).coerceAtLeast(safeVerticalPx)
        } else {
            below
        }
        layout(constraints.maxWidth, constraints.maxHeight) { card.place(x, y) }
    }
}

/** The NavigationRail row recipe on a card ground: one merged Button node per row. */
@Composable
private fun MenuRow(
    item: IglooMenuItem,
    modifier: Modifier = Modifier,
) {
    val colors = IglooTheme.colors
    var focused by remember { mutableStateOf(false) }
    val fill = when {
        focused && item.destructive -> colors.destructive
        // The rail's transparent-on-card fill would be invisible here; muted is the token
        // ground that separates a focused row from the card it sits on.
        focused -> colors.muted
        else -> Color.Transparent
    }
    val textColor = when {
        focused && item.destructive -> colors.destructiveForeground
        item.destructive -> colors.destructive
        else -> colors.cardForeground
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = IglooTheme.sizes.navItemHeight)
            .focusRing(
                focused = focused,
                radius = IglooTheme.radius.lg,
                fill = fill,
            )
            .then(modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = item.onSelect,
            )
            .clearAndSetSemantics {
                contentDescription = item.label
                role = Role.Button
                onClick(label = item.label) {
                    item.onSelect()
                    true
                }
            }
            .padding(horizontal = IglooTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IglooText(
            text = item.label,
            style = IglooTheme.typography.bodyMedium,
            color = textColor,
            maxLines = 1,
        )
    }
}

/** Drawn-only: the grouping is visual, and a hairline has nothing to announce. */
@Composable
private fun MenuSeparator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = IglooTheme.spacing.xs)
            .height(1.dp)
            .background(IglooTheme.colors.border),
    )
}

/** Wide enough for the longest item at the clamped font scale; the card never wraps a label. */
private val MENU_WIDTH = 280.dp
