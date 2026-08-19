package com.igloo.blindpenguincoder.core.ui

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.core.design.IglooDarkColors
import com.igloo.blindpenguincoder.core.design.IglooMotion
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.LocalIglooReducedMotion
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pixel assertions for the anchored menu's authored alpha reveal (section 7.2). */
@RunWith(AndroidJUnit4::class)
class IglooMenuMotionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val density = InstrumentationRegistry.getInstrumentation()
        .targetContext.resources.displayMetrics.density

    @Test
    fun revealStartsTransparentAndReachesFullOpacityAfterStandardDuration() {
        composeRule.mainClock.autoAdvance = false
        setMenu(reducedMotion = false)

        // This frame runs LaunchedEffect and establishes animation play time zero.
        composeRule.mainClock.advanceTimeByFrame()
        val initial = revealFraction()

        composeRule.mainClock.advanceTimeBy(IglooMotion.STANDARD_MS / 2L)
        val middle = revealFraction()

        composeRule.mainClock.advanceTimeBy(IglooMotion.STANDARD_MS / 2L + FRAME_MILLIS)
        val full = revealFraction()

        assertTrue("reveal did not start below full opacity: $initial", initial < 0.10f)
        assertTrue("reveal did not progress by its midpoint: $middle", middle > initial + 0.10f)
        assertTrue("reveal reached full opacity too early: $middle", middle < 0.98f)
        assertTrue("reveal did not reach full opacity: $full", abs(full - 1f) < 0.03f)
    }

    @Test
    fun revealSnapsWhenReducedMotionIsEnabled() {
        composeRule.mainClock.autoAdvance = false
        setMenu(reducedMotion = true)

        // Two render frames are still far shorter than standard; snap must already be at 1.
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()

        val reveal = revealFraction()
        assertTrue("reduced-motion reveal did not snap: $reveal", abs(reveal - 1f) < 0.03f)
    }

    private fun setMenu(reducedMotion: Boolean) {
        composeRule.setContent {
            IglooTheme {
                CompositionLocalProvider(LocalIglooReducedMotion provides reducedMotion) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.White),
                    ) {
                        IglooMenu(
                            title = "More options",
                            items = listOf(
                                IglooMenuItem("Playback Settings", {}),
                                IglooMenuItem("Watch Together", {}),
                                IglooMenuItem("Technical Details", {}),
                            ),
                            anchorBounds = Rect(600f, 60f, 660f, 120f),
                            onDismiss = {},
                        )
                    }
                }
            }
        }
    }

    /**
     * The white canvas and dark card make the red channel a direct effective-alpha probe.
     * Sampled inside the card's bottom-left padding — the resting rows above it are transparent
     * over the same fill, but the focused first row and the label glyphs are not.
     */
    private fun revealFraction(): Float {
        val bounds = composeRule.onNodeWithTag("more_menu").getUnclippedBoundsInRoot()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val pixel = bitmap.getPixel(
            (bounds.left.value * density + SAMPLE_INSET_PX).roundToInt(),
            (bounds.bottom.value * density - SAMPLE_INSET_PX).roundToInt(),
        )
        val canvas = 255f
        val card = AndroidColor.red(IglooDarkColors.card.toArgb()).toFloat()
        return (canvas - AndroidColor.red(pixel)) / (canvas - card)
    }

    private companion object {
        const val FRAME_MILLIS = 16L
        const val SAMPLE_INSET_PX = 10f
    }
}
