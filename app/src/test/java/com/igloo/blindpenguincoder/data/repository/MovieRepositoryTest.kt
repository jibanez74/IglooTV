package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.playback.hls.HlsManifestResult
import com.igloo.blindpenguincoder.playback.hls.HlsSessionSpec
import com.igloo.blindpenguincoder.playback.model.HlsAudioProfile
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_SERVER_UNREACHABLE_MESSAGE
import com.igloo.blindpenguincoder.playback.model.PLAYBACK_UNAUTHORIZED_MESSAGE
import com.igloo.blindpenguincoder.playback.model.playbackServerRefusedMessage
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MovieRepositoryTest {

    @Test
    fun `latest movies hits the contract path with the bearer token`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(latestMoviesJson(latestMovieJson(id = 5, title = "Heat")))
        }
        http.profiles.setPending("igd_test")

        val result = http.movieRepository.latestMovies()

        val captured = requireNotNull(request)
        assertEquals("/api/movies/latest", captured.url.encodedPath)
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val movies = (result as ApiResult.Success).value
        assertEquals(listOf(5L), movies.map { it.id })
        assertEquals("Heat", movies.single().title)
        assertEquals("/heat.jpg", movies.single().posterPath.orNull())
        assertEquals(1995L, movies.single().year.orNull())
    }

    @Test
    fun `the stream url is the contract path on the api base`() = runTest {
        val http = TestHttp { error("the stream url is built, never fetched") }

        assertEquals("$TEST_SERVER/movies/9/stream", http.movieRepository.movieStreamUrl(9))
    }

    // --- HLS session plumbing ---

    private fun hlsSpec(reload: Int = 0, audioProfile: HlsAudioProfile? = null) = HlsSessionSpec(
        movieId = 9,
        profileId = "remux",
        audioTypeIndex = 1,
        startSec = 90,
        sessionUuid = "5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1",
        reload = reload,
        audioProfile = audioProfile,
    )

    @Test
    fun `the hls playlist url carries the profile path and the session query`() = runTest {
        val http = TestHttp { error("the playlist url is built, never fetched") }

        assertEquals(
            "$TEST_SERVER/movies/9/hls/remux/playlist.m3u8" +
                "?playback_session=5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1&start=90&audio_track=1",
            http.movieRepository.hlsPlaylistUrl(hlsSpec()),
        )
    }

    @Test
    fun `an audio conversion rides the playlist url as the codec-channels pair`() = runTest {
        val http = TestHttp { error("the playlist url is built, never fetched") }

        assertEquals(
            "$TEST_SERVER/movies/9/hls/remux/playlist.m3u8" +
                "?playback_session=5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1&start=90&audio_track=1" +
                "&audio_codec=eac3&audio_channels=6",
            http.movieRepository.hlsPlaylistUrl(
                hlsSpec(audioProfile = HlsAudioProfile.DolbyDigitalPlus),
            ),
        )
    }

    @Test
    fun `the subtitle url shifts by the session start and omits a zero start`() = runTest {
        val http = TestHttp { error("the subtitle url is built, never fetched") }

        assertEquals(
            "$TEST_SERVER/movies/9/subtitles/2/web.vtt?start=87.417",
            http.movieRepository.movieSubtitleUrl(9, 2, 87.417),
        )
        assertEquals(
            "$TEST_SERVER/movies/9/subtitles/2/web.vtt",
            http.movieRepository.movieSubtitleUrl(9, 2, 0.0),
        )
    }

    @Test
    fun `fetching the manifest sends the contract query and reads the igloo headers`() = runTest {
        var request: HttpRequestData? = null
        val body = ByteReadChannel("#EXTM3U")
        val http = TestHttp {
            request = it
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(
                    "X-Igloo-Effective-Profile" to listOf("1080p_8mbps"),
                    "X-Igloo-Actual-Start" to listOf("87.417"),
                ),
            )
        }
        http.profiles.setPending("igd_test")

        val result = http.movieRepository.fetchHlsManifest(hlsSpec())

        val captured = requireNotNull(request)
        assertEquals("/api/movies/9/hls/remux/playlist.m3u8", captured.url.encodedPath)
        assertEquals("5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1", captured.url.parameters["playback_session"])
        assertEquals("90", captured.url.parameters["start"])
        assertEquals("1", captured.url.parameters["audio_track"])
        // A legacy spec must not leak half a conversion pair onto the wire.
        assertEquals(null, captured.url.parameters["audio_codec"])
        assertEquals(null, captured.url.parameters["audio_channels"])
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val timeouts = requireNotNull(captured.getCapabilityOrNull(HttpTimeoutCapability))
        assertEquals(45_000L, timeouts.requestTimeoutMillis)
        assertEquals(45_000L, timeouts.socketTimeoutMillis)
        assertEquals(HlsManifestResult.Ready("1080p_8mbps", 87.417), result)
        assertTrue("a successful manifest body must be drained", body.isClosedForRead)
    }

    @Test
    fun `a busy manifest surfaces the retry hint instead of failing`() = runTest {
        val body = ByteReadChannel("conversion capacity exhausted")
        val http = TestHttp {
            respond(
                content = body,
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf("Retry-After" to listOf("5")),
            )
        }

        assertEquals(
            HlsManifestResult.Busy(retryAfterSec = 5),
            http.movieRepository.fetchHlsManifest(hlsSpec()),
        )
        assertTrue("a non-success manifest body must be drained", body.isClosedForRead)
    }

    @Test
    fun `a lost session is a protocol state the controller can retry, not a failure`() = runTest {
        val http = TestHttp {
            respond(
                content = ByteReadChannel("""{"error":true,"message":"not found"}"""),
                status = HttpStatusCode.NotFound,
            )
        }

        assertEquals(HlsManifestResult.Lost, http.movieRepository.fetchHlsManifest(hlsSpec()))
    }

    @Test
    fun `a rejected credential fails the manifest as unauthorized`() = runTest {
        val http = TestHttp {
            respond(
                content = ByteReadChannel("""{"error":true,"message":"unauthorized"}"""),
                status = HttpStatusCode.Unauthorized,
            )
        }

        val result = http.movieRepository.fetchHlsManifest(hlsSpec())

        val failed = result as HlsManifestResult.Failed
        assertTrue(failed.unauthorized)
        assertEquals(PLAYBACK_UNAUTHORIZED_MESSAGE, failed.message)
    }

    @Test
    fun `any other refusal carries its status into the message`() = runTest {
        val http = TestHttp {
            respond(
                content = ByteReadChannel("""{"error":true,"message":"boom"}"""),
                status = HttpStatusCode.InternalServerError,
            )
        }

        assertEquals(
            HlsManifestResult.Failed(playbackServerRefusedMessage(500)),
            http.movieRepository.fetchHlsManifest(hlsSpec()),
        )
    }

    @Test
    fun `a transport failure reads as unreachable rather than crashing the player`() = runTest {
        val http = TestHttp { throw IOException("connection reset") }

        assertEquals(
            HlsManifestResult.Failed(PLAYBACK_SERVER_UNREACHABLE_MESSAGE),
            http.movieRepository.fetchHlsManifest(hlsSpec()),
        )
    }

    @Test
    fun `a connection timeout fails immediately as unreachable`() = runTest {
        val http = TestHttp { throw ConnectTimeoutException(TEST_SERVER) }

        assertEquals(
            HlsManifestResult.Failed(PLAYBACK_SERVER_UNREACHABLE_MESSAGE),
            http.movieRepository.fetchHlsManifest(hlsSpec()),
        )
    }

    @Test
    fun `a wrapped connection timeout fails immediately as unreachable`() = runTest {
        val http = TestHttp {
            throw RuntimeException("engine failed", ConnectTimeoutException(TEST_SERVER))
        }

        assertEquals(
            HlsManifestResult.Failed(PLAYBACK_SERVER_UNREACHABLE_MESSAGE),
            http.movieRepository.fetchHlsManifest(hlsSpec()),
        )
    }

    @Test
    fun `a request timeout remains busy for capacity retry`() = runTest {
        val http = TestHttp {
            throw HttpRequestTimeoutException(TEST_SERVER, 45_000L)
        }

        assertEquals(
            HlsManifestResult.Busy(retryAfterSec = null),
            http.movieRepository.fetchHlsManifest(hlsSpec()),
        )
    }

    @Test
    fun `a programming error surfaces instead of masquerading as a transport failure`() = runTest {
        val http = TestHttp { error("server address requested before setup completed") }

        try {
            http.movieRepository.fetchHlsManifest(hlsSpec())
            fail("expected the programming error to surface")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("server address"))
        }
    }

    @Test
    fun `stopping a session posts the uuid to the contract path`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse("""{"error":false,"message":"stopped"}""")
        }

        http.movieRepository.stopHlsSession(9, "5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1")

        val captured = requireNotNull(request)
        assertEquals("/api/movies/9/hls/session/stop", captured.url.encodedPath)
        assertEquals(
            "5e0f8f2a-9df1-4f2f-8a53-0d9f8f2a9df1",
            captured.url.parameters["playback_session"],
        )
    }

    @Test
    fun `invalid wire nulls decode to absent values`() = runTest {
        val http = TestHttp {
            jsonResponse(latestMoviesJson(latestMovieJson(posterPath = null, year = null)))
        }

        val movie = (http.movieRepository.latestMovies() as ApiResult.Success).value.single()

        assertNull(movie.posterPath.orNull())
        assertNull(movie.year.orNull())
    }

    @Test
    fun `an empty library is a success with no movies`() = runTest {
        val http = TestHttp { jsonResponse(latestMoviesJson()) }

        val result = http.movieRepository.latestMovies()

        assertTrue((result as ApiResult.Success).value.isEmpty())
    }

    @Test
    fun `a dead token maps to Unauthorized`() = runTest {
        val http = TestHttp {
            jsonResponse("""{"error":true,"message":"gone"}""", HttpStatusCode.Unauthorized)
        }

        val result = http.movieRepository.latestMovies()

        assertEquals(AppError.Unauthorized, (result as ApiResult.Failure).error)
    }

    @Test
    fun `a server failure preserves the backend message`() = runTest {
        val http = TestHttp {
            jsonResponse(
                """{"error":true,"message":"scan in progress"}""",
                HttpStatusCode.InternalServerError,
            )
        }

        val result = http.movieRepository.latestMovies()

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("scan in progress", error.message)
        assertEquals(500, error.status)
    }

    @Test
    fun `a success envelope with no data is Unexpected`() = runTest {
        val http = TestHttp { jsonResponse("""{"error":false,"message":"ok"}""") }

        val result = http.movieRepository.latestMovies()

        assertTrue((result as ApiResult.Failure).error is AppError.Unexpected)
    }

    @Test
    fun `continue watching hits the contract path with the bearer token`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(
                continueWatchingMoviesJson(
                    continueWatchingMovieJson(
                        id = 5,
                        title = "Heat",
                        progressSec = 1800.0,
                        durationSec = 10200.0,
                    ),
                ),
            )
        }
        http.profiles.setPending("igd_test")

        val result = http.movieRepository.continueWatchingMovies()

        val captured = requireNotNull(request)
        assertEquals("/api/movies/continue-watching", captured.url.encodedPath)
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val movie = (result as ApiResult.Success).value.single()
        assertEquals(5L, movie.id)
        assertEquals("Heat", movie.title)
        assertEquals("/heat.jpg", movie.posterPath.orNull())
        assertEquals(1995L, movie.year.orNull())
        assertEquals(1800.0, movie.progressSec, 0.0)
        assertEquals(10200.0, movie.durationSec, 0.0)
    }

    @Test
    fun `nothing in progress is a success with no movies`() = runTest {
        val http = TestHttp { jsonResponse(continueWatchingMoviesJson()) }

        val result = http.movieRepository.continueWatchingMovies()

        assertTrue((result as ApiResult.Success).value.isEmpty())
    }

    @Test
    fun `a dead token maps continue watching to Unauthorized`() = runTest {
        val http = TestHttp {
            jsonResponse("""{"error":true,"message":"gone"}""", HttpStatusCode.Unauthorized)
        }

        val result = http.movieRepository.continueWatchingMovies()

        assertEquals(AppError.Unauthorized, (result as ApiResult.Failure).error)
    }

    @Test
    fun `a continue watching server failure preserves the backend message`() = runTest {
        val http = TestHttp {
            jsonResponse(
                """{"error":true,"message":"scan in progress"}""",
                HttpStatusCode.InternalServerError,
            )
        }

        val result = http.movieRepository.continueWatchingMovies()

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("scan in progress", error.message)
        assertEquals(500, error.status)
    }

    @Test
    fun `a continue watching success envelope with no data is Unexpected`() = runTest {
        val http = TestHttp { jsonResponse("""{"error":false,"message":"ok"}""") }

        val result = http.movieRepository.continueWatchingMovies()

        assertTrue((result as ApiResult.Failure).error is AppError.Unexpected)
    }

    @Test
    fun `movies in theaters hits the contract path with the bearer token`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(
                theaterMoviesJson(
                    theaterMovieJson(id = 5, title = "Heat 2", voteAverage = 7.9),
                ),
            )
        }
        http.profiles.setPending("igd_test")

        val result = http.movieRepository.moviesInTheaters()

        val captured = requireNotNull(request)
        assertEquals("/api/tmdb/movies/in-theaters", captured.url.encodedPath)
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val movie = (result as ApiResult.Success).value.single()
        assertEquals(5, movie.id)
        assertEquals("Heat 2", movie.title)
        assertEquals("2026-08-01", movie.releaseDate)
        assertEquals("/heat2.jpg", movie.posterPath)
        assertEquals(7.9, movie.voteAverage, 0.0)
    }

    @Test
    fun `one TMDB movie hits the contract path with the bearer token`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(tmdbMovieJson(id = 969681, title = "Heat 2"))
        }
        http.profiles.setPending("igd_test")

        val result = http.movieRepository.tmdbMovie(969681)

        val captured = requireNotNull(request)
        assertEquals("/api/tmdb/movies/969681", captured.url.encodedPath)
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val movie = (result as ApiResult.Success).value
        assertEquals(969681, movie.id)
        assertEquals("Heat 2", movie.title)
    }

    @Test
    fun `a TMDB movie response with no data fails instead of rendering an empty page`() = runTest {
        val http = TestHttp { jsonResponse("""{"error":false,"message":"tmdb movie"}""") }

        val result = http.movieRepository.tmdbMovie(1)

        assertTrue((result as ApiResult.Failure).error is AppError.Unexpected)
    }

    @Test
    fun `no movies in theaters is a success with no movies`() = runTest {
        val http = TestHttp { jsonResponse(theaterMoviesJson()) }

        val result = http.movieRepository.moviesInTheaters()

        assertTrue((result as ApiResult.Success).value.isEmpty())
    }

    @Test
    fun `an in-theaters server failure preserves the backend message`() = runTest {
        val http = TestHttp {
            jsonResponse(
                """{"error":true,"message":"tmdb unavailable"}""",
                HttpStatusCode.InternalServerError,
            )
        }

        val result = http.movieRepository.moviesInTheaters()

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("tmdb unavailable", error.message)
        assertEquals(500, error.status)
    }

    @Test
    fun `an in-theaters success envelope with no data is Unexpected`() = runTest {
        val http = TestHttp { jsonResponse("""{"error":false,"message":"ok"}""") }

        val result = http.movieRepository.moviesInTheaters()

        assertTrue((result as ApiResult.Failure).error is AppError.Unexpected)
    }

    @Test
    fun `movie details hits the contract path with the bearer token`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(movieDetailsJson(id = 5, title = "Heat"))
        }
        http.profiles.setPending("igd_test")

        val result = http.movieRepository.movieDetails(5)

        val captured = requireNotNull(request)
        assertEquals("/api/movies/details/5", captured.url.encodedPath)
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val details = (result as ApiResult.Success).value
        assertEquals(5L, details.movie.id)
        assertEquals("Heat", details.movie.title)
        assertEquals("/heat-backdrop.jpg", details.movie.backdropPath?.orNull())
        assertEquals(170L, details.movie.runTime?.orNull())
        assertEquals(8.2, requireNotNull(details.movie.criticRating?.orNull()), 0.0)
        assertTrue(details.cast.isEmpty())
    }

    @Test
    fun `movie details wire nulls decode to absent values`() = runTest {
        val http = TestHttp {
            jsonResponse(
                movieDetailsJson(
                    backdropPath = null,
                    overview = null,
                    year = null,
                    certification = null,
                    runTimeMinutes = null,
                    criticRating = null,
                ),
            )
        }

        val movie = (http.movieRepository.movieDetails(1) as ApiResult.Success).value.movie

        assertNull(movie.backdropPath?.orNull())
        assertNull(movie.overview?.orNull())
        assertNull(movie.year?.orNull())
        assertNull(movie.certification?.orNull())
        assertNull(movie.runTime?.orNull())
        assertNull(movie.criticRating?.orNull())
    }

    @Test
    fun `a movie details success envelope with no data is Unexpected`() = runTest {
        val http = TestHttp { jsonResponse("""{"error":false,"message":"ok"}""") }

        val result = http.movieRepository.movieDetails(1)

        assertTrue((result as ApiResult.Failure).error is AppError.Unexpected)
    }

    @Test
    fun `movie details decodes populated related lists`() = runTest {
        val http = TestHttp {
            jsonResponse(
                movieDetailsJson(
                    cast = listOf(castMemberJson(artistName = "Al Pacino", character = "Vincent Hanna")),
                    crew = listOf(crewMemberJson(job = "Director", artistName = "Michael Mann")),
                    genres = listOf(movieGenreJson(tag = "Crime")),
                    productionCompanies = listOf(productionCompanyJson(name = "Regency Enterprises")),
                    extraVideos = listOf(extraVideoJson(title = "Heat - Trailer")),
                ),
            )
        }

        val details = (http.movieRepository.movieDetails(1) as ApiResult.Success).value

        assertEquals("Al Pacino", details.cast.single().artistName)
        assertEquals("Vincent Hanna", details.cast.single().character)
        assertEquals("Michael Mann", details.crew.single().artistName)
        assertEquals("Crime", details.genres.single().tag)
        assertEquals("Regency Enterprises", details.productionCompanies.single().name)
        assertEquals("Heat - Trailer", details.extraVideos.single().title)
    }

    @Test
    fun `technical details hits the contract path and decodes streams`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(
                technicalDetailsJson(
                    videoStreams = listOf(videoStreamJson(width = 3840, height = 1600)),
                    audioStreams = listOf(audioStreamJson(channels = 6)),
                    subtitles = listOf(subtitleJson()),
                    chapters = listOf(chapterJson(startTimeSec = 193)),
                ),
            )
        }
        http.profiles.setPending("igd_test")

        val result = http.movieRepository.movieTechnicalDetails(5)

        val captured = requireNotNull(request)
        assertEquals("/api/movies/5/technical-details", captured.url.encodedPath)
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val data = (result as ApiResult.Success).value
        assertEquals(3840L, data.videoStreams.single().width)
        assertEquals(6L, data.audioStreams.single().channels)
        assertEquals("subrip", data.subtitles.single().codec)
        assertEquals(193L, data.chapters.single().startTime)
    }

    @Test
    fun `a technical details server failure preserves the backend message`() = runTest {
        val http = TestHttp {
            jsonResponse(
                """{"error":true,"message":"probe failed"}""",
                HttpStatusCode.InternalServerError,
            )
        }

        val result = http.movieRepository.movieTechnicalDetails(1)

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("probe failed", error.message)
    }

    @Test
    fun `watch progress hits the contract path and decodes plain nulls`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(watchProgressJson())
        }

        val result = http.movieRepository.movieWatchProgress(5)

        assertEquals("/api/movies/5/watch-progress", requireNotNull(request).url.encodedPath)
        val progress = (result as ApiResult.Success).value
        assertNull(progress.progressSec)
        assertNull(progress.durationSec)
        assertEquals(false, progress.watched)
    }

    @Test
    fun `watch progress decodes a saved position`() = runTest {
        val http = TestHttp {
            jsonResponse(
                watchProgressJson(
                    progressSec = 1800.0,
                    durationSec = 7200.0,
                    updatedAt = "2026-08-14T00:00:00Z",
                ),
            )
        }

        val progress = (http.movieRepository.movieWatchProgress(1) as ApiResult.Success).value

        assertEquals(1800.0, requireNotNull(progress.progressSec), 0.0)
        assertEquals(7200.0, requireNotNull(progress.durationSec), 0.0)
    }

    @Test
    fun `set watched PUTs the desired value and decodes the confirmation`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(watchedUpdateJson(movieId = 5, watched = true))
        }

        val result = http.movieRepository.setMovieWatched(5, watched = true)

        val captured = requireNotNull(request)
        assertEquals("/api/movies/5/watch-progress/watched", captured.url.encodedPath)
        assertEquals("PUT", captured.method.value)
        val data = (result as ApiResult.Success).value
        assertEquals(5L, data.movieId)
        assertTrue(data.watched)
    }

    @Test
    fun `like status hits the contract path`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(likeStatusJson(isLiked = true))
        }

        val result = http.movieRepository.movieLikeStatus(5)

        assertEquals("/api/movies/5/like-status", requireNotNull(request).url.encodedPath)
        assertTrue((result as ApiResult.Success).value.isLiked)
    }

    @Test
    fun `like toggle POSTs with no body and decodes the new state`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(likeToggleJson(movieId = 5, isLiked = true))
        }

        val result = http.movieRepository.toggleMovieLike(5)

        val captured = requireNotNull(request)
        assertEquals("/api/movies/5/like", captured.url.encodedPath)
        assertEquals("POST", captured.method.value)
        val data = (result as ApiResult.Success).value
        assertEquals(5L, data.movieId)
        assertTrue(data.isLiked)
    }

    @Test
    fun `a like toggle failure maps to an Api error`() = runTest {
        val http = TestHttp {
            jsonResponse(
                """{"error":true,"message":"like failed"}""",
                HttpStatusCode.InternalServerError,
            )
        }

        val result = http.movieRepository.toggleMovieLike(1)

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("like failed", error.message)
    }
}
