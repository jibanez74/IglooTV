package com.igloo.blindpenguincoder

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.igloo.blindpenguincoder.core.design.viewportFactor
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

/** What TalkBack offers as "double tap to …" — it must match what the press actually does. */
internal fun SemanticsNodeInteraction.clickActionLabel(): String? =
    fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label

internal fun clickActionLabelled(label: String) = SemanticsMatcher("click action labelled $label") {
    it.config.getOrNull(SemanticsActions.OnClick)?.label == label
}
