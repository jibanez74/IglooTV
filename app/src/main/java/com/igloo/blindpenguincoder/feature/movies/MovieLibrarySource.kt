package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.core.error.map
import com.igloo.blindpenguincoder.data.model.MoviesLibraryData
import com.igloo.blindpenguincoder.data.repository.MovieRepository
import com.igloo.blindpenguincoder.feature.library.LibraryGenre
import com.igloo.blindpenguincoder.feature.library.LibraryKind
import com.igloo.blindpenguincoder.feature.library.LibraryPage
import com.igloo.blindpenguincoder.feature.library.LibraryRow
import com.igloo.blindpenguincoder.feature.library.LibrarySource

/**
 * The movie library's routes as a [LibrarySource]: library, genre and liked pages, the count,
 * the genres.
 */
fun movieLibrarySource(movies: MovieRepository): LibrarySource = LibrarySource(
    kind = LibraryKind.Movies,
    all = { page, perPage, sort ->
        movies.moviesLibrary(page, perPage, sort).map { it.toLibraryPage() }
    },
    genre = { genreId, page, perPage, sort ->
        movies.genreMovies(genreId, page, perPage, sort).map { it.toLibraryPage() }
    },
    liked = { page, perPage, sort ->
        movies.likedMovies(page, perPage, sort).map { it.toLibraryPage() }
    },
    total = { movies.movieStats().map { it.totalMovies } },
    genres = {
        movies.movieGenres().map { genres ->
            genres.map { LibraryGenre(id = it.genreId, tag = it.genreTag, count = it.movieCount) }
        }
    },
)

private fun MoviesLibraryData.toLibraryPage(): LibraryPage = LibraryPage(
    rows = movies.map {
        LibraryRow(id = it.id, title = it.title, posterPath = it.posterPath, year = it.year)
    },
    total = total,
    totalPages = totalPages,
)
