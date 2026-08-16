package com.igloo.blindpenguincoder.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * Pins every direction, for a state whose single focusable has nowhere of its own to go. An
 * overlay's host UI is composed underneath it, so an unpinned edge is an escape hatch onto
 * something the user cannot see (design system section 11.4.1).
 */
fun Modifier.pinnedToScreen(): Modifier = focusProperties {
    left = FocusRequester.Cancel
    right = FocusRequester.Cancel
    up = FocusRequester.Cancel
    down = FocusRequester.Cancel
}
