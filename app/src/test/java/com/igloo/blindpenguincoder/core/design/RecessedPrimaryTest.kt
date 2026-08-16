package com.igloo.blindpenguincoder.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the recessed `Primary` fill a details-hero action row uses while a sibling holds focus.
 * Two things are easy to break here and neither shows up in a screenshot: drifting far enough to
 * read as a disabled button, and losing the label.
 */
class RecessedPrimaryTest {

    private fun contrast(a: Color, b: Color): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    @Test
    fun `the recessed fill is the documented sRGB mix of primary and the canvas`() {
        assertEquals(0xFF2A8AB8.toInt(), IglooDarkColors.recessedPrimary().toArgb())
        assertEquals(0xFF4B94BC.toInt(), IglooLightColors.recessedPrimary().toArgb())
    }

    @Test
    fun `the recessed fill is opaque`() {
        // An alpha fill would let the backdrop through the one control on the screen that must
        // stay solid.
        assertEquals(1f, IglooDarkColors.recessedPrimary().alpha, 0f)
        assertEquals(1f, IglooLightColors.recessedPrimary().alpha, 0f)
    }

    @Test
    fun `recession never reaches the disabled control`() {
        // `primary @ 0.40` is section 3.1's disabled alpha and composites over the canvas to
        // #1C5778 in dark. Recessing that far would say "you cannot press this".
        listOf(IglooDarkColors, IglooLightColors).forEach { colors ->
            val disabled = colors.primary.copy(alpha = 0.4f).compositeOverOpaque(colors.background)
            assertNotEquals(disabled.toArgb(), colors.recessedPrimary().toArgb())
            assertTrue(
                "recessed must stay nearer primary than the disabled composite",
                contrast(colors.recessedPrimary(), colors.primary) <
                    contrast(disabled, colors.primary),
            )
        }
    }

    @Test
    fun `the recessed label clears the control-label contrast bar in both themes`() {
        // Section 12 sets 4.5:1 for a non-body pair. Recession moves the fill toward the canvas,
        // which in light lightens it under a white primaryForeground — hence picking by contrast
        // rather than always using the paired token.
        listOf(IglooDarkColors, IglooLightColors).forEach { colors ->
            val ratio = contrast(colors.recessedPrimaryContent(), colors.recessedPrimary())
            assertTrue("label contrast was $ratio", ratio >= 4.5f)
        }
    }

    @Test
    fun `dark keeps its paired label and light switches to foreground`() {
        assertEquals(IglooDarkColors.primaryForeground, IglooDarkColors.recessedPrimaryContent())
        assertEquals(IglooLightColors.foreground, IglooLightColors.recessedPrimaryContent())
    }

    @Test
    fun `recession is visible against the resting fill`() {
        // The whole point is handing the eye to the focused sibling; an imperceptible step would
        // leave the row exactly as confusing as before.
        listOf(IglooDarkColors, IglooLightColors).forEach { colors ->
            assertTrue(contrast(colors.recessedPrimary(), colors.primary) >= 1.5f)
        }
    }

    private fun Color.compositeOverOpaque(background: Color) = Color(
        red = red * alpha + background.red * (1f - alpha),
        green = green * alpha + background.green * (1f - alpha),
        blue = blue * alpha + background.blue * (1f - alpha),
    )
}
