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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.core.design.IglooDarkColors
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.LocalIglooReducedMotion
import com.igloo.blindpenguincoder.spokenFeedbackEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * A rail's scroll surface reaches x = 0 (design-system.md section 8.3), so a card scrolled past
 * the gutter sits where the nav spine's translucent scrim would let it show through. The rail
 * paints its leading edge into the canvas. Asserted in pixels on a bare rail: a semantics query
 * sees the card either way, and the defect was only ever visible on a television.
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
    fun aCardScrolledUnderTheStartInsetIsPaintedIntoTheCanvas() {
        val entry = FocusRequester()
        composeRule.setContent {
            IglooTheme {
                CompositionLocalProvider(LocalIglooReducedMotion provides true) {
                    Box(Modifier.fillMaxSize().background(IglooTheme.colors.background)) {
                        IglooMediaRail(
                            title = RAIL_TITLE,
                            state = IglooRailState.Loaded((0L until CARD_COUNT).toList()),
                            itemKey = { it },
                            entryRequester = entry,
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
        composeRule.runOnUiThread { entry.requestFocus() }
        composeRule.waitForIdle()
        card(0).assertIsFocused()

        // Walk right until a card has scrolled under the start inset's opaque half.
        var covering: Long? = null
        for (id in 1 until CARD_COUNT) {
            card(id - 1).performKeyInput { pressKey(Key.DirectionRight) }
            composeRule.waitForIdle()
            card(id).assertIsFocused()
            covering = (0 until id).firstOrNull { candidate ->
                val bounds = card(candidate).getBoundsInRoot()
                bounds.left < PROBE_X && bounds.right > PROBE_X
            }
            if (covering != null) break
        }
        assertNotNull("no card ever scrolled under the start inset, so the fade was never exercised", covering)

        val row = composeRule.onNodeWithTag("rail_scroll_$RAIL_TITLE")
        val rowBounds = row.getBoundsInRoot()
        val capture = row.captureToImage().asAndroidBitmap()
        val probeX = ((PROBE_X - rowBounds.left).value * density).toInt()
        val cardBounds = card(covering!!).getBoundsInRoot()
        val probeY = (((cardBounds.top + cardBounds.bottom) / 2 - rowBounds.top).value * density).toInt()

        assertEquals(
            "card $covering shows through the start inset at x = $PROBE_X; the leading edge did not fade it",
            IglooDarkColors.background.toArgb(),
            capture.getPixel(probeX, probeY),
            TOLERANCE,
        )
        // The same scan proves the probe can see a card at all: the focused card, past the inset.
        val focusedBounds = card(covering + 1).getBoundsInRoot()
        val visibleX = (((focusedBounds.left + focusedBounds.right) / 2 - rowBounds.left).value * density).toInt()
        assertEquals(
            "the probe cannot see a card past the inset either, so the fade assertion proves nothing",
            CARD_COLOR.toArgb(),
            capture.getPixel(visibleX, probeY),
            TOLERANCE,
        )
    }

    private fun card(id: Long) = composeRule.onNodeWithTag("fade_card_$id")
    private fun card(id: Int) = card(id.toLong())

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
        /** Inside the opaque half of the start inset. */
        val PROBE_X = 8.dp
        const val TOLERANCE = 4
    }
}
