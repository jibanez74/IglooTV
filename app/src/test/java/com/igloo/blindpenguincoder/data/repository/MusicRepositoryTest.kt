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

    @Test
    fun `album details rows carry the musician id the row menu needs`() = runTest {
        val http = TestHttp {
            jsonResponse(
                albumDetailsJson(
                    tracks = listOf(
                        albumTrackJson(id = 900, musicianId = 4),
                        albumTrackJson(id = 901, musicianId = null),
                    ),
                ),
            )
        }

        val data = (http.musicRepository.albumDetails(7) as ApiResult.Success).value

        assertEquals(listOf(4L, null), data.tracks.map { it.musicianId.orNull() })
    }

    // --- paged lists ---

    @Test
    fun `albums hits the contract path with page and per_page and decodes the counts`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(
                albumsJson(
                    albums = listOf(simpleAlbumJson(id = 7), simpleAlbumJson(id = 8)),
                    total = 100,
                    page = 2,
                    perPage = 48,
                    totalPages = 3,
                ),
            )
        }
        http.profiles.setPending("igd_test")

        val result = http.musicRepository.albums(page = 2, perPage = 48)

        val captured = requireNotNull(request)
        assertEquals("/api/music/albums", captured.url.encodedPath)
        assertEquals("2", captured.url.parameters["page"])
        assertEquals("48", captured.url.parameters["per_page"])
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val data = (result as ApiResult.Success).value
        assertEquals(listOf(7L, 8L), data.albums.map { it.id })
        assertEquals(100L, data.total)
        assertEquals(3L, data.totalPages)
    }

    @Test
    fun `musicians hits the contract path and decodes rows without a sort name`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(
                musiciansJson(
                    musicians = listOf(
                        simpleMusicianJson(id = 4, name = "The Beatles"),
                        simpleMusicianJson(id = 5, name = "Untagged", thumb = null),
                    ),
                    total = 60,
                    totalPages = 2,
                ),
            )
        }

        val result = http.musicRepository.musicians(page = 1, perPage = 48)

        val captured = requireNotNull(request)
        assertEquals("/api/music/musicians", captured.url.encodedPath)
        assertEquals("1", captured.url.parameters["page"])
        assertEquals("48", captured.url.parameters["per_page"])
        val data = (result as ApiResult.Success).value
        assertEquals(listOf("The Beatles", "Untagged"), data.musicians.map { it.name })
        assertEquals("https://i.scdn.co/image/beatles.jpg", data.musicians[0].thumb.orNull())
        assertNull(data.musicians[1].thumb.orNull())
        assertEquals(3L, data.musicians[0].albumCount)
        assertEquals(40L, data.musicians[0].trackCount)
        assertEquals(60L, data.total)
        assertEquals(2L, data.totalPages)
    }

    @Test
    fun `musicians server failure preserves the backend message`() = runTest {
        val http = TestHttp {
            jsonResponse("""{"error":true,"message":"nope"}""", HttpStatusCode.InternalServerError)
        }

        val result = http.musicRepository.musicians(page = 1, perPage = 48)

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("nope", error.message)
    }

    @Test
    fun `musician details hits the contract path and decodes the typed payload`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(
                musicianDetailsJson(
                    musician = musicianJson(id = 4, spotifyPopularity = 88.0, spotifyFollowers = 25_000_000),
                    albums = listOf(musicianAlbumJson(id = 1, trackCount = 14)),
                    tracks = listOf(
                        musicianTrackJson(id = 900, durationMs = 125_000),
                        musicianTrackJson(id = 901, albumId = null, albumTitle = null, albumCover = null),
                    ),
                    genres = listOf("Rock", "Pop"),
                    totalDurationMs = 250_000.0,
                ),
            )
        }

        val result = http.musicRepository.musicianDetails(4)

        assertEquals("/api/music/musicians/4", requireNotNull(request).url.encodedPath)
        val data = (result as ApiResult.Success).value
        assertEquals("The Beatles", data.musician.name)
        assertEquals(88.0, data.musician.spotifyPopularity.orNull())
        assertEquals(25_000_000L, data.musician.spotifyFollowers.orNull())
        assertEquals(14L, data.albums.single().trackCount)
        assertEquals(listOf(125_000L, 125_000L), data.tracks.map { it.duration })
        assertEquals(1L, data.tracks[0].albumId.orNull())
        assertNull(data.tracks[1].albumId.orNull())
        assertEquals(listOf("Rock", "Pop"), data.genres)
        assertEquals(250_000.0, data.totalDuration, 0.0)
    }

    @Test
    fun `musician details not found preserves the backend message`() = runTest {
        val http = TestHttp {
            jsonResponse("""{"error":true,"message":"musician not found"}""", HttpStatusCode.NotFound)
        }

        val result = http.musicRepository.musicianDetails(999)

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("musician not found", error.message)
        assertEquals(404, error.status)
    }

    // --- track list ---

    /** Not `page`/`per_page`: this route is the one music list that pages by window. */
    @Test
    fun `tracks sends limit and offset and decodes the cursor`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(
                tracksJson(
                    tracks = listOf(
                        trackListItemJson(id = 900),
                        trackListItemJson(
                            id = 901,
                            albumId = null,
                            albumTitle = null,
                            albumCover = null,
                            musicianId = null,
                            musicianName = null,
                        ),
                    ),
                    total = 150,
                    offset = 50,
                    limit = 50,
                    hasMore = true,
                ),
            )
        }
        http.profiles.setPending("igd_test")

        val result = http.musicRepository.tracks(limit = 50, offset = 50)

        val captured = requireNotNull(request)
        assertEquals("/api/music/tracks", captured.url.encodedPath)
        assertEquals("50", captured.url.parameters["limit"])
        assertEquals("50", captured.url.parameters["offset"])
        assertNull(captured.url.parameters["page"])
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val data = (result as ApiResult.Success).value
        assertEquals(listOf(900L, 901L), data.tracks.map { it.id })
        // Milliseconds on the wire, delivered untouched.
        assertEquals(125_000L, data.tracks[0].duration)
        assertEquals("The Beatles", data.tracks[0].musicianName.orNull())
        assertEquals(1L, data.tracks[0].albumId.orNull())
        assertNull(data.tracks[1].musicianName.orNull())
        assertNull(data.tracks[1].albumId.orNull())
        assertEquals(150L, data.total)
        assertEquals(50L, data.offset)
        assertTrue(data.hasMore)
    }

    @Test
    fun `tracks server failure preserves the backend message`() = runTest {
        val http = TestHttp {
            jsonResponse("""{"error":true,"message":"failed to fetch tracks"}""", HttpStatusCode.InternalServerError)
        }

        val result = http.musicRepository.tracks(limit = 50, offset = 0)

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("failed to fetch tracks", error.message)
    }

    @Test
    fun `shuffle sends limit and a comma-joined exclude list`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(shuffleTracksJson(trackListItemJson(id = 12), trackListItemJson(id = 34)))
        }

        val result = http.musicRepository.shuffleTracks(limit = 50, exclude = listOf(1L, 2L, 3L))

        val captured = requireNotNull(request)
        assertEquals("/api/music/tracks/shuffle", captured.url.encodedPath)
        assertEquals("50", captured.url.parameters["limit"])
        assertEquals("1,2,3", captured.url.parameters["exclude"])
        assertEquals(listOf(12L, 34L), (result as ApiResult.Success).value.tracks.map { it.id })
    }

    /** The first batch has nothing to exclude; an empty `exclude=` would be a contract invention. */
    @Test
    fun `shuffle omits exclude when there is nothing to exclude`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(shuffleTracksJson())
        }

        val result = http.musicRepository.shuffleTracks(limit = 50, exclude = emptyList())

        assertNull(requireNotNull(request).url.parameters["exclude"])
        assertTrue((result as ApiResult.Success).value.tracks.isEmpty())
    }

    // --- likes and stats ---

    @Test
    fun `liked track ids decode to a set`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(likedTrackIdsJson(3, 5, 3))
        }

        val result = http.musicRepository.likedTrackIds()

        assertEquals("/api/music/tracks/liked-ids", requireNotNull(request).url.encodedPath)
        assertEquals(setOf(3L, 5L), (result as ApiResult.Success).value)
    }

    @Test
    fun `a like toggle posts to the contract path with no body and returns the new state`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(trackLikeToggleJson(trackId = 900, isLiked = false))
        }
        http.profiles.setPending("igd_test")

        val result = http.musicRepository.toggleTrackLike(900)

        val captured = requireNotNull(request)
        assertEquals("/api/music/tracks/900/like", captured.url.encodedPath)
        assertEquals("POST", captured.method.value)
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val data = (result as ApiResult.Success).value
        assertEquals(900L, data.trackId)
        assertEquals(false, data.isLiked)
    }

    @Test
    fun `a like toggle failure maps to an Api error`() = runTest {
        val http = TestHttp {
            jsonResponse("""{"error":true,"message":"invalid track id"}""", HttpStatusCode.BadRequest)
        }

        val result = http.musicRepository.toggleTrackLike(0)

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("invalid track id", error.message)
        assertEquals(400, error.status)
    }

    @Test
    fun `music stats decode the three counts`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(musicStatsJson(albums = 12, tracks = 150, musicians = 9))
        }

        val result = http.musicRepository.musicStats()

        assertEquals("/api/music/stats", requireNotNull(request).url.encodedPath)
        val stats = (result as ApiResult.Success).value
        assertEquals(12L, stats.totalAlbums)
        assertEquals(150L, stats.totalTracks)
        assertEquals(9L, stats.totalMusicians)
    }

    @Test
    fun `a paged success envelope with no data is Unexpected`() = runTest {
        val http = TestHttp { jsonResponse("""{"error":false,"message":"ok"}""") }

        assertTrue((http.musicRepository.tracks(50, 0) as ApiResult.Failure).error is AppError.Unexpected)
        assertTrue((http.musicRepository.musicians(1, 48) as ApiResult.Failure).error is AppError.Unexpected)
        assertTrue((http.musicRepository.likedTrackIds() as ApiResult.Failure).error is AppError.Unexpected)
    }
}
