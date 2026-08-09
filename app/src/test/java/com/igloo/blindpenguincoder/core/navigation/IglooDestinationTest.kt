package com.igloo.blindpenguincoder.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IglooDestinationTest {
    @Test
    fun primaryDestinationsKeepTvShellOrder() {
        assertEquals(
            listOf(
                "Search",
                "Home",
                "Movies",
                "TV Shows",
                "Music",
                "Photos",
                "Settings",
            ),
            PrimaryIglooDestinations.map { it.label },
        )
    }

    @Test
    fun destinationsProvideReadableSupportingText() {
        assertTrue(PrimaryIglooDestinations.all { it.supportingText.isNotBlank() })
    }
}

