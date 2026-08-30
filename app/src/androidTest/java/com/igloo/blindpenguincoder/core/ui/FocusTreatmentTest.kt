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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
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
     * exactly on top of the ring and the separator and makes those assertions meaningless.
     * Skipped rather than failed: the Shield keeps TalkBack on for the a11y validation
     * `AGENTS.md` requires, and a pixel test must not turn that into a red build.
     *
     * Kept at class level rather than narrowed to the focused captures. The rest-state tests
     * genuinely do not need it — nothing draws an accessibility rectangle over an unfocused
     * control — but narrowing it was tried and reverted: on the Shield, the one device where the
     * assumption ever fires, `createComposeRule` cannot get a compose hierarchy at all, so
     * narrowing only converted a skip into a red build for an unrelated reason.
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
        val focused = captureRingProbe(focused = true, radius = { IglooTheme.radius.pill }) {
            Box(Modifier.size(PROBE_SIZE).background(OPAQUE_AVATAR, CircleShape))
        }
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
     * The half of `25bb781` that shipped unfinished. Its KDoc and §6.1 both say `focusRing` owns
     * the clip, and on that promise every call site gave up its own `.clip(shape)` — but the
     * implementation only clipped while focused, so at rest each of them drew square-cornered and
     * snapped round the instant focus arrived.
     *
     * Asserted at the corner, which is the only place a missing clip shows: with the clip, the
     * pixel just inside the node's top-left bounds lies outside the corner arc and belongs to the
     * canvas; without it, the content's own square corner is sitting there.
     */
    @Test
    fun restingContentIsClippedToTheCornerRadius() {
        val resting = captureRingProbe(focused = false, radius = { PROBE_RADIUS }) {
            // Deliberately unclipped and edge-to-edge: the whole question is whether the
            // modifier masks a child that does not mask itself.
            Box(Modifier.size(PROBE_SIZE).background(OPAQUE_AVATAR))
        }

        val inset = (PROBE_PADDING_DP * density).toInt() + 2
        val corner = luminance(resting.getPixel(inset, inset))
        assertTrue(
            "the resting control drew its content into the corner, so it is square at rest and " +
                "rounds only on focus. Corner luminance ${"%.3f".format(corner)} vs content " +
                "${"%.3f".format(OPAQUE_AVATAR.toArgbLuminance())}",
            contrastRatio(corner, OPAQUE_AVATAR.toArgbLuminance()) >= 3.0,
        )
    }

    /**
     * The separator gap is `ringWidth + restWidth` and both are unscaled, so on a control narrower
     * than twice it the inset rect inverts. An inverted rounded rect is the *empty* path, and
     * clipping to the empty path erases the content — silently, with no crash and no log.
     *
     * Asserted on the ring rather than on the content, and the ring is the only honest choice: at
     * any size small enough to trigger the inversion, the 3dp ring covers every pixel of the
     * control anyway, so a content assertion would fail whether the clip was right or wrong. What
     * this pins down is that the degenerate size still *renders a treatment* instead of throwing
     * or blanking, and it fixes the floor in a place a future change to the gap will trip over.
     */
    @Test
    fun aFocusedControlTooSmallForTheGapStillDrawsItsTreatment() {
        val focused = captureRingProbe(
            focused = true,
            radius = { 2.dp },
            size = TINY_PROBE_SIZE,
        ) {
            Box(Modifier.size(TINY_PROBE_SIZE).background(OPAQUE_AVATAR))
        }

        val glacier = IglooDarkColors.ring.toArgbLuminance()
        val ringPixels = (0 until focused.width).sumOf { x ->
            (0 until focused.height).count { y ->
                contrastRatio(luminance(focused.getPixel(x, y)), glacier) < 1.2
            }
        }
        assertTrue(
            "a ${TINY_PROBE_SIZE.value.toInt()}dp focused control drew no ring at all",
            ringPixels > 0,
        )
    }

    /** `hasError` swaps the resting hairline to `destructive`; nothing covered it before. */
    @Test
    fun theRestingBorderTurnsDestructiveOnError() {
        // One composition, driven by state. The rule allows a single setContent, so two captures
        // of "the same probe with one parameter changed" have to come from flipping that
        // parameter rather than from composing twice.
        val hasError = mutableStateOf(false)
        composeRule.setContent {
            RingProbe(focused = false, radius = PROBE_RADIUS, hasError = hasError.value) {
                Box(Modifier.size(PROBE_SIZE))
            }
        }
        composeRule.waitForIdle()
        val plain = composeRule.onRoot().captureToImage().asAndroidBitmap()

        composeRule.runOnUiThread { hasError.value = true }
        composeRule.waitForIdle()
        val errored = composeRule.onRoot().captureToImage().asAndroidBitmap()

        val row = plain.height / 2
        val edge = (0 until plain.width).firstOrNull { x ->
            plain.getPixel(x, row) != errored.getPixel(x, row)
        }
        assertTrue("the resting border looks identical with and without hasError", edge != null)

        // Redness, not luminance: `destructive` and `border` are close in luminance and only the
        // hue tells them apart, which is exactly what a luminance-only assertion would miss.
        val pixel = errored.getPixel(edge!!, row)
        val red = (pixel shr 16) and 0xFF
        val blue = pixel and 0xFF
        assertTrue(
            "the errored border differs from the plain one but is not the destructive red " +
                "(r=$red, b=$blue); `border` is a blue slate and `destructive` is not",
            red > blue,
        )
    }

    /**
     * `IglooTextField` is the one opt-out from the focus scale, for reasons `IglooTextField.kt`
     * spells out at length — a scaled field mis-anchors the IME and resamples its glyphs soft.
     * Nothing asserted that the opt-out actually reached a pixel.
     */
    @Test
    fun optingOutOfTheFocusScaleKeepsTheControlTheSameSize() {
        val scaleOnFocus = mutableStateOf(true)
        composeRule.setContent {
            RingProbe(
                focused = true,
                radius = PROBE_RADIUS,
                fill = OPAQUE_AVATAR,
                scaleOnFocus = scaleOnFocus.value,
                content = {},
            )
        }
        composeRule.waitForIdle()
        val scaled = opaqueContentWidth()

        composeRule.runOnUiThread { scaleOnFocus.value = false }
        composeRule.waitForIdle()
        val unscaled = opaqueContentWidth()

        assertTrue(
            "scaleOnFocus = true did not widen the control on focus ($scaled px against " +
                "$unscaled px), so this test cannot tell the two apart and proves nothing " +
                "about the opt-out",
            scaled > unscaled,
        )
        assertTrue(
            "scaleOnFocus = false still scaled the control on focus: $unscaled px against a " +
                "resting ${(PROBE_SIZE.value * density).toInt()} px",
            unscaled <= (PROBE_SIZE.value * density).toInt() + 2,
        )
    }

    /**
     * `radius.pill` is 999dp, clamped to half the shortest side so it resolves to the shape it
     * stands for. The avatar case only exercises a square, where the clamp and the correct radius
     * coincide; a non-square pill is where an unclamped radius would produce an invalid outline.
     */
    @Test
    fun aPillRadiusOnANonSquareControlClampsToTheShortSide() {
        val resting = captureRingProbe(
            focused = false,
            radius = { IglooTheme.radius.pill },
            size = PROBE_SIZE,
            width = PROBE_SIZE * 2,
        ) {
            Box(Modifier.size(width = PROBE_SIZE * 2, height = PROBE_SIZE).background(OPAQUE_AVATAR))
        }

        val pad = (PROBE_PADDING_DP * density).toInt()
        val corner = luminance(resting.getPixel(pad + 2, pad + 2))
        assertTrue(
            "a pill-radius control that is twice as wide as it is tall drew content into its " +
                "corner, so the radius did not resolve to the half-height it stands for",
            contrastRatio(corner, OPAQUE_AVATAR.toArgbLuminance()) >= 3.0,
        )
    }

    /** The palette was hardcoded to dark, and the commit that added these conceded light was untested. */
    @Test
    fun theTreatmentIsVisibleInTheLightPaletteToo() {
        val (unfocused, focused) = captureBothStates(IglooButtonVariant.Primary, darkTheme = false)
        assertDiffers("Primary (light)", unfocused, focused)
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

    /**
     * Renders a bare [focusRing] around [content] on the theme canvas and captures it.
     *
     * [radius] is a lambda because the interesting radii are theme tokens, which can only be read
     * inside composition.
     */
    private fun captureRingProbe(
        focused: Boolean,
        radius: @Composable () -> Dp,
        hasError: Boolean = false,
        size: Dp = PROBE_SIZE,
        width: Dp = size,
        content: @Composable () -> Unit,
    ): Bitmap {
        composeRule.setContent {
            RingProbe(
                focused = focused,
                radius = radius(),
                hasError = hasError,
                size = size,
                width = width,
                content = content,
            )
        }
        composeRule.waitForIdle()
        return composeRule.onRoot().captureToImage().asAndroidBitmap()
    }

    /** A bare [focusRing] around [content], on the theme canvas with room for scale and glow. */
    @Composable
    private fun RingProbe(
        focused: Boolean,
        radius: Dp,
        hasError: Boolean = false,
        fill: Color = Color.Transparent,
        scaleOnFocus: Boolean = true,
        size: Dp = PROBE_SIZE,
        width: Dp = size,
        content: @Composable () -> Unit,
    ) {
        IglooTheme {
            CompositionLocalProvider(LocalIglooReducedMotion provides true) {
                Box(
                    Modifier
                        .background(IglooTheme.colors.background)
                        .padding(PROBE_PADDING_DP.dp),
                ) {
                    Box(
                        Modifier
                            .size(width = width, height = size)
                            .focusRing(
                                focused = focused,
                                radius = radius,
                                fill = fill,
                                hasError = hasError,
                                scaleOnFocus = scaleOnFocus,
                            ),
                    ) {
                        content()
                    }
                }
            }
        }
    }

    /** Width in pixels of the opaque band across the middle of the current capture. */
    private fun opaqueContentWidth(): Int {
        val capture = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val content = OPAQUE_AVATAR.toArgbLuminance()
        val row = capture.height / 2
        val hits = (0 until capture.width).filter {
            contrastRatio(luminance(capture.getPixel(it, row)), content) < 1.2
        }
        return if (hits.isEmpty()) 0 else hits.last() - hits.first() + 1
    }

    /** Renders the button unfocused, captures, gives it focus, captures again. */
    private fun captureBothStates(
        variant: IglooButtonVariant,
        darkTheme: Boolean = true,
    ): Pair<Bitmap, Bitmap> {
        val focusRequester = FocusRequester()
        composeRule.setContent { Probe(variant, focusRequester, darkTheme) }

        composeRule.waitForIdle()
        val unfocused = composeRule.onRoot().captureToImage().asAndroidBitmap()

        composeRule.runOnUiThread { focusRequester.requestFocus() }
        composeRule.waitForIdle()
        val focused = composeRule.onRoot().captureToImage().asAndroidBitmap()

        return unfocused to focused
    }

    @Composable
    private fun Probe(
        variant: IglooButtonVariant,
        focusRequester: FocusRequester,
        darkTheme: Boolean = true,
    ) {
        IglooTheme(darkTheme = darkTheme) {
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
        val PROBE_SIZE = 96.dp
        val PROBE_RADIUS = 24.dp

        /** Room for the scale and the glow to land clear of the probe. */
        const val PROBE_PADDING_DP = 24

        /** Narrower than twice the unscaled `ringWidth + restWidth` gap. */
        val TINY_PROBE_SIZE = 6.dp
    }
}
