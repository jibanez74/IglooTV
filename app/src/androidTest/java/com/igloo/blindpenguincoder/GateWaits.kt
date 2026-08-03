package com.igloo.blindpenguincoder

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText

/**
 * The gate screens appear only once `restore()` has read DataStore, which happens after
 * the first frame. Tests that launch the real activity wait for their screen rather than
 * asserting against whatever the previous test left on the singleton session state.
 */
fun ComposeTestRule.awaitScreen(title: String) {
    waitUntil(WAIT_TIMEOUT_MILLIS) {
        onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
    }
}

private const val WAIT_TIMEOUT_MILLIS = 5_000L
