package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRepositoryTest {

    @Test
    fun `search all hits the contract path with the query and the bearer token`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(
                searchAllJson(
                    query = "help",
                    movies = searchSectionJson(
                        listOf(movieLibraryItemJson(id = 3, title = "Help!", year = 1965)),
                        total = 12,
                    ),
                    albums = searchSectionJson(listOf(simpleAlbumJson(id = 5, cover = null))),
                    tracks = searchSectionJson(listOf(trackListItemJson(id = 9, title = "Help!")), total = 1),
                ),
            )
        }
        http.profiles.setPending("igd_test")

        val result = http.searchRepository.searchAll("help")

        val captured = requireNotNull(request)
        assertEquals("/api/search", captured.url.encodedPath)
        assertEquals("help", captured.url.parameters["q"])
        assertEquals(listOf("q"), captured.url.parameters.names().toList())
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val data = (result as ApiResult.Success).value
        assertEquals(12L, data.movies.total)
        assertEquals(3L, data.movies.results.single().id)
        assertEquals(1965L, data.movies.results.single().year.orNull())
        // An empty section is a list, never null.
        assertEquals(0L, data.shows.total)
        assertTrue(data.shows.results.isEmpty())
        assertEquals("The Beatles", data.albums.results.single().musician.orNull())
        assertNull(data.albums.results.single().cover.orNull())
        assertEquals(9L, data.tracks.results.single().id)
        assertEquals(125_000L, data.tracks.results.single().duration)
    }

    @Test
    fun `every category search sends q page and per_page to its own route`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = TestHttp {
            requests += it
            jsonResponse(searchPageJson(emptyList(), query = "x", page = 2, totalPages = 0))
        }

        http.searchRepository.searchMovies("x", page = 2, perPage = 48)
        http.searchRepository.searchShows("x", page = 2, perPage = 48)
        http.searchRepository.searchAlbums("x", page = 2, perPage = 48)
        http.searchRepository.searchMusicians("x", page = 2, perPage = 48)
        http.searchRepository.searchTracks("x", page = 2, perPage = 48)

        assertEquals(
            listOf(
                "/api/search/movies",
                "/api/search/shows",
                "/api/search/albums",
                "/api/search/musicians",
                "/api/search/tracks",
            ),
            requests.map { it.url.encodedPath },
        )
        requests.forEach { request ->
            assertEquals("x", request.url.parameters["q"])
            assertEquals("2", request.url.parameters["page"])
            assertEquals("48", request.url.parameters["per_page"])
            assertNull(request.url.parameters["sort"])
        }
    }

    @Test
    fun `a category page decodes its rows and paging counts`() = runTest {
        val http = TestHttp {
            jsonResponse(
                searchPageJson(
                    results = listOf(simpleMusicianJson(id = 4, name = "The Beatles", thumb = null)),
                    query = "beat",
                    total = 70,
                    page = 2,
                    totalPages = 3,
                ),
            )
        }

        val page = (http.searchRepository.searchMusicians("beat", 2, 24) as ApiResult.Success).value

        assertEquals(70L, page.total)
        assertEquals(3L, page.totalPages)
        val musician = page.results.single()
        assertEquals("The Beatles", musician.name)
        assertNull(musician.thumb.orNull())
    }
}
