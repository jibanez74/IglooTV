package com.igloo.blindpenguincoder

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.igloo.blindpenguincoder.core.design.viewportFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Presses Back on the activity hosting the compose content, as the remote does, then idles. */
internal fun ComposeContentTestRule.pressBack() {
    runOnUiThread {
        val host = ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED)
            .single() as ComponentActivity
        host.onBackPressedDispatcher.onBackPressed()
    }
    waitForIdle()
}

internal tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("The compose test host context is not an Activity")
}

/** Fails the suite unless the device is the 960dp reference viewport its layout assumes. */
@Composable
internal fun AssertReferenceViewport() {
    val widthDp = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density
    assertTrue(
        "test device reports ${widthDp}dp wide; this suite assumes the 960dp reference viewport",
        viewportFactor(widthDp) == 1f,
    )
}

internal fun isHeading() = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

/** A node TalkBack announces on change without interrupting what it is saying. */
internal fun isPoliteLiveRegion() =
    SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

/** What TalkBack offers as "double tap to …" — it must match what the press actually does. */
internal fun SemanticsNodeInteraction.clickActionLabel(): String? =
    fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label

internal fun clickActionLabelled(label: String) = SemanticsMatcher("click action labelled $label") {
    it.config.getOrNull(SemanticsActions.OnClick)?.label == label
}

/** Focuses the node tagged [tag], presses select on it, and waits for what that opens to settle. */
internal fun ComposeTestRule.focusAndPress(tag: String) {
    onNodeWithTag(tag).requestFocus()
    onNodeWithTag(tag)
        .assertIsFocused()
        .performKeyInput { pressKey(Key.DirectionCenter) }
    waitForIdle()
}

/** Displayed, and neither starting above the window nor ending below it. */
internal fun SemanticsNodeInteraction.assertFullyOnscreen(rule: ComposeTestRule): SemanticsNodeInteraction {
    assertIsDisplayed()
    val root = rule.onRoot().getUnclippedBoundsInRoot()
    val bounds = getUnclippedBoundsInRoot()
    assertTrue("Focused control starts above the viewport: $bounds", bounds.top >= root.top)
    assertTrue("Focused control ends below the viewport: $bounds", bounds.bottom <= root.bottom)
    return this
}

internal const val VIEWPORT_TAG = "tv-viewport"

/**
 * The 960x540dp panel a layout suite measures against, tagged [VIEWPORT_TAG]. Not `onRoot()`: the
 * root is the whole test-activity window. IglooTheme derives its viewport factor from the window
 * rather than from this box, so pair it with [AssertReferenceViewport].
 */
@Composable
internal fun ReferenceViewport(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 960.dp, height = 540.dp)
            .testTag(VIEWPORT_TAG),
        content = content,
    )
}

internal fun ComposeTestRule.viewportBounds() =
    onNodeWithTag(VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot

/** Runs a player's auto-hide clock dry, so its chrome rests hidden over the playing video. */
internal fun ComposeTestRule.letChromeHide() {
    mainClock.autoAdvance = false
    mainClock.advanceTimeBy(4_500)
    mainClock.autoAdvance = true
    waitForIdle()
}

/**
 * What TalkBack reads is the platform tree, not Compose's semantics: whatever [collect] gathers
 * from it must be exactly [expected]. Polls, because the platform tree trails Compose's
 * semantics by an accessibility batch.
 */
internal fun <T> ComposeTestRule.assertPlatformTree(
    expected: List<T>,
    timeoutMs: Long = 5_000,
    collect: (root: AccessibilityNodeInfo, into: MutableList<T>) -> Unit,
) {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    var found: List<T>
    do {
        waitForIdle()
        found = buildList { automation.rootInActiveWindow?.let { collect(it, this) } }
        if (found == expected) return
        Thread.sleep(100)
    } while (SystemClock.uptimeMillis() < deadline)
    assertEquals(expected, found)
}
