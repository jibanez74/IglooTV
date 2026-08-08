package com.igloo.blindpenguincoder.core.ui

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.igloo.blindpenguincoder.core.design.IglooDarkColors
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.LocalIglooReducedMotion
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The guard that was missing while `Primary` buttons had no visible focus indicator for the whole
 * life of the auth flow. Every other test asserts `assertIsFocused()`, which reads semantics — so
 * "correctly focused" and "invisibly focused" are indistinguishable to all of them, and a ring
 * drawn in `ring` on top of a fill in `primary` (identical values in the dark palette) passed
 * everything.
 *
 * These assert pixels instead. They are not golden-image tests: nothing is stored, nothing needs
 * re-blessing, and they compare two states of the same render on the device in front of them.
 */
@RunWith(AndroidJUnit4::class)
class FocusTreatmentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val density = context.resources.displayMetrics.density

    /**
     * TalkBack paints a green accessibility-focus rectangle over the focused node, which sits
     * exactly on top of the ring and the separator and makes every assertion here meaningless.
     * Skipped rather than failed: the Shield keeps TalkBack on for the a11y validation
     * `AGENTS.md` requires, and a pixel test must not turn that into a red build.
     */
    @Before
    fun skipWhileAnAccessibilityServiceIsDrawingItsOwnFocusIndicator() {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        assumeFalse(
            "an accessibility service is active and draws its own focus indicator over the " +
                "treatment under test; disable TalkBack to run these",
            manager != null && manager.isEnabled,
        )
    }

    @Test
    fun focusVisiblyChangesAPrimaryButton() {
        val (unfocused, focused) = captureBothStates(IglooButtonVariant.Primary)
        assertDiffers("Primary", unfocused, focused)
    }

    @Test
    fun focusVisiblyChangesAGhostButton() {
        val (unfocused, focused) = captureBothStates(IglooButtonVariant.Ghost)
        assertDiffers("Ghost", unfocused, focused)
    }

    /**
     * The specific regression, and the one that survived every existing test. A `Primary` button is
     * filled with `primary`, and in the dark palette `ring == primary`, so a ring alone is
     * invisible here no matter how thick it is.
     *
     * "Outlined" is asserted as a shape in the luminance profile, not as a colour at a hardcoded
     * offset: reading inward from the button's outermost glacier pixel, the trace must go
     * **bright, then dark, then bright again** — ring, separation, fill. A control whose ring is
     * its own fill colour goes bright and stays bright, and fails at the first assertion.
     *
     * Deliberately not "is there a dark pixel near the edge". The glow's own falloff is dark, so
     * that version of the test passes on a button with no outline at all — verified by restoring
     * the defect and watching it stay green.
     */
    @Test
    fun aFocusedPrimaryButtonIsOutlinedAgainstItsOwnFill() {
        val (_, focused) = captureBothStates(IglooButtonVariant.Primary)

        val glacier = IglooDarkColors.primary.toArgbLuminance()
        // `ring` and `primary` are the same value, so this matches the ring and the fill alike.
        // That is the point: the test is looking for the seam between two identical colours.
        val isGlacier =
            { x: Int, y: Int -> contrastRatio(luminance(focused.getPixel(x, y)), glacier) < 1.1 }

        // The button is located from the pixels rather than from node bounds: the treatment draws
        // outside its layout bounds (the scale and the glow both do), so a capture cropped to
        // those bounds starts partway inside and hides the very band under test.
        var top = -1
        var bottom = -1
        for (y in 0 until focused.height) {
            if ((0 until focused.width).any { isGlacier(it, y) }) {
                if (top < 0) top = y
                bottom = y
            }
        }
        assertTrue("no glacier pixels found anywhere in the capture", top >= 0)

        val row = (top + bottom) / 2
        val outerEdge = (0 until focused.width).first { isGlacier(it, row) }

        val band = (12 * density).toInt()
        val inward = (outerEdge until minOf(outerEdge + band, focused.width))
            .map { luminance(focused.getPixel(it, row)) }
        val dip = inward.indexOfFirst { contrastRatio(it, glacier) >= 3.0 }

        assertTrue(
            "the focused Primary button is not outlined: reading inward from its outer glacier " +
                "edge the trace never darkens by 3:1, so the ring and the interior read as one " +
                "solid block. Ring and fill are both `#38BDF8` in the dark palette — something " +
                "has to separate them. Contrast-vs-glacier inward: " +
                inward.joinToString(" ") { "%.2f".format(contrastRatio(it, glacier)) },
            dip >= 0,
        )
        assertTrue(
            "found a dark band but no glacier beyond it — that is the button's outer edge " +
                "against the canvas, not a ring separated from its fill",
            inward.drop(dip).any { contrastRatio(it, glacier) < 1.5 },
        )
    }

    @Test
    fun focusedOpaqueCircularContentShowsRingSeparatorAndAvatarInOrder() {
        composeRule.setContent {
            IglooTheme {
                CompositionLocalProvider(LocalIglooReducedMotion provides true) {
                    Box(
                        Modifier
                            .background(IglooTheme.colors.background)
                            .padding(24.dp),
                    ) {
                        Box(
                            Modifier
                                .size(96.dp)
                                .focusRing(
                                    focused = true,
                                    radius = IglooTheme.radius.pill,
                                ),
                        ) {
                            Box(
                                Modifier
                                    .size(96.dp)
                                    .background(OPAQUE_AVATAR, CircleShape),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val focused = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val row = focused.height / 2
        val glacier = IglooDarkColors.ring.toArgbLuminance()
        val avatar = OPAQUE_AVATAR.toArgbLuminance()
        val inward = (0 until focused.width).map { luminance(focused.getPixel(it, row)) }

        val ring = inward.indexOfFirst { contrastRatio(it, glacier) < 1.2 }
        assertTrue("the opaque avatar covered every solid ring pixel", ring >= 0)

        val separator = inward.indexOfFirstAfter(ring) {
            contrastRatio(it, glacier) >= 3.0 && contrastRatio(it, avatar) >= 3.0
        }
        assertTrue(
            "the focused ring runs directly into opaque avatar content; no real separator was " +
                "visible. Luminance inward: ${inward.traceFrom(ring)}",
            separator > ring,
        )

        val content = inward.indexOfFirstAfter(separator) { contrastRatio(it, avatar) < 1.2 }
        assertTrue(
            "ring and separator were visible, but opaque avatar content did not follow them. " +
                "Luminance inward: ${inward.traceFrom(ring)}",
            content > separator,
        )
    }

    /**
     * Deliberately not a bare `!=`. A treatment that only lifted the least significant bits — an
     * elevation glow on its own measures about 1.5:1 against the canvas — would satisfy inequality
     * while being invisible at ten feet, which is exactly the failure being guarded against.
     */
    private fun assertDiffers(case: String, unfocused: Bitmap, focused: Bitmap) {
        var changed = 0
        var peak = 1.0
        for (y in 0 until minOf(unfocused.height, focused.height)) {
            for (x in 0 until minOf(unfocused.width, focused.width)) {
                val a = luminance(unfocused.getPixel(x, y))
                val b = luminance(focused.getPixel(x, y))
                val ratio = contrastRatio(a, b)
                if (ratio >= 1.5) changed++
                if (ratio > peak) peak = ratio
            }
        }
        val total = unfocused.width * unfocused.height
        assertTrue(
            "$case: focus changed no pixel by a visible amount (peak ${"%.2f".format(peak)}:1)",
            peak >= 3.0,
        )
        assertTrue(
            "$case: focus changed only $changed of $total pixels; the treatment is too faint " +
                "to find at TV viewing distance",
            changed >= total / 100,
        )
    }

    /** Renders the button unfocused, captures, gives it focus, captures again. */
    private fun captureBothStates(variant: IglooButtonVariant): Pair<Bitmap, Bitmap> {
        val focusRequester = FocusRequester()
        composeRule.setContent { Probe(variant, focusRequester) }

        composeRule.waitForIdle()
        val unfocused = composeRule.onRoot().captureToImage().asAndroidBitmap()

        composeRule.runOnUiThread { focusRequester.requestFocus() }
        composeRule.waitForIdle()
        val focused = composeRule.onRoot().captureToImage().asAndroidBitmap()

        return unfocused to focused
    }

    @Composable
    private fun Probe(variant: IglooButtonVariant, focusRequester: FocusRequester) {
        IglooTheme {
            // Reduced motion pinned on so both captures are taken at an endpoint rather than
            // mid-tween, and provided inside the theme, which supplies its own value from the
            // system animator scale and would otherwise win.
            CompositionLocalProvider(LocalIglooReducedMotion provides true) {
                Box(
                    Modifier
                        .background(IglooTheme.colors.background)
                        // Room for the scale and the glow to land clear of the button, so the
                        // scan below reads the treatment and not the edge of the canvas.
                        .padding(24.dp),
                ) {
                    IglooButton(
                        text = "Connect",
                        onClick = {},
                        variant = variant,
                        modifier = Modifier
                            .width(240.dp)
                            .focusRequester(focusRequester),
                    )
                }
            }
        }
    }

    private fun androidx.compose.ui.graphics.Color.toArgbLuminance(): Double =
        channelLuminance(red.toDouble(), green.toDouble(), blue.toDouble())

    private fun luminance(pixel: Int): Double = channelLuminance(
        ((pixel shr 16) and 0xFF) / 255.0,
        ((pixel shr 8) and 0xFF) / 255.0,
        (pixel and 0xFF) / 255.0,
    )

    private fun channelLuminance(r: Double, g: Double, b: Double): Double =
        0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)

    private fun linear(c: Double): Double =
        if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)

    private fun contrastRatio(a: Double, b: Double): Double {
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun List<Double>.indexOfFirstAfter(index: Int, predicate: (Double) -> Boolean): Int {
        val offset = drop(index + 1).indexOfFirst(predicate)
        return if (offset < 0) -1 else index + 1 + offset
    }

    private fun List<Double>.traceFrom(index: Int): String =
        drop(index.coerceAtLeast(0)).take((12 * density).toInt())
            .joinToString(" ") { "%.3f".format(it) }

    private companion object {
        val OPAQUE_AVATAR = Color.White
    }
}
