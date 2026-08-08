package com.igloo.blindpenguincoder

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText

/**
 * The gate screens appear only once `restore()` has read DataStore, which happens after
 * the first frame. Tests that launch the real activity wait for their screen rather than
 * asserting against whatever the previous test left on the singleton session state.
 *
 * The wait requires a match with real bounds, not merely a match. Compose test queries span every
 * hierarchy in the process, and a second `ActivityScenario.launch` in one class can leave the
 * previous activity's nodes attached for a while — still `isPlaced`, but reporting an empty
 * `boundsInWindow`. Matching one of those returns early and the next `assertIsDisplayed()` then
 * fails against a window that is not on screen.
 */
fun ComposeTestRule.awaitScreen(title: String) {
    waitUntil(WAIT_TIMEOUT_MILLIS) {
        onAllNodesWithText(title).fetchSemanticsNodes().any { !it.boundsInWindow.isEmpty }
    }
}

private const val WAIT_TIMEOUT_MILLIS = 5_000L
