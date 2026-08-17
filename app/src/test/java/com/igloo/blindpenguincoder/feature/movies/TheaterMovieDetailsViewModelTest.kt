package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.core.ui.RatingTier
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.tmdbCastJson
import com.igloo.blindpenguincoder.data.repository.tmdbCountryReleaseDatesJson
import com.igloo.blindpenguincoder.data.repository.tmdbCrewJson
import com.igloo.blindpenguincoder.data.repository.tmdbMovieJson
import com.igloo.blindpenguincoder.data.repository.tmdbVideoJson
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TheaterMovieDetailsViewModelTest {

    // A view model outlives the test body — its scope is not runTest's child — so an unfinished
    // load would still be on Dispatchers.Main when the next class calls setMain. Closing cancels
    // it, which is also what Back does in production.
    private val viewModels = mutableListOf<TheaterMovieDetailsViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.close() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(http: TestHttp) =
        TheaterMovieDetailsViewModel(http.movieRepository, http.serverUrl)
            .also { viewModels += it }

    private fun TestScope.http(
        respond: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(tmdbMovieJson()) },
    ) = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
        check(request.url.encodedPath.startsWith("/api/tmdb/movies/")) {
            "Unrouted path: ${request.url.encodedPath}"
        }
        respond(request)
    }

    private fun TheaterMovieDetailsViewModel.loaded(): MovieDetailsUi =
        (uiState.value.details as MovieDetailsState.Loaded).movie

    @Test
    fun `nothing loads until a movie is opened`() = runTest {
        var requests = 0
        val viewModel = viewModel(http { requests++; jsonResponse(tmdbMovieJson()) })

        assertNull(viewModel.uiState.value.openMovieId)
        viewModel.refresh()
        viewModel.retry()

        assertEquals(0, requests)
    }

    @Test
    fun `open reads the TMDB movie and maps the page`() = runTest {
        val paths = mutableListOf<String>()
        val http = http { request ->
            paths += request.url.encodedPath
            jsonResponse(
                tmdbMovieJson(
                    id = 21,
                    cast = listOf(
                        tmdbCastJson(),
                        tmdbCastJson(
                            id = 101,
                            name = "Robert De Niro",
                            character = "Neil McCauley",
                            profilePath = null,
                            order = 1,
                        ),
                    ),
                    crew = listOf(
                        tmdbCrewJson(),
                        tmdbCrewJson(id = 201, job = "Writer", department = "Writing"),
                    ),
                ),
            )
        }

        val viewModel = viewModel(http)
        viewModel.open(21)
        testScheduler.advanceUntilIdle()
        val movie = viewModel.loaded()

        assertEquals(listOf("/api/tmdb/movies/21"), paths)
        assertEquals(21L, viewModel.uiState.value.openMovieId)
        assertEquals(21L, movie.id)
        assertEquals("Heat 2", movie.title)
        assertEquals("A Los Angeles crime saga.", movie.tagline)
        assertEquals(
            "http://igloo.test:8080/api/tmdb/images/w1280/heat2-backdrop.jpg",
            movie.backdropUrl,
        )
        assertEquals("http://igloo.test:8080/api/tmdb/images/w500/heat2.jpg", movie.posterUrl)
        assertEquals("7.9", movie.ratingBadge?.label)
        assertEquals(RatingTier.Strong, movie.ratingBadge?.tier)
        assertEquals("R", movie.certification)
        assertEquals("2h 50m", movie.runtimeText)
        assertEquals("August 1, 2026", movie.releaseDateText)
        assertEquals("Crime · Drama", movie.genresLine)
        assertEquals(
            listOf(CrewEntry("Director", "Michael Mann"), CrewEntry("Writer", "Michael Mann")),
            movie.keyCrew,
        )
        assertEquals(listOf("Al Pacino", "Robert De Niro"), movie.cast.map { it.name })
        assertEquals("Vincent Hanna", movie.cast.first().character)
        assertEquals(
            "http://igloo.test:8080/api/tmdb/images/w185/pacino.jpg",
            movie.cast.first().photoUrl,
        )
        assertNull(movie.cast[1].photoUrl)
        assertEquals("Regency Enterprises", movie.about.production)
        assertEquals("Released", movie.about.status)
        assertEquals("EN", movie.about.language)
        assertEquals("$60,000,000", movie.about.budget)
        assertEquals("$187,436,818", movie.about.revenue)
        assertEquals(
            "Rated 7.9 out of 10, R, 2 hours 50 minutes, released August 1, 2026",
            movie.metadataDescription,
        )
    }

    @Test
    fun `a movie the library does not hold has no badges, resume, or toggles`() = runTest {
        val viewModel = viewModel(http())
        viewModel.open(21)
        testScheduler.advanceUntilIdle()
        val movie = viewModel.loaded()

        // Every field the library page fills from its secondary reads stays absent: there are no
        // probed streams, no watch progress, and nothing to like on a TMDB record.
        assertEquals(emptyList<String>(), movie.mediaBadges)
        assertNull(movie.progress)
        assertNull(movie.watched)
        assertNull(movie.liked)
    }

    @Test
    fun `extras are YouTube only, trailers first, and the hero plays the first trailer`() =
        runTest {
            val viewModel = viewModel(
                http {
                    jsonResponse(
                        tmdbMovieJson(
                            videos = listOf(
                                tmdbVideoJson(
                                    id = "v1",
                                    key = "featurette",
                                    name = "Making Heat",
                                    type = "Featurette",
                                ),
                                tmdbVideoJson(id = "v2", key = "vimeo", site = "Vimeo"),
                                tmdbVideoJson(id = "v3", key = "trailer2", name = "New Trailer"),
                                tmdbVideoJson(id = "v4", key = "trailer1"),
                            ),
                        ),
                    )
                },
            )
            viewModel.open(21)
            testScheduler.advanceUntilIdle()
            val movie = viewModel.loaded()

            // Vimeo is dropped (no thumbnail proxy), trailers lead in title order, and TMDB's own
            // free-form types are shown as it spells them.
            assertEquals(
                listOf("New Trailer", "Official Trailer", "Making Heat"),
                movie.extraVideos.map { it.title },
            )
            assertEquals(
                listOf("Trailer", "Trailer", "Featurette"),
                movie.extraVideos.map { it.typeLabel },
            )
            assertEquals(
                "http://igloo.test:8080/api/youtube/thumbnails/trailer2",
                movie.extraVideos.first().thumbnailUrl,
            )
            // The hero plays TMDB's first trailer, not the rail's — and it is the same card, so
            // the button and the rail never disagree about what "the trailer" is.
            assertEquals("trailer2", movie.heroTrailer?.key)
            assertTrue(movie.extraVideos.contains(movie.heroTrailer))
            // Keys are payload positions, assigned before the sort, so they stay put.
            assertEquals(listOf(3L, 4L, 1L), movie.extraVideos.map { it.id })
        }

    @Test
    fun `a movie with no YouTube trailer offers no hero action`() = runTest {
        val viewModel = viewModel(
            http {
                jsonResponse(
                    tmdbMovieJson(
                        videos = listOf(
                            tmdbVideoJson(key = "teaser", name = "Teaser", type = "Teaser"),
                            tmdbVideoJson(key = "vimeo-trailer", site = "Vimeo"),
                        ),
                    ),
                )
            },
        )
        viewModel.open(21)
        testScheduler.advanceUntilIdle()
        val movie = viewModel.loaded()

        assertNull(movie.heroTrailer)
        assertEquals(listOf("Teaser"), movie.extraVideos.map { it.title })
    }

    @Test
    fun `the certification is the US rating, or the first any country carries`() = runTest {
        val withoutUs = viewModel(
            http {
                jsonResponse(
                    tmdbMovieJson(
                        releaseDates = listOf(
                            tmdbCountryReleaseDatesJson(country = "AE", certifications = listOf("")),
                            tmdbCountryReleaseDatesJson(country = "AU", certifications = listOf("M")),
                            tmdbCountryReleaseDatesJson(country = "BE", certifications = listOf("12")),
                        ),
                    ),
                )
            },
        )
        withoutUs.open(21)
        testScheduler.advanceUntilIdle()

        // Blank certifications are skipped, and the backend scanner's own fallback applies: the
        // first non-empty rating from any country when the US has none.
        assertEquals("M", withoutUs.loaded().certification)

        val withUs = viewModel(
            http {
                jsonResponse(
                    tmdbMovieJson(
                        releaseDates = listOf(
                            tmdbCountryReleaseDatesJson(country = "AU", certifications = listOf("M")),
                            tmdbCountryReleaseDatesJson(
                                country = "US",
                                certifications = listOf("", "PG-13"),
                            ),
                        ),
                    ),
                )
            },
        )
        withUs.open(21)
        testScheduler.advanceUntilIdle()

        assertEquals("PG-13", withUs.loaded().certification)
    }

    @Test
    fun `an empty TMDB record drops every optional field instead of showing zeroes`() = runTest {
        val viewModel = viewModel(
            http {
                jsonResponse(
                    tmdbMovieJson(
                        overview = "",
                        posterPath = null,
                        backdropPath = null,
                        // TMDB's "no data" for a rating, a runtime, and money is a valid 0.
                        voteAverage = 0.0,
                        runtime = 0,
                        status = "",
                        tagline = "",
                        budget = 0,
                        revenue = 0,
                        originalLanguage = "",
                        releaseDate = "",
                        genres = null,
                        productionCompanies = null,
                        cast = null,
                        crew = null,
                        videos = null,
                        releaseDates = null,
                    ),
                )
            },
        )
        viewModel.open(21)
        testScheduler.advanceUntilIdle()
        val movie = viewModel.loaded()

        assertNull(movie.ratingBadge)
        assertNull(movie.runtimeText)
        assertNull(movie.releaseDateText)
        assertNull(movie.certification)
        assertNull(movie.tagline)
        assertNull(movie.overview)
        assertNull(movie.posterUrl)
        assertNull(movie.backdropUrl)
        assertNull(movie.genresLine)
        assertNull(movie.heroTrailer)
        assertEquals(emptyList<CrewEntry>(), movie.keyCrew)
        assertEquals(emptyList<CastMemberUi>(), movie.cast)
        assertEquals(emptyList<ExtraVideoUi>(), movie.extraVideos)
        assertTrue(movie.about.isEmpty)
        assertEquals("", movie.metadataDescription)
    }

    @Test
    fun `the cast rail is capped at ten, in billing order`() = runTest {
        val viewModel = viewModel(
            http {
                jsonResponse(
                    tmdbMovieJson(
                        cast = (0..14).reversed().map { order ->
                            tmdbCastJson(id = 100 + order, name = "Actor $order", order = order)
                        },
                    ),
                )
            },
        )
        viewModel.open(21)
        testScheduler.advanceUntilIdle()

        assertEquals(
            (0..9).map { "Actor $it" },
            viewModel.loaded().cast.map { it.name },
        )
    }

    @Test
    fun `a failed open shows the error and retry reloads`() = runTest {
        var fail = true
        val viewModel = viewModel(
            http {
                if (fail) {
                    jsonResponse(
                        """{"error":true,"message":"TMDB is not configured"}""",
                        HttpStatusCode.InternalServerError,
                    )
                } else {
                    jsonResponse(tmdbMovieJson())
                }
            },
        )

        viewModel.open(21)
        testScheduler.advanceUntilIdle()
        assertEquals(
            "TMDB is not configured",
            (viewModel.uiState.value.details as MovieDetailsState.Error).message,
        )

        fail = false
        viewModel.retry()
        testScheduler.advanceUntilIdle()

        assertEquals("Heat 2", viewModel.loaded().title)
    }

    @Test
    fun `a failed background refresh keeps the page that is already on screen`() = runTest {
        var fail = false
        val viewModel = viewModel(
            http {
                if (fail) {
                    jsonResponse(
                        """{"error":true,"message":"TMDB is unavailable"}""",
                        HttpStatusCode.InternalServerError,
                    )
                } else {
                    jsonResponse(tmdbMovieJson())
                }
            },
        )
        viewModel.open(21)
        testScheduler.advanceUntilIdle()

        fail = true
        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        // A TV waking from standby must not swap a readable page for an error nobody asked for.
        assertEquals("Heat 2", viewModel.loaded().title)
    }

    @Test
    fun `closing forgets the movie and a late response cannot reopen it`() = runTest {
        val viewModel = viewModel(http())
        viewModel.open(21)
        testScheduler.advanceUntilIdle()

        viewModel.close()

        assertNull(viewModel.uiState.value.openMovieId)
        assertTrue(viewModel.uiState.value.details is MovieDetailsState.Loading)
        // Idempotent: the host closes both detail view models on Back without asking which was up.
        viewModel.close()
        viewModel.refresh()
        testScheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.openMovieId)
    }
}
