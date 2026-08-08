package com.igloo.blindpenguincoder.core.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Guards the motion table in docs/design-system.md section 7. */
class IglooMotionTest {

    @Test
    fun `durations match the documented table`() {
        assertEquals(150, IglooMotion.MICRO_MS)
        assertEquals(200, IglooMotion.STANDARD_MS)
        assertEquals(300, IglooMotion.PAGE_MS)
        assertEquals(60, IglooMotion.STAGGER_MS)
        assertEquals(24_000, IglooMotion.AMBIENT_MS)
        assertEquals(900, IglooMotion.SPLASH_HOLD_MS)
    }

    @Test
    fun `the splash hold outlasts the fade that ends it`() {
        // Otherwise the hand-off starts before the wordmark has finished arriving.
        assertTrue(IglooMotion.SPLASH_HOLD_MS > IglooMotion.PAGE_MS + IglooMotion.STAGGER_MS)
    }

    @Test
    fun `the ambient period clears the twenty second floor the loop carve-out requires`() {
        assertTrue(IglooMotion.AMBIENT_MS >= 20_000)
    }

    @Test
    fun `ambient offset is seamless across the loop boundary`() {
        // RepeatMode.Restart jumps from 1 back to 0, so f(0) must equal f(1) or the backdrop
        // visibly snaps once every cycle.
        listOf(0f, 0.25f, 0.5f, 0.75f).forEach { phase ->
            assertEquals(
                "phase $phase",
                ambientOffset(0f, phase, 40f),
                ambientOffset(1f, phase, 40f),
                1e-4f,
            )
        }
    }

    @Test
    fun `ambient offset stays within its amplitude`() {
        val amplitude = 40f
        var progress = 0f
        while (progress <= 1f) {
            listOf(0f, 0.25f, 0.5f, 0.75f).forEach { phase ->
                val offset = ambientOffset(progress, phase, amplitude)
                assertTrue(
                    "offset $offset exceeded amplitude at progress $progress phase $phase",
                    abs(offset) <= amplitude + 1e-4f,
                )
            }
            progress += 0.01f
        }
    }

    @Test
    fun `phase actually staggers`() {
        assertTrue(
            abs(ambientOffset(0f, 0f, 40f) - ambientOffset(0f, 0.25f, 40f)) > 1f,
        )
    }
}
