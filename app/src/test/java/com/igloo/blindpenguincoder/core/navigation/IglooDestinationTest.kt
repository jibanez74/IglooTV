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
    fun theLibraryIsOneUnbrokenRunOfMoviesShowsAndMusic() {
        // The rail draws a hairline wherever this flag changes between neighbours, so a shelf
        // out of the run would cut the group in two.
        assertEquals(
            listOf(IglooDestination.Movies, IglooDestination.TvShows, IglooDestination.Music),
            IglooDestination.entries.filter { it.inLibrary },
        )
        val first = IglooDestination.Movies.ordinal
        assertEquals(listOf(first, first + 1, first + 2), IglooDestination.entries
            .filter { it.inLibrary }
            .map { it.ordinal })
    }

    @Test
    fun destinationsProvideReadableSupportingText() {
        assertTrue(IglooDestination.entries.all { it.supportingText.isNotBlank() })
    }
}

