package com.igloo.blindpenguincoder.feature.shared

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import com.igloo.blindpenguincoder.core.design.IglooTheme

/** What a pane's tab strip draws, speaks and is addressed by for one section — one lookup, not three. */
data class TabPresentation(
    val label: String,
    val semanticLabel: String,
    /** Doubles as the focus-ownership key ([PaneFocusOwnership]) and the test tag. */
    val key: String,
)

/**
 * The pane's horizontal gutter belongs inside the scroll surface (docs/design-system.md section
 * 8.3), but its vertical values do not: the surface needs room of its own so the focus scale
 * and glow are not cross-axis clipped at the first row, and the safe area below so the last
 * row clears overscan.
 */
@Composable
fun PaddingValues.asScrollPadding(): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction),
        end = calculateEndPadding(direction),
        top = IglooTheme.spacing.md,
        bottom = IglooTheme.layout.safeAreaVertical,
    )
}

const val REFRESH_LABEL = "Refresh"
const val REFRESHING_LABEL = "Refreshing…"

/** How close to the end a grid gets before it asks for the next page, in rows. */
const val GRID_PREFETCH_ROWS = 2

/** Rows of card geometry a grid skeleton draws. */
const val SKELETON_ROWS = 3
