package com.igloo.blindpenguincoder.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IglooDestinationTest {
    @Test
    fun destinationsKeepTvShellOrder() {
        assertEquals(
            listOf(
                "Search",
                "Home",
                "Watch Rooms",
                "Movies",
                "TV Shows",
                "Music",
                "Settings",
            ),
            IglooDestination.entries.map { it.label },
        )
    }

    @Test
    fun destinationsProvideReadableSupportingText() {
        assertTrue(IglooDestination.entries.all { it.supportingText.isNotBlank() })
    }
}

