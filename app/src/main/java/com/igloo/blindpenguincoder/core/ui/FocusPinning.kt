package com.igloo.blindpenguincoder.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * Pins Left and Right, for a focusable in a vertical run: [up] and [down] name its neighbours,
 * and a null one is pinned too. An overlay's host UI is composed underneath it, so an unpinned
 * edge is an escape hatch onto something the user cannot see (design system section 11.4.1).
 */
fun Modifier.pinnedSideways(up: FocusRequester? = null, down: FocusRequester? = null): Modifier =
    focusProperties {
        left = FocusRequester.Cancel
        right = FocusRequester.Cancel
        this.up = up ?: FocusRequester.Cancel
        this.down = down ?: FocusRequester.Cancel
    }

/** Pins every direction, for a state whose single focusable has nowhere of its own to go. */
fun Modifier.pinnedToScreen(): Modifier = pinnedSideways()
