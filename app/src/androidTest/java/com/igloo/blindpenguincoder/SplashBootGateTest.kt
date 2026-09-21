package com.igloo.blindpenguincoder

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.feature.boot.SPLASH_LABEL
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The splash is a boot moment: it must appear at launch and must be gone once a screen has taken
 * focus. [AnimationScaleRule] keeps the welcome screen's ambient backdrop from holding the Compose
 * clock busy while these assertions wait.
 */
@RunWith(AndroidJUnit4::class)
class SplashBootGateTest {

    // Explicit order: lower is outermost, so animations are off before the compose rule sets up.
    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    /**
     * Scoped to what a semantics query can actually see: the splash announces itself at launch and
     * has gone silent by the time the screen beneath holds focus. It deliberately does not claim
     * the splash is no longer *drawn* — `announce` drops at the start of the fade, so the node
     * disappears while the pixels are still there. The visual hand-off is verified on-device.
     */
    @Test
    fun theSplashAnnouncesAtLaunchThenGoesSilentWithTheScreenBeneathLive() {
        clearStoredSetup()

        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitUntil(WAIT_MILLIS) { splashNodeCount() == 1 }

            // The hold outlasts the DataStore read, so the welcome arrives underneath the splash
            // and is already focusable by the time the splash leaves.
            composeRule.awaitScreen("Welcome to Igloo")

            composeRule.waitUntil(WAIT_MILLIS) { splashNodeCount() == 0 }
            composeRule.onAllNodesWithContentDescription(SPLASH_LABEL).assertCountEquals(0)

            // The point of handing off silently: TalkBack lands on the control that now has focus.
            composeRule.onNodeWithContentDescription("Get started").assertIsFocused()
        }
    }

    private fun splashNodeCount() = composeRule
        .onAllNodesWithContentDescription(SPLASH_LABEL)
        .fetchSemanticsNodes()
        .size

    private fun clearStoredSetup() {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as IglooApplication
        runBlocking {
            app.container.serverSettingsStore.clear()
            app.container.profileRepository.clearAll()
        }
        app.container.serverUrlProvider.set(null)
    }

    private companion object {
        const val WAIT_MILLIS = 5_000L
    }
}
