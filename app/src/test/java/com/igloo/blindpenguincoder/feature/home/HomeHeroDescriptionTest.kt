package com.igloo.blindpenguincoder.feature.home

import com.igloo.blindpenguincoder.core.ui.ratingBadgeSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/** What each hero page says to TalkBack (design-system.md section 11.3.1). */
class HomeHeroDescriptionTest {

    private val movie = HomeHero(
        kind = HomeHeroKind.Movie,
        id = 7,
        title = "Heat",
        backdropUrl = null,
        overview = "Obsessive master thief Neil McCauley leads a top-notch crew.",
        year = "1995",
        certification = "R",
        ratingBadge = ratingBadgeSpec(8.2),
        extent = "2h 50m",
        metadataDescription = "Rated 8.2 out of 10, R, 1995, 2 hours and 50 minutes",
    )

    private val show = movie.copy(
        kind = HomeHeroKind.Show,
        id = 40,
        title = "Severance",
        overview = "Mark leads a team of office workers.",
        metadataDescription = "TV-MA, 2022, 2 seasons",
    )

    @Test
    fun `each page names its feature and its place among the pages`() {
        assertEquals(
            "Recently added movie, 1 of 2. Heat. Rated 8.2 out of 10, R, 1995, " +
                "2 hours and 50 minutes. Obsessive master thief Neil McCauley leads a top-notch crew.",
            heroPageDescription(movie, index = 0, count = 2),
        )
        assertEquals(
            "Recently added TV show, 2 of 2. Severance. TV-MA, 2022, 2 seasons. " +
                "Mark leads a team of office workers.",
            heroPageDescription(show, index = 1, count = 2),
        )
    }

    @Test
    fun `a lone page has no place to give`() {
        assertEquals(
            "Recently added TV show. Severance. TV-MA, 2022, 2 seasons. " +
                "Mark leads a team of office workers.",
            heroPageDescription(show, index = 0, count = 1),
        )
    }

    @Test
    fun `absent metadata and overview leave no empty sentences`() {
        assertEquals(
            "Recently added movie, 1 of 2. Heat",
            heroPageDescription(
                movie.copy(overview = null, metadataDescription = null),
                index = 0,
                count = 2,
            ),
        )
    }
}
