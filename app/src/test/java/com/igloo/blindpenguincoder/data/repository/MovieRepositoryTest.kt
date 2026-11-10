package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.SortOrder
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `the movies library request carries page, per page and sort as query parameters`() =
        runTest {
            var request: HttpRequestData? = null
            val http = TestHttp {
                request = it
                jsonResponse(moviesLibraryJson(page = 2, total = 96, totalPages = 2))
            }
            http.profiles.setPending("igd_test")

            http.movieRepository.moviesLibrary(page = 2, perPage = 48, sort = SortOrder.Ascending)

            val captured = requireNotNull(request)
            assertEquals("/api/movies/library", captured.url.encodedPath)
            assertEquals("2", captured.url.parameters["page"])
            assertEquals("48", captured.url.parameters["per_page"])
            // The wire spelling, not the Kotlin constant name.
            assertEquals("asc", captured.url.parameters["sort"])
            assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        }

    @Test
    fun `a library page decodes its items and its paging counts`() = runTest {
        val http = TestHttp {
            jsonResponse(
                moviesLibraryJson(
                    page = 1,
                    total = 96,
                    totalPages = 2,
                    movies = arrayOf(
                        movieLibraryItemJson(id = 5, title = "Heat"),
                        // A movie the scanner never matched: no poster, no year, no rating.
                        movieLibraryItemJson(
                            id = 6,
                            title = "Unmatched",
                            posterPath = null,
                            year = null,
                            certification = null,
                        ),
                    ),
                ),
            )
        }

        val page = (http.movieRepository.moviesLibrary(1, 48, SortOrder.Ascending)
            as ApiResult.Success).value

        assertEquals(listOf(5L, 6L), page.movies.map { it.id })
        assertEquals("/heat.jpg", page.movies.first().posterPath.orNull())
        assertEquals(1995L, page.movies.first().year.orNull())
        assertNull(page.movies.last().posterPath.orNull())
        assertNull(page.movies.last().year.orNull())
        assertEquals(96L, page.total)
        assertEquals(2L, page.totalPages)
    }

    @Test
    fun `movie stats decodes the total movie count`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(moviesStatsJson(totalMovies = 1234))
        }

        val stats = (http.movieRepository.movieStats() as ApiResult.Success).value

        assertEquals("/api/movies/stats", requireNotNull(request).url.encodedPath)
        assertEquals(1234L, stats.totalMovies)
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
                continueWatchingJson(
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

        val result = http.movieRepository.continueWatching()

        val captured = requireNotNull(request)
        assertEquals("/api/continue-watching", captured.url.encodedPath)
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
    fun `continue watching keeps the server order and both kinds`() = runTest {
        val http = TestHttp {
            jsonResponse(
                continueWatchingJson(
                    continueWatchingEpisodeJson(id = 900, showTitle = "Severance"),
                    continueWatchingMovieJson(id = 5, title = "Heat"),
                    continueWatchingMovieJson(id = 2, title = "Arrival", posterPath = null, year = null),
                ),
            )
        }

        val result = http.movieRepository.continueWatching()

        val items = (result as ApiResult.Success).value
        assertEquals(listOf("Severance", "Heat", "Arrival"), items.map { it.title })
        assertEquals(listOf(true, false, false), items.map { it.isEpisode })
        assertEquals(listOf(false, true, true), items.map { it.isMovie })
        val episode = items.first()
        assertEquals(600.0, episode.progressSec, 0.0)
        assertEquals(1L, episode.seasonNumber)
        assertEquals(3L, episode.episodeNumber)
        assertEquals("In Perpetuity", episode.episodeName)
        assertNull(items[1].episodeName)
        assertNull(items[2].posterPath.orNull())
        assertNull(items[2].year.orNull())
    }

    @Test
    fun `nothing in progress is a success with no movies`() = runTest {
        val http = TestHttp { jsonResponse(continueWatchingJson()) }

        val result = http.movieRepository.continueWatching()

        assertTrue((result as ApiResult.Success).value.isEmpty())
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
        assertEquals(1995L, details.movie.year?.orNull())
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
                    tagLine = null,
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
        assertNull(movie.tagLine?.orNull())
        assertNull(movie.year?.orNull())
        assertNull(movie.certification?.orNull())
        assertNull(movie.runTime?.orNull())
        assertNull(movie.criticRating?.orNull())
    }

    @Test
    fun `movie details decodes populated related lists`() = runTest {
        val http = TestHttp {
            jsonResponse(
                movieDetailsJson(
                    cast = listOf(
                        castMemberJson(
                            artistName = "Al Pacino",
                            character = "Vincent Hanna",
                            castOrder = 0,
                            artistProfile = "/pacino.jpg",
                        ),
                    ),
                    crew = listOf(
                        crewMemberJson(job = "Director", department = "Directing", artistName = "Michael Mann"),
                    ),
                    genres = listOf(genreJson(tag = "Crime")),
                    productionCompanies = listOf(productionCompanyJson(name = "Regency Enterprises")),
                    extraVideos = listOf(
                        extraVideoJson(
                            title = "Heat - Trailer",
                            key = "0xbkYZbdIVw",
                            type = "trailer",
                            site = "youtube",
                        ),
                    ),
                ),
            )
        }

        val details = (http.movieRepository.movieDetails(1) as ApiResult.Success).value

        val cast = details.cast.single()
        assertEquals("Al Pacino", cast.artistName)
        assertEquals("Vincent Hanna", cast.character)
        assertEquals(0L, cast.castOrder)
        assertEquals("/pacino.jpg", cast.artistProfile?.orNull())
        val crew = details.crew.single()
        assertEquals("Michael Mann", crew.artistName)
        assertEquals("Director", crew.job)
        assertEquals("Directing", crew.department)
        assertEquals("Crime", details.genres.single().tag)
        assertEquals("Regency Enterprises", details.productionCompanies.single().name)
        val video = details.extraVideos.single()
        assertEquals("Heat - Trailer", video.title)
        assertEquals("0xbkYZbdIVw", video.key)
        assertEquals("trailer", video.type)
        assertEquals("youtube", video.site)
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
        assertEquals("video/x-matroska", data.movie.mimeType)
        val video = data.videoStreams.single()
        assertEquals(3840L, video.width)
        assertEquals(1600L, video.height)
        assertEquals("smpte2084", video.colorTransfer?.orNull())
        val audio = data.audioStreams.single()
        assertEquals(6L, audio.channels)
        assertEquals("5.1(side)", audio.channelLayout?.orNull())
        assertTrue(audio.isDefault)
        val subtitle = data.subtitles.single()
        assertEquals("subrip", subtitle.codec)
        assertFalse(subtitle.isForced)
        assertEquals(193L, data.chapters.single().startTime)
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
        assertTrue(data.isLiked)
    }
}
