package com.igloo.blindpenguincoder.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

class InTheatersCardTest {

    @Test
    fun `rounds the TMDB score to the one decimal the badge shows`() {
        assertEquals("7.9", ratingBadgeSpec(7.869).label)
        assertEquals("6.0", ratingBadgeSpec(6.0).label)
        assertEquals("10.0", ratingBadgeSpec(10.0).label)
    }

    @Test
    fun `tiers the score the badge shows, not the raw one`() {
        // Both round up across a tier boundary: reading the tier off the raw score would paint
        // "7.0" in the middle tier and "5.0" in the low one.
        assertEquals(RatingTier.Strong, ratingBadgeSpec(6.951).tier)
        assertEquals("7.0", ratingBadgeSpec(6.951).label)
        assertEquals(RatingTier.Fair, ratingBadgeSpec(4.96).tier)
        assertEquals("5.0", ratingBadgeSpec(4.96).label)
    }

    @Test
    fun `keeps the section 3-2 tier boundaries`() {
        assertEquals(RatingTier.Strong, ratingBadgeSpec(7.0).tier)
        assertEquals(RatingTier.Fair, ratingBadgeSpec(6.94).tier)
        assertEquals(RatingTier.Fair, ratingBadgeSpec(5.0).tier)
        assertEquals(RatingTier.Weak, ratingBadgeSpec(4.94).tier)
    }
}
