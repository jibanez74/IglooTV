package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicRepositoryTest {

    @Test
    fun `latest albums hits the contract path with the bearer token`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(latestAlbumsJson(simpleAlbumJson(id = 7, title = "Help!")))
        }
        http.profiles.setPending("igd_test")

        val result = http.musicRepository.latestAlbums()

        val captured = requireNotNull(request)
        assertEquals("/api/music/albums/latest", captured.url.encodedPath)
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val album = (result as ApiResult.Success).value.single()
        assertEquals(7L, album.id)
        assertEquals("Help!", album.title)
        assertEquals("https://i.scdn.co/image/help.jpg", album.cover.orNull())
        assertEquals("The Beatles", album.musician.orNull())
        assertEquals(1965L, album.year.orNull())
    }

    /** The route takes none, so a stray one would be a contract invention. */
    @Test
    fun `latest albums sends no query parameters`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(latestAlbumsJson(simpleAlbumJson()))
        }

        http.musicRepository.latestAlbums()

        assertTrue(requireNotNull(request).url.parameters.isEmpty())
    }

    @Test
    fun `invalid wire nulls decode to absent values`() = runTest {
        val http = TestHttp {
            jsonResponse(latestAlbumsJson(simpleAlbumJson(cover = null, musician = null, year = null)))
        }

        val album = (http.musicRepository.latestAlbums() as ApiResult.Success).value.single()

        assertNull(album.cover.orNull())
        assertNull(album.musician.orNull())
        assertNull(album.year.orNull())
    }

    @Test
    fun `an empty music library is a success with no albums`() = runTest {
        val http = TestHttp { jsonResponse(latestAlbumsJson()) }

        val result = http.musicRepository.latestAlbums()

        assertTrue((result as ApiResult.Success).value.isEmpty())
    }

    @Test
    fun `a dead token maps to Unauthorized`() = runTest {
        val http = TestHttp {
            jsonResponse("""{"error":true,"message":"gone"}""", HttpStatusCode.Unauthorized)
        }

        val result = http.musicRepository.latestAlbums()

        assertEquals(AppError.Unauthorized, (result as ApiResult.Failure).error)
    }

    @Test
    fun `a server failure preserves the backend message`() = runTest {
        val http = TestHttp {
            jsonResponse(
                """{"error":true,"message":"music scan in progress"}""",
                HttpStatusCode.InternalServerError,
            )
        }

        val result = http.musicRepository.latestAlbums()

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("music scan in progress", error.message)
        assertEquals(500, error.status)
    }

    @Test
    fun `a success envelope with no data is Unexpected`() = runTest {
        val http = TestHttp { jsonResponse("""{"error":false,"message":"ok"}""") }

        val result = http.musicRepository.latestAlbums()

        assertTrue((result as ApiResult.Failure).error is AppError.Unexpected)
    }

    @Test
    fun `album details hits the contract path with the bearer token and decodes the payload`() =
        runTest {
            var request: HttpRequestData? = null
            val http = TestHttp {
                request = it
                jsonResponse(
                    albumDetailsJson(
                        album = albumJson(id = 7, title = "Help!", spotifyPopularity = 73.4),
                        tracks = listOf(
                            albumTrackJson(id = 900, trackIndex = 1, durationMs = 214_000, disc = 1),
                            albumTrackJson(id = 901, trackIndex = 1, durationMs = 245_000, disc = 2),
                        ),
                        trackGenres = listOf(trackGenreJson(trackId = 900, tag = "Ambient")),
                        albumGenres = listOf("Ambient", "Electronic"),
                        totalDurationMs = 459_000.0,
                    ),
                )
            }
            http.profiles.setPending("igd_test")

            val result = http.musicRepository.albumDetails(7)

            val captured = requireNotNull(request)
            assertEquals("/api/music/albums/details/7", captured.url.encodedPath)
            assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
            val data = (result as ApiResult.Success).value
            assertEquals("Help!", data.album.title)
            assertEquals(73.4, data.album.spotifyPopularity.orNull())
            // Milliseconds on the wire, delivered untouched.
            assertEquals(listOf(214_000L, 245_000L), data.tracks.map { it.duration })
            assertEquals(listOf(1L, 2L), data.tracks.map { it.disc })
            assertEquals(459_000.0, data.totalDuration, 0.0)
            assertEquals("The Beatles", data.artists.single().name)
            assertEquals("Ambient", data.trackGenres.single().tag)
            assertEquals(listOf("Ambient", "Electronic"), data.albumGenres)
        }

    @Test
    fun `album details server failure preserves the backend message`() = runTest {
        val http = TestHttp {
            jsonResponse("""{"error":true,"message":"album not found"}""", HttpStatusCode.NotFound)
        }

        val result = http.musicRepository.albumDetails(999)

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("album not found", error.message)
        assertEquals(404, error.status)
    }

    @Test
    fun `album details success envelope with no data is Unexpected`() = runTest {
        val http = TestHttp { jsonResponse("""{"error":false,"message":"ok"}""") }

        val result = http.musicRepository.albumDetails(7)

        assertTrue((result as ApiResult.Failure).error is AppError.Unexpected)
    }
}
