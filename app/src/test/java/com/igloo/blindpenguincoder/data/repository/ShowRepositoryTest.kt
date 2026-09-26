package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.SortOrder
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowRepositoryTest {

    @Test
    fun `the shows library request carries page, per page and sort with the bearer token`() =
        runTest {
            var request: HttpRequestData? = null
            val http = TestHttp {
                request = it
                jsonResponse(showsLibraryJson(page = 2, total = 96, totalPages = 2))
            }
            http.profiles.setPending("igd_test")

            http.showRepository.showsLibrary(page = 2, perPage = 48, sort = SortOrder.Descending)

            val captured = requireNotNull(request)
            assertEquals("/api/shows/library", captured.url.encodedPath)
            assertEquals("2", captured.url.parameters["page"])
            assertEquals("48", captured.url.parameters["per_page"])
            // The wire spelling, not the Kotlin constant name.
            assertEquals("desc", captured.url.parameters["sort"])
            assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        }

    @Test
    fun `a library page decodes its items and its paging counts`() = runTest {
        val http = TestHttp {
            jsonResponse(
                showsLibraryJson(
                    page = 1,
                    total = 96,
                    totalPages = 2,
                    shows = arrayOf(
                        showLibraryItemJson(id = 40, name = "Severance"),
                        // A show the scanner never matched: no poster, no year, no rating.
                        showLibraryItemJson(
                            id = 41,
                            name = "Unmatched",
                            posterPath = null,
                            premiereYear = null,
                            certification = null,
                        ),
                    ),
                ),
            )
        }

        val page = (http.showRepository.showsLibrary(1, 48, SortOrder.Ascending)
            as ApiResult.Success).value

        assertEquals(listOf(40L, 41L), page.shows.map { it.id })
        assertEquals("Severance", page.shows.first().name)
        assertEquals("/severance.jpg", page.shows.first().posterPath.orNull())
        assertEquals(2022L, page.shows.first().premiereYear.orNull())
        assertNull(page.shows.last().posterPath.orNull())
        assertNull(page.shows.last().premiereYear.orNull())
        assertEquals(96L, page.total)
        assertEquals(2L, page.totalPages)
    }

    @Test
    fun `an empty library is a successful empty page`() = runTest {
        val http = TestHttp { jsonResponse(showsLibraryJson(total = 0, totalPages = 0)) }

        val page = (http.showRepository.showsLibrary(1, 48, SortOrder.Ascending)
            as ApiResult.Success).value

        assertTrue(page.shows.isEmpty())
        assertEquals(0L, page.total)
    }

    @Test
    fun `a refused library page fails with its status`() = runTest {
        val http = TestHttp {
            jsonResponse("""{"error":true,"message":"nope"}""", HttpStatusCode.InternalServerError)
        }

        val result = http.showRepository.showsLibrary(1, 48, SortOrder.Ascending)

        val error = (result as ApiResult.Failure).error
        assertEquals(500, (error as AppError.Api).status)
    }

    @Test
    fun `a success envelope with no data is unexpected rather than an empty page`() = runTest {
        val http = TestHttp { jsonResponse("""{"error":false,"message":"shows library"}""") }

        val result = http.showRepository.showsLibrary(1, 48, SortOrder.Ascending)

        assertTrue((result as ApiResult.Failure).error is AppError.Unexpected)
    }

    @Test
    fun `a genre's shows page hits the genre path with the same query`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(showsLibraryJson(shows = arrayOf(showLibraryItemJson(id = 40))))
        }

        val page = (http.showRepository.genreShows(7, page = 3, perPage = 48, sort = SortOrder.Ascending)
            as ApiResult.Success).value

        val captured = requireNotNull(request)
        assertEquals("/api/shows/genres/7/shows", captured.url.encodedPath)
        assertEquals("3", captured.url.parameters["page"])
        assertEquals("48", captured.url.parameters["per_page"])
        assertEquals("asc", captured.url.parameters["sort"])
        assertEquals(listOf(40L), page.shows.map { it.id })
    }

    @Test
    fun `show genres decode their ids, tags and counts`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(
                showsGenresJson(
                    showGenreWithCountJson(id = 7, tag = "Comedy", showCount = 2),
                    showGenreWithCountJson(id = 9, tag = "Drama", showCount = 1),
                ),
            )
        }

        val genres = (http.showRepository.showGenres() as ApiResult.Success).value

        assertEquals("/api/shows/genres", requireNotNull(request).url.encodedPath)
        assertEquals(listOf(7L, 9L), genres.map { it.genreId })
        assertEquals(listOf("Comedy", "Drama"), genres.map { it.genreTag })
        assertEquals(listOf(2L, 1L), genres.map { it.showCount })
    }

    @Test
    fun `show stats decodes the total show count`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(showsStatsJson(totalShows = 3))
        }

        val stats = (http.showRepository.showStats() as ApiResult.Success).value

        assertEquals("/api/shows/stats", requireNotNull(request).url.encodedPath)
        assertEquals(3L, stats.totalShows)
    }
}
