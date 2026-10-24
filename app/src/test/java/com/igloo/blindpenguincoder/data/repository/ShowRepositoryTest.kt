package com.igloo.blindpenguincoder.data.repository

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.SortOrder
import io.ktor.client.engine.mock.toByteArray
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

        val result =
            http.showRepository.genreShows(7, page = 3, perPage = 48, sort = SortOrder.Ascending)
        val page = (result as ApiResult.Success).value

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

    @Test
    fun `latest shows hit the contract path and keep the server's order`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(
                latestShowsJson(
                    latestShowJson(id = 40),
                    latestShowJson(id = 12, name = "Friends", posterPath = null, premiereYear = null),
                ),
            )
        }
        http.profiles.setPending("igd_test")

        val result = http.showRepository.latestShows()

        val captured = requireNotNull(request)
        assertEquals("/api/shows/latest", captured.url.encodedPath)
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val shows = (result as ApiResult.Success).value
        assertEquals(listOf(40L, 12L), shows.map { it.id })
        assertEquals(listOf("Severance", "Friends"), shows.map { it.name })
        assertEquals("/severance.jpg", shows[0].posterPath.orNull())
        assertEquals(2022L, shows[0].premiereYear.orNull())
        assertNull(shows[1].posterPath.orNull())
        assertNull(shows[1].premiereYear.orNull())
    }

    @Test
    fun `show details hit the contract path and decode the show and its seasons`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(
                showDetailsJson(
                    id = 40,
                    seasons = listOf(showSeasonSummaryJson(1), showSeasonSummaryJson(0)),
                ),
            )
        }

        val result = http.showRepository.showDetails(40)

        assertEquals("/api/shows/details/40", requireNotNull(request).url.encodedPath)
        val data = (result as ApiResult.Success).value
        assertEquals("Severance", data.show.name)
        assertEquals(2022L, data.show.premiereYear.orNull())
        assertEquals("TV-MA", data.show.certification.orNull())
        assertEquals("/severance-backdrop.jpg", data.show.backdropPath.orNull())
        assertEquals(8.4, requireNotNull(data.show.voteAverage.orNull()), 0.0)
        assertEquals(listOf(1L, 0L), data.seasons.map { it.seasonNumber })
    }

    @Test
    fun `show details decode cast, creators, genres, networks, companies and extras`() = runTest {
        val http = TestHttp {
            jsonResponse(
                showDetailsJson(
                    tagline = "Please enjoy each fact equally.",
                    cast = listOf(showCastCreditJson(), showCastCreditJson(artistId = 4, castOrder = 1)),
                    creators = listOf(showPersonJson()),
                    genres = listOf(genreJson(tag = "Drama")),
                    networks = listOf(showNetworkJson()),
                    productionCompanies = listOf(productionCompanyJson(name = "Red Hour")),
                    extraVideos = listOf(extraVideoJson(title = "Severance - Trailer")),
                ),
            )
        }

        val data = (http.showRepository.showDetails(40) as ApiResult.Success).value

        assertEquals("Please enjoy each fact equally.", data.show.tagline.orNull())
        assertEquals("Returning Series", data.show.status.orNull())
        assertEquals("/severance.jpg", data.show.posterPath.orNull())
        assertEquals(listOf(0L, 1L), data.cast.map { it.castOrder })
        assertEquals("Mark Scout", data.cast.first().character)
        assertEquals(19L, data.cast.first().episodeCount)
        assertEquals(listOf("Dan Erickson"), data.creators.map { it.name })
        assertEquals(listOf("Drama"), data.genres.map { it.tag })
        assertEquals(listOf("Apple TV+"), data.networks.map { it.name })
        assertEquals(listOf("Red Hour"), data.productionCompanies.map { it.name })
        assertEquals(listOf("Severance - Trailer"), data.extraVideos.map { it.title })
    }

    @Test
    fun `season episodes hit the contract path and decode progress and watched`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(
                showSeasonEpisodesJson(
                    2,
                    showEpisodeJson(id = 910, episodeNumber = 1, progressSec = 600.0, durationSec = 3300.0),
                    showEpisodeJson(id = 911, episodeNumber = 2, stillPath = null, watched = true),
                ),
            )
        }
        http.profiles.setPending("igd_test")

        val result = http.showRepository.seasonEpisodes(showId = 40, seasonNumber = 2)

        val captured = requireNotNull(request)
        assertEquals("/api/shows/40/seasons/2/episodes", captured.url.encodedPath)
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val episodes = (result as ApiResult.Success).value
        assertEquals(listOf(910L, 911L), episodes.map { it.id })
        assertEquals(600.0, requireNotNull(episodes[0].progressSec.orNull()), 0.0)
        assertEquals(57L, episodes[0].tmdbRuntime.orNull())
        assertNull(episodes[1].stillPath.orNull())
        assertTrue(episodes[1].watched)
    }

    @Test
    fun `a missing season fails with its status`() = runTest {
        val http = TestHttp {
            jsonResponse("""{"error":true,"message":"season not found"}""", HttpStatusCode.NotFound)
        }

        val result = http.showRepository.seasonEpisodes(showId = 40, seasonNumber = 9)

        val error = (result as ApiResult.Failure).error
        assertEquals(404, (error as AppError.Api).status)
    }

    @Test
    fun `a missing show fails with its status`() = runTest {
        val http = TestHttp {
            jsonResponse("""{"error":true,"message":"show not found"}""", HttpStatusCode.NotFound)
        }

        val result = http.showRepository.showDetails(404)

        val error = (result as ApiResult.Failure).error
        assertEquals(404, (error as AppError.Api).status)
    }

    @Test
    fun `episode playback hits the contract path and decodes the header`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(episodePlaybackJson(nextEpisode = upNextEpisodeJson()))
        }
        http.profiles.setPending("igd_test")

        val result = http.showRepository.episodePlayback(900)

        val captured = requireNotNull(request)
        assertEquals("/api/shows/episodes/900", captured.url.encodedPath)
        assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
        val data = (result as ApiResult.Success).value
        assertEquals("Severance", data.show.name)
        assertEquals("/severance.jpg", data.show.posterPath.orNull())
        assertEquals(1L, data.season.seasonNumber)
        assertEquals(3L, data.episode.episodeNumber)
        assertEquals("In Perpetuity", data.episode.name)
        val next = requireNotNull(data.nextEpisode)
        assertEquals(901L, next.id)
        assertEquals(4L, next.episodeNumber)
        assertEquals("The You You Are", next.name)
    }

    @Test
    fun `the last playable episode has no next episode`() = runTest {
        val http = TestHttp { jsonResponse(episodePlaybackJson(nextEpisode = "null")) }

        val result = http.showRepository.episodePlayback(900)

        assertNull((result as ApiResult.Success).value.nextEpisode)
    }

    @Test
    fun `setting an episode watched PUTs the flag to the contract path and decodes the answer`() =
        runTest {
            var request: HttpRequestData? = null
            var body: String? = null
            val http = TestHttp {
                request = it
                body = String(it.body.toByteArray())
                jsonResponse(episodeWatchedJson(episodeId = 903, watched = true))
            }
            http.profiles.setPending("igd_test")

            val result = http.showRepository.setEpisodeWatched(903, watched = true)

            val captured = requireNotNull(request)
            assertEquals("/api/shows/episodes/903/watch-progress/watched", captured.url.encodedPath)
            assertEquals("PUT", captured.method.value)
            assertEquals("""{"watched":true}""", body)
            assertEquals("Bearer igd_test", captured.headers[HttpHeaders.Authorization])
            val data = (result as ApiResult.Success).value
            assertEquals(903L, data.episodeId)
            assertTrue(data.watched)
        }

    @Test
    fun `a refused watched write fails with its status`() = runTest {
        val http = TestHttp {
            jsonResponse("""{"error":true,"message":"not found"}""", HttpStatusCode.NotFound)
        }

        val result = http.showRepository.setEpisodeWatched(999, watched = false)

        val error = (result as ApiResult.Failure).error
        assertEquals(404, (error as AppError.Api).status)
    }

    @Test
    fun `episode technical details decode file-keyed streams and the probed duration`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(
                episodeTechnicalDetailsJson(
                    durationSec = 3300.0,
                    audioStreams = listOf(audioStreamJson(ownerKey = "file_id", codec = "eac3")),
                    chapters = listOf(chapterJson(ownerKey = "file_id", startTimeSec = 60)),
                ),
            )
        }

        val result = http.showRepository.episodeTechnicalDetails(900)

        assertEquals("/api/shows/episodes/900/technical-details", requireNotNull(request).url.encodedPath)
        val data = (result as ApiResult.Success).value
        assertEquals("video/x-matroska", data.file.mimeType)
        assertEquals(3300.0, requireNotNull(data.file.duration.orNull()), 0.0)
        assertEquals("eac3", data.audioStreams.single().codec)
        assertEquals("subrip", data.subtitles.single().codec)
        assertEquals(60L, data.chapters.single().startTime)
    }

    @Test
    fun `episode watch progress hits the contract path and decodes a saved position`() = runTest {
        var request: HttpRequestData? = null
        val http = TestHttp {
            request = it
            jsonResponse(watchProgressJson(progressSec = 600.0, durationSec = 3300.0))
        }

        val result = http.showRepository.episodeWatchProgress(900)

        assertEquals("/api/shows/episodes/900/watch-progress", requireNotNull(request).url.encodedPath)
        val progress = (result as ApiResult.Success).value
        assertEquals(600.0, requireNotNull(progress.progressSec), 0.0)
        assertEquals(3300.0, requireNotNull(progress.durationSec), 0.0)
    }

    @Test
    fun `an episode server failure preserves the backend message`() = runTest {
        val http = TestHttp {
            jsonResponse("""{"error":true,"message":"probe failed"}""", HttpStatusCode.InternalServerError)
        }

        val result = http.showRepository.episodeTechnicalDetails(900)

        val error = (result as ApiResult.Failure).error as AppError.Api
        assertEquals("probe failed", error.message)
    }
}
