package com.igloo.blindpenguincoder.feature.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.LocalIglooReducedMotion
import com.igloo.blindpenguincoder.core.design.UiScale
import com.igloo.blindpenguincoder.core.design.viewportFactor
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The 960×540 box the screen is measured against — see `assertFullyOnscreen`. */
private const val VIEWPORT_TAG = "tv-viewport"

/**
 * The welcome screen has no internal scroll, so "does it fit" is the whole test. It is exercised
 * directly rather than through the activity so both [UiScale] and the system font scale can vary —
 * the worst case is Large at the 1.30 font-scale ceiling, which clears the budget by ~32dp.
 */
@RunWith(AndroidJUnit4::class)
class WelcomeScreenLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var uiScale by mutableStateOf(UiScale.Standard)
    private var fontScale by mutableStateOf(1.0f)

    @Test
    fun everythingFitsAtEveryScaleAndFontScale() {
        setWelcomeContent()

        listOf(UiScale.Compact, UiScale.Standard, UiScale.Large).forEach { scale ->
            listOf(1.0f, 1.30f).forEach { font ->
                composeRule.runOnUiThread {
                    uiScale = scale
                    fontScale = font
                }
                composeRule.waitForIdle()

                val case = "$scale at font scale $font"
                composeRule.onNodeWithText(WELCOME_HEADLINE).assertFullyOnscreen(case)
                composeRule.onNodeWithText(WELCOME_INTRO).assertFullyOnscreen(case)
                WelcomeSteps.forEach { step ->
                    composeRule.onNodeWithContentDescription(step.accessibilityLabel)
                        .assertFullyOnscreen(case)
                }
                composeRule.onNodeWithContentDescription(WELCOME_ACTION).assertFullyOnscreen(case)
            }
        }
    }

    @Test
    fun theActionIsFocusedImmediatelyUnderReducedMotion() {
        setWelcomeContent()

        // A screen whose entrance snapped must still be complete and driveable.
        composeRule.onNodeWithText(WELCOME_HEADLINE).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(WELCOME_ACTION).assertIsFocused()
    }

    private fun setWelcomeContent() {
        composeRule.setContent {
            assertReferenceViewport()
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                IglooTheme(uiScale = uiScale) {
                    // Pinned on, and provided inside the theme because the theme supplies its own
                    // value from the system animator scale and would otherwise win. The ambient
                    // backdrop loops forever, so with motion on the Compose clock never goes idle
                    // and every assertion below would spin until it timed out. Motion itself is
                    // verified on-device, not here.
                    CompositionLocalProvider(
                        LocalIglooReducedMotion provides true,
                    ) {
                        Box(
                            Modifier
                                .size(width = 960.dp, height = 540.dp)
                                .testTag(VIEWPORT_TAG),
                        ) {
                            WelcomeScreen(onGetStarted = {})
                        }
                    }
                }
            }
        }
    }

    /**
     * IglooTheme derives its viewport factor from the window rather than from the box below, so a
     * device reporting 1200dp or wider would double every dimension and fail this suite for the
     * wrong reason.
     */
    @Composable
    private fun assertReferenceViewport() {
        val widthDp = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density
        assertTrue(
            "test device reports ${widthDp}dp wide; the layout budget assumes the 960dp " +
                "reference viewport",
            viewportFactor(widthDp) == 1f,
        )
    }

    /**
     * Measured against the 960×540 box, not `onRoot()`. The root is the whole test-activity
     * window, which on a taller device leaves room for content that overflows a real TV panel to
     * still sit inside it — the assertion would pass on a layout that does not fit.
     */
    private fun SemanticsNodeInteraction.assertFullyOnscreen(case: String): SemanticsNodeInteraction {
        assertIsDisplayed()
        val viewport = composeRule.onNodeWithTag(VIEWPORT_TAG).getUnclippedBoundsInRoot()
        val bounds = getUnclippedBoundsInRoot()
        assertTrue("$case: starts above the viewport: $bounds", bounds.top >= viewport.top)
        assertTrue("$case: ends below the viewport: $bounds", bounds.bottom <= viewport.bottom)
        assertTrue("$case: starts left of the viewport: $bounds", bounds.left >= viewport.left)
        assertTrue("$case: ends right of the viewport: $bounds", bounds.right <= viewport.right)
        return this
    }
}
