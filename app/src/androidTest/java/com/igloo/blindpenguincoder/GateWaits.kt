package com.igloo.blindpenguincoder

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry

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

/**
 * Waits on a control instead of the screen title, for screens whose resting state may scroll
 * the header out of the viewport — a banner over the auth card does exactly that (design
 * system section 11.1.3), taking the title's window bounds with it. The same real-bounds
 * requirement as [awaitScreen] applies, for the same stale-hierarchy reason.
 */
fun ComposeTestRule.awaitContentDescription(description: String) {
    waitUntil(WAIT_TIMEOUT_MILLIS) {
        onAllNodesWithContentDescription(description)
            .fetchSemanticsNodes()
            .any { !it.boundsInWindow.isEmpty }
    }
}

/**
 * Waits on a tag, for nodes whose content description is not a fixed string — the pairing
 * characters carry a one-shot arrival announcement on the first of them. Same real-bounds
 * requirement as [awaitScreen].
 */
fun ComposeTestRule.awaitTestTag(tag: String) {
    waitUntil(WAIT_TIMEOUT_MILLIS) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().any { !it.boundsInWindow.isEmpty }
    }
}

/**
 * Whether a screen reader is listening on this device, read exactly as the app reads it. The
 * gate screens render different trees either way, so tests that launch the real activity have
 * to assert the contract the device is actually under — the Shield runs with TalkBack on.
 */
fun spokenFeedbackEnabled(): Boolean {
    val manager = InstrumentationRegistry.getInstrumentation()
        .targetContext
        .getSystemService(AccessibilityManager::class.java)
    return manager?.isEnabled == true &&
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
            .isNotEmpty()
}

private const val WAIT_TIMEOUT_MILLIS = 5_000L
