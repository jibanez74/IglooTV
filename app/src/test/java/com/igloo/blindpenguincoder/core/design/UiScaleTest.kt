package com.igloo.blindpenguincoder.core.design

import org.junit.Assert.assertEquals
import org.junit.Test

class UiScaleTest {

    @Test
    fun `reference viewport is not corrected`() {
        assertEquals(1f, viewportFactor(960f), 0.0001f)
    }

    @Test
    fun `viewports below the guard are not corrected`() {
        // A 720p panel reports slightly over the reference width; a 1080dp viewport is still
        // plausible. Neither is a misreporting device.
        assertEquals(1f, viewportFactor(962f), 0.0001f)
        assertEquals(1f, viewportFactor(1080f), 0.0001f)
        assertEquals(1f, viewportFactor(1199f), 0.0001f)
    }

    @Test
    fun `density one sticks reporting 1920dp are corrected back to reference`() {
        assertEquals(2f, viewportFactor(1920f), 0.0001f)
    }

    @Test
    fun `correction engages at the guard threshold`() {
        assertEquals(1200f / 960f, viewportFactor(1200f), 0.0001f)
        assertEquals(1280f / 960f, viewportFactor(1280f), 0.0001f)
    }

    @Test
    fun `correction is capped so absurd viewports cannot explode the ui`() {
        assertEquals(2f, viewportFactor(2400f), 0.0001f)
        assertEquals(2f, viewportFactor(10_000f), 0.0001f)
    }

    @Test
    fun `unknown or missing stored names fall back to the default`() {
        assertEquals(UiScale.Standard, UiScale.fromName(null))
        assertEquals(UiScale.Standard, UiScale.fromName(""))
        assertEquals(UiScale.Standard, UiScale.fromName("Enormous"))
        assertEquals(UiScale.Standard, UiScale.fromName("compact"))
    }

    @Test
    fun `stored names round trip`() {
        UiScale.entries.forEach { assertEquals(it, UiScale.fromName(it.name)) }
    }
}
