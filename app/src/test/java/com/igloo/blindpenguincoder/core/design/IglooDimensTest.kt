package com.igloo.blindpenguincoder.core.design

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the Standard tables in docs/design-system.md sections 5, 6 and 8. If a number changes
 * in that document and this test still passes, the code was not updated.
 */
class IglooDimensTest {

    private val compact = iglooDimens(UiScale.Compact)
    private val standard = iglooDimens(UiScale.Standard)
    private val large = iglooDimens(UiScale.Large)

    @Test
    fun `standard spacing matches the documented scale`() {
        with(standard.spacing) {
            assertEquals(4.dp, xs)
            assertEquals(8.dp, sm)
            assertEquals(16.dp, md)
            assertEquals(24.dp, lg)
            assertEquals(32.dp, xl)
            assertEquals(48.dp, xxl)
        }
    }

    @Test
    fun `standard radius matches the documented scale`() {
        with(standard.radius) {
            assertEquals(6.dp, sm)
            assertEquals(8.dp, md)
            assertEquals(10.dp, lg)
            assertEquals(14.dp, xl)
        }
    }

    @Test
    fun `standard component sizes match the documented scale`() {
        with(standard.sizes) {
            assertEquals(52.dp, controlHeight)
            assertEquals(56.dp, fieldHeight)
            assertEquals(44.dp, navItemHeight)
            assertEquals(48.dp, brandTile)
            assertEquals(10.dp, dot)
        }
        assertEquals(24.dp, standard.icons.md)
        assertEquals(32.dp, standard.icons.lg)
    }

    @Test
    fun `standard layout geometry matches the documented scale`() {
        with(standard.layout) {
            assertEquals(48.dp, safeAreaHorizontal)
            assertEquals(27.dp, safeAreaVertical)
            assertEquals(236.dp, navSpineWidth)
            assertEquals(480.dp, authCardWidth)
            assertEquals(840.dp, authCardWideWidth)
            assertEquals(148.dp, posterWidth)
            assertEquals(264.dp, wideCardWidth)
            assertEquals(2f / 3f, posterAspect, 0.0001f)
            assertEquals(16f / 9f, wideAspect, 0.0001f)
        }
    }

    @Test
    fun `safe area ignores the user preference because overscan is physical`() {
        listOf(compact, standard, large).forEach {
            assertEquals(48.dp, it.layout.safeAreaHorizontal)
            assertEquals(27.dp, it.layout.safeAreaVertical)
        }
    }

    @Test
    fun `safe area still tracks the viewport guard so the margin stays 5 percent`() {
        // A device reporting 1920dp gets everything doubled, including the overscan inset —
        // otherwise the physical margin would halve on exactly the devices that misreport.
        val corrected = iglooDimens(UiScale.Standard, viewportFactor(1920f))
        assertEquals(96.dp, corrected.layout.safeAreaHorizontal)
        assertEquals(54.dp, corrected.layout.safeAreaVertical)
    }

    @Test
    fun `focus treatment is identical at every scale`() {
        listOf(compact, standard, large).forEach {
            assertEquals(3.dp, it.focus.ringWidth)
            assertEquals(1.dp, it.focus.restWidth)
            assertEquals(1.05f, it.focus.scale, 0.0001f)
            assertEquals(16.dp, it.focus.glowElevation)
        }
    }

    @Test
    fun `pill radius is an unscaled sentinel`() {
        listOf(compact, standard, large).forEach { assertEquals(999.dp, it.radius.pill) }
    }

    @Test
    fun `grid columns are discrete and inverse to scale`() {
        assertEquals(6, compact.layout.gridColumns)
        assertEquals(5, standard.layout.gridColumns)
        assertEquals(4, large.layout.gridColumns)
    }

    @Test
    fun `scaled dimensions grow monotonically with ui scale`() {
        val scaled: List<(IglooDimens) -> Dp> = listOf(
            { it.spacing.md }, { it.spacing.lg }, { it.spacing.xl }, { it.spacing.xxl },
            { it.radius.lg }, { it.radius.xl },
            { it.sizes.controlHeight }, { it.sizes.fieldHeight }, { it.sizes.navItemHeight },
            { it.sizes.brandTile },
            { it.icons.md }, { it.icons.lg },
            { it.layout.navSpineWidth }, { it.layout.posterWidth }, { it.layout.wideCardWidth },
            { it.layout.authCardWidth }, { it.layout.authCardWideWidth },
        )
        scaled.forEach { token ->
            assertTrue(
                "expected ${token(compact)} < ${token(standard)} < ${token(large)}",
                token(compact) < token(standard) && token(standard) < token(large),
            )
        }
    }

    @Test
    fun `the viewport guard scales dimensions independently of the user preference`() {
        val corrected = iglooDimens(UiScale.Standard, viewportFactor(1920f))
        assertEquals(2f, corrected.scale, 0.0001f)
        assertEquals(472.dp, corrected.layout.navSpineWidth)
        // ...but hairlines and the discrete column count still do not move.
        assertEquals(3.dp, corrected.focus.ringWidth)
        assertEquals(5, corrected.layout.gridColumns)
    }

    @Test
    fun `the spine still fits the reference viewport at every scale`() {
        // 960dp wide, minus the safe area on both sides, must leave a usable content pane.
        listOf(compact, standard, large).forEach {
            val pane = 960.dp - it.layout.safeAreaHorizontal * 2 - it.layout.navSpineWidth
            assertTrue("content pane collapsed to $pane", pane > 500.dp)
        }
    }
}
