package com.igloo.blindpenguincoder.feature.library

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.feature.shared.TabPresentation
import org.junit.Assert.assertEquals
import org.junit.Test

/** The wording and tags the instrumented suites assert, pinned per kind on the JVM. */
class LibraryKindTest {

    @Test
    fun `movies keep the wording and tags the movies suites pin`() {
        val kind = LibraryKind.Movies

        assertEquals("Movies", kind.heading)
        assertEquals("movie", kind.noun(1))
        assertEquals("movies", kind.noun(3))
        assertEquals("the movie library", kind.libraryPhrase)
        assertEquals("Loading movies", kind.loadingLabel)
        assertEquals("movies", kind.tagPrefix)
        assertEquals("poster_card_7", kind.cardTag(7))
        assertEquals(
            TabPresentation("All Movies", "All movies", "movies_tab_all"),
            kind.presentation(LibraryTab.All),
        )
        assertEquals(
            TabPresentation("Genres", "Genres", "movies_tab_genres"),
            kind.presentation(LibraryTab.Genres),
        )
        assertEquals(
            TabPresentation("Liked", "Liked movies", "movies_tab_liked"),
            kind.presentation(LibraryTab.Liked),
        )
        assertEquals("No movies found in your library.", kind.emptyMessage(LibraryFilter.All))
        assertEquals(
            "No liked movies yet. Like a movie from its details page and it will appear here.",
            kind.emptyMessage(LibraryFilter.Liked),
        )
        assertEquals(
            "No Action movies in your library.",
            kind.emptyMessage(LibraryFilter.Genre(id = 7, tag = "Action")),
        )
    }

    @Test
    fun `shows speak of shows and answer to the shows tags`() {
        val kind = LibraryKind.Shows

        assertEquals("TV Shows", kind.heading)
        assertEquals("show", kind.noun(1))
        assertEquals("shows", kind.noun(2))
        assertEquals("the TV show library", kind.libraryPhrase)
        assertEquals("Loading shows", kind.loadingLabel)
        assertEquals("shows", kind.tagPrefix)
        assertEquals("show_card_40", kind.cardTag(40))
        assertEquals(
            TabPresentation("All Shows", "All shows", "shows_tab_all"),
            kind.presentation(LibraryTab.All),
        )
        assertEquals(
            TabPresentation("Genres", "Genres", "shows_tab_genres"),
            kind.presentation(LibraryTab.Genres),
        )
        assertEquals("No shows found in your library.", kind.emptyMessage(LibraryFilter.All))
        assertEquals(
            "No Comedy shows in your library.",
            kind.emptyMessage(LibraryFilter.Genre(id = 7, tag = "Comedy")),
        )
    }

    @Test
    fun `a source offers the liked tab only when it can serve one`() {
        val page: suspend (Long, Long, SortOrder) -> ApiResult<LibraryPage> =
            { _, _, _ -> ApiResult.Success(LibraryPage(emptyList(), total = 0, totalPages = 0)) }
        fun source(liked: Boolean) = LibrarySource(
            kind = LibraryKind.Shows,
            all = page,
            genre = { _, p, perPage, sort -> page(p, perPage, sort) },
            liked = page.takeIf { liked },
            total = { ApiResult.Success(0L) },
            genres = { ApiResult.Success(emptyList()) },
        )

        assertEquals(listOf(LibraryTab.All, LibraryTab.Genres), source(liked = false).tabs)
        assertEquals(LibraryTab.entries, source(liked = true).tabs)
    }

    @Test
    fun `the filter a tab names is the genres tab's remembered genre or nothing`() {
        val genre = LibraryFilter.Genre(id = 9, tag = "Drama")

        assertEquals(LibraryFilter.All, filterFor(LibraryTab.All, genre))
        assertEquals(LibraryFilter.Liked, filterFor(LibraryTab.Liked, genre))
        assertEquals(genre, filterFor(LibraryTab.Genres, genre))
        assertEquals(null, filterFor(LibraryTab.Genres, null))
    }
}
