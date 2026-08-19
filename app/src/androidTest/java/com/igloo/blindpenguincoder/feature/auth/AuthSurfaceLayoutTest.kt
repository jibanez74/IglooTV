package com.igloo.blindpenguincoder.feature.auth

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.LocalIglooReducedMotion
import com.igloo.blindpenguincoder.core.design.UiScale
import com.igloo.blindpenguincoder.core.design.viewportFactor
import com.igloo.blindpenguincoder.core.ui.IglooButton
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The auth canvas fills the panel.
 *
 * It used to be a 480dp card centred on a 960dp viewport — half the screen was the card and the
 * rest was empty background, which is what "it looks like a mobile app" meant concretely. Nothing
 * caught it, because `QuickConnectLayoutTest`'s `assertFullyOnscreen` checked top and bottom only.
 * These assertions are horizontal on purpose.
 */
@RunWith(AndroidJUnit4::class)
class AuthSurfaceLayoutTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var safeAreaHorizontal: Dp = Dp.Unspecified

    // Driven through state so one setContent covers all three scales — a compose rule accepts
    // exactly one, the same shape WelcomeScreenLayoutTest uses.
    private var uiScale by mutableStateOf(UiScale.Standard)

    private fun setCanvas(canvas: AuthCanvas) {
        composeRule.setContent {
            IglooTheme(uiScale = uiScale) {
                assertReferenceViewport()
                safeAreaHorizontal = IglooTheme.layout.safeAreaHorizontal
                // The backdrop's ambient loop never lets the Compose clock go idle.
                CompositionLocalProvider(LocalIglooReducedMotion provides true) {
                    Box(
                        Modifier
                            .size(width = VIEWPORT_WIDTH, height = VIEWPORT_HEIGHT)
                            .testTag(VIEWPORT_TAG),
                    ) {
                        AuthSurface(
                            title = "Sign in to Igloo",
                            subtitle = "https://igloo.test",
                            canvas = canvas,
                        ) {
                            IglooButton(
                                text = "A control",
                                onClick = {},
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun atScale(scale: UiScale) {
        composeRule.runOnUiThread { uiScale = scale }
        composeRule.waitForIdle()
    }

    @Composable
    private fun assertReferenceViewport() {
        val widthDp = LocalWindowInfo.current.containerSize.width / LocalDensity.current.density
        assertTrue(
            "test device reports ${widthDp}dp wide; this suite assumes the 960dp reference viewport",
            viewportFactor(widthDp) == 1f,
        )
    }

    private fun viewport() = composeRule.onNodeWithTag(VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot

    @Test
    fun theCanvasItselfIsFullBleedAtEveryScale() {
        setCanvas(AuthCanvas.Split)

        UiScale.entries.forEach { scale ->
            atScale(scale)
            val panel = viewport()
            val canvas = composeRule.onNodeWithTag("auth_surface").fetchSemanticsNode().boundsInRoot
            assertTrue(
                "at $scale the canvas $canvas should cover the panel $panel",
                canvas.left == panel.left && canvas.top == panel.top &&
                    canvas.right == panel.right && canvas.bottom == panel.bottom,
            )
        }
    }

    @Test
    fun theSplitFormReachesTheEndSafeAreaRatherThanACardEdge() {
        setCanvas(AuthCanvas.Split)

        UiScale.entries.forEach { scale ->
            atScale(scale)
            val panel = viewport()
            val form = composeRule.onNodeWithTag("auth_form").fetchSemanticsNode().boundsInRoot
            val expected = panel.right - with(composeRule.density) { safeAreaHorizontal.toPx() }
            assertTrue(
                "at $scale the form's right ${form.right} should sit on the safe area $expected",
                kotlin.math.abs(form.right - expected) < 2f,
            )
        }
    }

    @Test
    fun theStackedCanvasSpansBothSafeAreaEdges() {
        setCanvas(AuthCanvas.Stacked)

        UiScale.entries.forEach { scale ->
            atScale(scale)
            val panel = viewport()
            val form = composeRule.onNodeWithTag("auth_form").fetchSemanticsNode().boundsInRoot
            val inset = with(composeRule.density) { safeAreaHorizontal.toPx() }
            assertTrue(
                "at $scale the stacked content ${form.left}..${form.right} should span the " +
                    "inset measure ${panel.left + inset}..${panel.right - inset}",
                kotlin.math.abs(form.left - (panel.left + inset)) < 2f &&
                    kotlin.math.abs(form.right - (panel.right - inset)) < 2f,
            )
        }
    }

    private companion object {
        const val VIEWPORT_TAG = "tv-viewport"
        val VIEWPORT_WIDTH = 960.dp
        val VIEWPORT_HEIGHT = 540.dp
    }
}
