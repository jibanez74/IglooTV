package com.igloo.blindpenguincoder.feature.boot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.LocalIglooReducedMotion
import com.igloo.blindpenguincoder.core.design.UiScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The splash is one accessibility node by design, so what is worth asserting here is what it
 * says and when it stops saying it — the geometry is a centered lockup with no fit budget to
 * blow, unlike the welcome screen.
 */
@RunWith(AndroidJUnit4::class)
class SplashScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var announce by mutableStateOf(true)
    private var uiScale by mutableStateOf(UiScale.Standard)

    @Test
    fun theWholeScreenIsOneNodeThatNamesTheApp() {
        setSplashContent()

        composeRule.onNodeWithContentDescription(SPLASH_LABEL).assertIsDisplayed()

        // The mark and the two halves of the wordmark are inside it, not beside it.
        composeRule.onAllNodesWithText("I").assertCountEquals(0)
        composeRule.onAllNodesWithText(SPLASH_WORDMARK).assertCountEquals(0)
        composeRule.onAllNodesWithText(SPLASH_WORDMARK_SUFFIX).assertCountEquals(0)
    }

    @Test
    fun itGoesSilentWhileItHandsOff() {
        setSplashContent()

        composeRule.runOnUiThread { announce = false }
        composeRule.waitForIdle()

        // Still drawn for the length of the fade, but no longer something TalkBack can land on
        // over the screen that has taken focus.
        composeRule.onAllNodesWithContentDescription(SPLASH_LABEL).assertCountEquals(0)
    }

    @Test
    fun itSurvivesEveryScale() {
        setSplashContent()

        listOf(UiScale.Compact, UiScale.Standard, UiScale.Large).forEach { scale ->
            composeRule.runOnUiThread { uiScale = scale }
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription(SPLASH_LABEL).assertIsDisplayed()
        }
    }

    private fun setSplashContent() {
        composeRule.setContent {
            IglooTheme(uiScale = uiScale) {
                // Provided inside the theme, which supplies its own value from the system animator
                // scale and would otherwise win. The stagger is verified on-device, not here.
                CompositionLocalProvider(LocalIglooReducedMotion provides true) {
                    Box(Modifier.size(width = 960.dp, height = 540.dp)) {
                        SplashScreen(announce = announce)
                    }
                }
            }
        }
    }
}
