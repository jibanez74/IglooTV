package com.igloo.blindpenguincoder.core.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The blur behind a hero's poster stand-in (design-system.md section 3.2). */
class BoxBlurTest {

    private val opaque = 0xFF shl 24

    private fun grey(level: Int) = opaque or (level shl 16) or (level shl 8) or level

    private fun level(pixel: Int) = pixel and 0xFF

    @Test
    fun `a flat picture stays exactly as it was, edges included`() {
        // Edges repeat their outermost pixel; averaging against nothing would darken the border
        // of every wash.
        val pixels = IntArray(6 * 4) { grey(120) }

        boxBlur(pixels, width = 6, height = 4, radius = 2, passes = 3)

        assertArrayEquals(IntArray(6 * 4) { grey(120) }, pixels)
    }

    @Test
    fun `a single bright pixel spreads to its neighbours and loses its peak`() {
        val width = 9
        val height = 9
        val centre = 4 * width + 4
        val pixels = IntArray(width * height) { grey(0) }.also { it[centre] = grey(255) }

        boxBlur(pixels, width, height, radius = 1, passes = 1)

        // One pass of radius 1 is a 3 x 3 mean: a ninth of the light lands on each of nine pixels.
        assertEquals(255 / 9, level(pixels[centre]))
        assertEquals(level(pixels[centre]), level(pixels[centre - width - 1]))
        assertEquals(0, level(pixels[centre + 2]))
        assertTrue(pixels.all { it ushr 24 == 0xFF })
    }

    @Test
    fun `more passes reach further`() {
        val width = 15
        val pixels = IntArray(width) { grey(0) }.also { it[7] = grey(255) }

        boxBlur(pixels, width, height = 1, radius = 2, passes = 3)

        // Three passes of radius 2 carry light six pixels out, and no further.
        assertTrue(level(pixels[7 - 6]) > 0)
        assertTrue(level(pixels[7]) > level(pixels[7 - 3]))
        assertEquals(0, level(pixels[0]))
    }

    @Test
    fun `a radius of zero changes nothing`() {
        val pixels = intArrayOf(grey(10), grey(200), grey(30))

        boxBlur(pixels, width = 3, height = 1, radius = 0, passes = 3)

        assertArrayEquals(intArrayOf(grey(10), grey(200), grey(30)), pixels)
    }
}
