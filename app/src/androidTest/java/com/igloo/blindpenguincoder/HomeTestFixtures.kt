package com.igloo.blindpenguincoder

import com.igloo.blindpenguincoder.feature.home.HomeAlbum
import com.igloo.blindpenguincoder.feature.home.HomeContinueMovie
import com.igloo.blindpenguincoder.feature.home.HomeHero
import com.igloo.blindpenguincoder.feature.home.HomeMovie
import com.igloo.blindpenguincoder.feature.home.HomeTheaterMovie

/**
 * Poster-less movies for shell-level tests: the placeholder path renders deterministically
 * with no network or image decoding involved.
 */
internal val testHomeMovies = listOf(
    HomeMovie(id = 1, title = "Heat", year = 1995, posterUrl = null),
    HomeMovie(id = 2, title = "Arrival", year = 2016, posterUrl = null),
    HomeMovie(id = 3, title = "Ran", year = 1985, posterUrl = null),
)

internal val testContinueMovies = listOf(
    HomeContinueMovie(testHomeMovies[0], progressFraction = 0.25f, progressLabel = "127 min left"),
    HomeContinueMovie(testHomeMovies[1], progressFraction = 0.5f, progressLabel = "58 min left"),
    HomeContinueMovie(testHomeMovies[2], progressFraction = 0.9f, progressLabel = "16 min left"),
)

/** Cover-less for the same reason: the Music glyph fallback needs no network. */
internal val testAlbums = listOf(
    HomeAlbum(id = 11, title = "Help!", musician = "The Beatles", coverUrl = null),
    HomeAlbum(id = 12, title = "1984", musician = "Van Halen", coverUrl = null),
    HomeAlbum(id = 13, title = "Tribalistas", musician = "Tribalistas", coverUrl = null),
)

/** Poster-less for the same reason; ratings span the badge's three tiers. */
internal val testTheaterMovies = listOf(
    HomeTheaterMovie(id = 21, title = "Heat 2", year = "2026", posterUrl = null, rating = 7.9),
    HomeTheaterMovie(id = 22, title = "The Odyssey", year = "2026", posterUrl = null, rating = 5.1),
    HomeTheaterMovie(id = 23, title = "Unrated", year = null, posterUrl = null, rating = null),
)

/** Backdrop-less on purpose: the card-surface fallback renders with no image loading. */
internal val testHero = HomeHero(
    id = 1,
    title = "Heat",
    backdropUrl = null,
    overview = "Obsessive master thief Neil McCauley leads a top-notch crew.",
    metadataLine = "1995 · R · 2h 50m · 8.2",
)
