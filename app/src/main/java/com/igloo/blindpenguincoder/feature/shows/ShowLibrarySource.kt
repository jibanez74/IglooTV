package com.igloo.blindpenguincoder.feature.shows

import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.data.model.ShowsLibraryData
import com.igloo.blindpenguincoder.data.repository.ShowRepository
import com.igloo.blindpenguincoder.feature.library.LibraryGenre
import com.igloo.blindpenguincoder.feature.library.LibraryKind
import com.igloo.blindpenguincoder.feature.library.LibraryPage
import com.igloo.blindpenguincoder.feature.library.LibraryRow
import com.igloo.blindpenguincoder.feature.library.LibrarySource

/**
 * The show library's routes as a [LibrarySource]: library and genre pages, the count, the
 * genres. No liked list — the backend keeps no likes for shows — so the pane offers no Liked tab.
 */
fun showLibrarySource(shows: ShowRepository): LibrarySource = LibrarySource(
    kind = LibraryKind.Shows,
    all = { page, perPage, sort ->
        shows.showsLibrary(page, perPage, sort).map { it.toLibraryPage() }
    },
    genre = { genreId, page, perPage, sort ->
        shows.genreShows(genreId, page, perPage, sort).map { it.toLibraryPage() }
    },
    liked = null,
    total = { shows.showStats().map { it.totalShows } },
    genres = {
        shows.showGenres().map { genres ->
            genres.map { LibraryGenre(id = it.genreId, tag = it.genreTag, count = it.showCount) }
        }
    },
)

private fun ShowsLibraryData.toLibraryPage(): LibraryPage = LibraryPage(
    rows = shows.map { LibraryRow(id = it.id, title = it.name, posterPath = it.posterPath, year = it.premiereYear) },
    total = total,
    totalPages = totalPages,
)
