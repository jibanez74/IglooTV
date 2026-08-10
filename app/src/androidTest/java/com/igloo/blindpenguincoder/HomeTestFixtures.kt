package com.igloo.blindpenguincoder

import com.igloo.blindpenguincoder.feature.home.HomeMovie

/**
 * Poster-less movies for shell-level tests: the placeholder path renders deterministically
 * with no network or image decoding involved.
 */
internal val testHomeMovies = listOf(
    HomeMovie(id = 1, title = "Heat", year = 1995, posterUrl = null),
    HomeMovie(id = 2, title = "Arrival", year = 2016, posterUrl = null),
    HomeMovie(id = 3, title = "Ran", year = 1985, posterUrl = null),
)
