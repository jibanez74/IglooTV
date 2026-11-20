package com.igloo.blindpenguincoder.core.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the colour pairs section 3 states as ratios, so a token edit cannot move one unnoticed.
 * The figures are the web client's own (`contrast.test.ts`), which is where the palette comes from.
 */
class IglooColorsContrastTest {

    private val themes = listOf("dark" to IglooDarkColors, "light" to IglooLightColors)

    @Test
    fun `a border is a quiet line against the canvas and against a card`() {
        // Below 1.2:1 the hairline is gone; above 1.55:1 it is a hard outline that outranks what
        // it surrounds. Both ends are pinned so an edit can neither erase it nor bring that back.
        themes.forEach { (name, colors) ->
            listOf("background" to colors.background, "card" to colors.card).forEach { (on, ground) ->
                val ratio = contrastRatio(colors.border, ground)
                assertTrue("$name border on $on was $ratio", ratio in 1.2f..1.55f)
            }
        }
    }

    @Test
    fun `an OutlineDestructive label reads on the canvas and on its focus fill`() {
        themes.forEach { (name, colors) ->
            listOf(colors.background, colors.focusFill()).forEach { ground ->
                val ratio = contrastRatio(colors.destructive, ground)
                assertTrue("$name destructive label was $ratio", ratio >= 4.5f)
            }
        }
    }

    @Test
    fun `the over-media destructive label reads on its ground wherever a hero puts it`() {
        // A hero paints its art, a side scrim, then a fade into the theme's canvas, and its action
        // row sits deep in that fade. So the canvas itself is the lightest thing that can lie
        // under the control in light and the art under the scrim's far stop the lightest in dark;
        // black art is the floor.
        val underControl = themes.map { (_, colors) -> colors.background } +
            Color.Black.copy(alpha = 0.55f).compositeOver(Color.White) +
            Color.Black
        underControl.forEach { behind ->
            val ground = OVER_MEDIA_DESTRUCTIVE_FILL.compositeOver(behind)
            val ratio = contrastRatio(OVER_MEDIA_DESTRUCTIVE, ground)
            assertTrue("over-media destructive label was $ratio over $behind", ratio >= 4.5f)
        }
    }

    @Test
    fun `a picture tile's words read on its band over a white picture`() {
        // White art is the worst a backdrop can do to the band; the count is the dimmer line.
        val band = OVER_MEDIA_TILE_BAND.compositeOver(Color.White)
        assertTrue(contrastRatio(Color.White, band) >= 7f)
        assertTrue(contrastRatio(OVER_MEDIA_SECONDARY.compositeOver(band), band) >= 4.5f)
    }

    @Test
    fun `the missing-media glyph reads on its tile`() {
        assertTrue(contrastRatio(MEDIA_FALLBACK_GLYPH, MEDIA_FALLBACK_TILE) >= 4.5f)
    }
}
