package com.igloo.blindpenguincoder.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.core.design.IglooDarkColors
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.LocalIglooReducedMotion
import com.igloo.blindpenguincoder.spokenFeedbackEnabled
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * A rail's scroll surface reaches the logical start edge (design-system.md section 8.3), so a
 * card scrolled past the gutter sits where the nav spine's translucent scrim would let it show
 * through. The rail paints its leading edge into the canvas. Asserted in pixels on a bare rail:
 * a semantics query sees the card either way, and the defect was only ever visible on a television.
 */
@RunWith(AndroidJUnit4::class)
class IglooMediaRailFadeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val density =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    /** TalkBack paints its own rectangle over the focused node; see `FocusTreatmentTest`. */
    @Before
    fun skipWhileAnAccessibilityServiceIsDrawingItsOwnFocusIndicator() {
        assumeFalse("a screen reader is active; disable TalkBack to run this", spokenFeedbackEnabled())
    }

    @Test
    fun aCardScrolledUnderTheLtrStartInsetIsPaintedIntoTheCanvas() {
        assertStartInsetFade(LayoutDirection.Ltr)
    }

    @Test
    fun aCardScrolledUnderTheRtlStartInsetIsPaintedIntoTheCanvas() {
        assertStartInsetFade(LayoutDirection.Rtl)
    }

    private fun assertStartInsetFade(direction: LayoutDirection) {
        composeRule.setContent {
            IglooTheme {
                CompositionLocalProvider(
                    LocalIglooReducedMotion provides true,
                    LocalLayoutDirection provides direction,
                ) {
                    Box(Modifier.fillMaxSize().background(IglooTheme.colors.background)) {
                        IglooMediaRail(
                            title = RAIL_TITLE,
                            state = IglooRailState.Loaded((0L until CARD_COUNT).toList()),
                            itemKey = { it },
                            entryRequester = null,
                            leftFocusRequester = FocusRequester.Cancel,
                            lastFocusedKey = null,
                            onItemFocused = {},
                            contentInset = PaddingValues(start = INSET_START, end = 48.dp),
                        ) { id, itemModifier, _ ->
                            Box(
                                itemModifier
                                    .testTag("fade_card_$id")
                                    .size(CARD_WIDTH, CARD_HEIGHT)
                                    .background(CARD_COLOR)
                                    .focusable(),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        val row = composeRule.onNodeWithTag("rail_scroll_$RAIL_TITLE")
        // Move the first card from its resting inset to the physical start edge. The semantics
        // action scrolls forward in either direction, independently of directional focus.
        row.performSemanticsAction(SemanticsActions.ScrollBy) {
            it(INSET_START.value * density, 0f)
        }
        composeRule.waitForIdle()
        val rowBounds = row.getBoundsInRoot()
        val capture = row.captureToImage().asAndroidBitmap()
        val cardBounds = card(0).getBoundsInRoot()
        val probeY = (((cardBounds.top + cardBounds.bottom) / 2 - rowBounds.top).value * density).toInt()

        fun xFromStart(distance: Float): Int =
            if (direction == LayoutDirection.Ltr) {
                (distance * density).toInt()
            } else {
                capture.width - 1 - (distance * density).toInt()
            }

        fun pixelFromStart(distance: Float): Int = capture.getPixel(xFromStart(distance), probeY)

        // Prove all fade probes sit over actual card content, rather than an empty gutter.
        for (distance in listOf(8f, 120f, 140f)) {
            val rootX = rowBounds.left.value + xFromStart(distance) / density
            assertTrue(
                "$direction: no card under the start inset at $distance dp",
                rootX > cardBounds.left.value && rootX < cardBounds.right.value,
            )
        }
        assertEquals(
            "$direction: the card shows through the opaque outer portion of the start inset",
            IglooDarkColors.background.toArgb(),
            pixelFromStart(8f),
            TOLERANCE,
        )
        // White cards must brighten toward the inner boundary, with both transition probes
        // between the background and card colors. This also catches an incorrectly reversed ramp.
        for (shift in listOf(16, 8, 0)) {
            val background = (IglooDarkColors.background.toArgb() shr shift) and 0xff
            val outerBlend = (pixelFromStart(120f) shr shift) and 0xff
            val innerBlend = (pixelFromStart(140f) shr shift) and 0xff
            assertTrue(
                "$direction: the transition does not blend toward the card (channel $shift)",
                outerBlend > background + TOLERANCE &&
                    innerBlend > outerBlend + TOLERANCE && innerBlend < 255 - TOLERANCE,
            )
        }
        val visibleBounds = card(1).getBoundsInRoot()
        val visibleX = (((visibleBounds.left + visibleBounds.right) / 2 - rowBounds.left).value * density).toInt()
        val visibleDistance = if (direction == LayoutDirection.Ltr) visibleX else capture.width - 1 - visibleX
        assertTrue(
            "$direction: the visible-content probe is still inside the inset",
            visibleDistance > INSET_START.value * density,
        )
        assertEquals(
            "$direction: content beyond the inset is obscured",
            CARD_COLOR.toArgb(),
            capture.getPixel(visibleX, probeY),
            TOLERANCE,
        )
    }

    private fun card(id: Int) = composeRule.onNodeWithTag("fade_card_$id")

    private fun assertEquals(message: String, expected: Int, actual: Int, tolerance: Int) {
        val channels = listOf(16, 8, 0).map { shift -> abs(((expected shr shift) and 0xff) - ((actual shr shift) and 0xff)) }
        if (channels.any { it > tolerance }) {
            throw AssertionError("$message: expected #${Integer.toHexString(expected)} but was #${Integer.toHexString(actual)}")
        }
    }

    private companion object {
        const val RAIL_TITLE = "Fade"
        const val CARD_COUNT = 12L
        val INSET_START = 160.dp
        val CARD_WIDTH = 148.dp
        val CARD_HEIGHT = 222.dp
        val CARD_COLOR = Color.White
        const val TOLERANCE = 4
    }
}
