package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.core.ui.RatingTier
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.audioStreamJson
import com.igloo.blindpenguincoder.data.repository.castMemberJson
import com.igloo.blindpenguincoder.data.repository.crewMemberJson
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.likeStatusJson
import com.igloo.blindpenguincoder.data.repository.likeToggleJson
import com.igloo.blindpenguincoder.data.repository.movieDetailsJson
import com.igloo.blindpenguincoder.data.repository.movieGenreJson
import com.igloo.blindpenguincoder.data.repository.productionCompanyJson
import com.igloo.blindpenguincoder.data.repository.subtitleJson
import com.igloo.blindpenguincoder.data.repository.technicalDetailsJson
import com.igloo.blindpenguincoder.data.repository.videoStreamJson
import com.igloo.blindpenguincoder.data.repository.watchProgressJson
import com.igloo.blindpenguincoder.data.repository.watchedUpdateJson
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
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
class MovieDetailsViewModelTest {

    // A view model outlives the test body — its scope is not runTest's child — so an unfinished
    // load would still be on Dispatchers.Main when the next class calls setMain. Closing cancels
    // every load, which is also what Back does in production.
    private val viewModels = mutableListOf<MovieDetailsViewModel>()

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
        MovieDetailsViewModel(http.movieRepository, http.serverUrl)
            .also { viewModels += it }

    /**
     * `open` fires four requests in parallel; handlers route by path. The mock engine runs on
     * the caller's scheduler so the responses and the view model share one clock — a real
     * thread hop can resume after the test has finished and fail whichever test is running by
     * then (see TestHttp's note on the health probe).
     */
    private fun TestScope.routedHttp(
        details: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(populatedDetailsJson()) },
        technical: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(technicalDetailsJson()) },
        progress: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(watchProgressJson()) },
        likeStatus: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(likeStatusJson()) },
        likeToggle: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(likeToggleJson()) },
        setWatched: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(watchedUpdateJson()) },
    ) = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
        val path = request.url.encodedPath
        when {
            path.startsWith("/api/movies/details/") -> details(request)
            path.endsWith("/technical-details") -> technical(request)
            path.endsWith("/watch-progress/watched") -> setWatched(request)
            path.endsWith("/watch-progress") -> progress(request)
            path.endsWith("/like-status") -> likeStatus(request)
            path.endsWith("/like") -> likeToggle(request)
            else -> error("Unrouted path: $path")
        }
    }

    private suspend fun MovieDetailsViewModel.awaitLoaded(): MovieDetailsUi =
        (uiState.first { it.details is MovieDetailsState.Loaded }.details as MovieDetailsState.Loaded)
            .movie

    private suspend fun MovieDetailsViewModel.awaitError(): MovieDetailsState.Error =
        uiState.first { it.details is MovieDetailsState.Error }.details as MovieDetailsState.Error

    @Test
    fun `nothing loads until a movie is opened`() = runTest {
        var requests = 0
        val http = routedHttp(details = { requests++; jsonResponse(populatedDetailsJson()) })

        val viewModel = viewModel(http)

        assertEquals(0, requests)
        assertNull(viewModel.uiState.value.openMovieId)
        viewModel.refresh()
        assertEquals(0, requests)
    }

    @Test
    fun `open fires all four requests and maps the movie`() = runTest {
        val paths = mutableListOf<String>()
        val http = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
            val path = request.url.encodedPath
            paths += path
            when {
                path.startsWith("/api/movies/details/") -> jsonResponse(populatedDetailsJson())
                path.endsWith("/technical-details") -> jsonResponse(technicalDetailsJson())
                path.endsWith("/watch-progress") -> jsonResponse(
                    watchProgressJson(progressSec = 1800.0, durationSec = 10200.0),
                )
                path.endsWith("/like-status") -> jsonResponse(likeStatusJson(isLiked = true))
                else -> error("Unrouted path: $path")
            }
        }

        val viewModel = viewModel(http)
        viewModel.open(5)
        // The four responses land in any order; the fully-composed state has them all.
        val movie = viewModel.uiState
            .first {
                val loaded = (it.details as? MovieDetailsState.Loaded)?.movie
                loaded != null && loaded.mediaBadges.isNotEmpty() &&
                    loaded.liked == true && loaded.progress != null
            }
            .let { (it.details as MovieDetailsState.Loaded).movie }

        assertEquals(
            setOf(
                "/api/movies/details/5",
                "/api/movies/5/technical-details",
                "/api/movies/5/watch-progress",
                "/api/movies/5/like-status",
            ),
            paths.toSet(),
        )
        assertEquals(5L, viewModel.uiState.value.openMovieId)
        assertEquals("Heat", movie.title)
        assertEquals("A Los Angeles crime saga.", movie.tagline)
        assertEquals(
            "http://igloo.test:8080/api/tmdb/images/w1280/heat-backdrop.jpg",
            movie.backdropUrl,
        )
        assertEquals("http://igloo.test:8080/api/tmdb/images/w500/heat.jpg", movie.posterUrl)
        assertEquals("8.2", movie.ratingBadge?.label)
        assertEquals(RatingTier.Strong, movie.ratingBadge?.tier)
        assertEquals("R", movie.certification)
        assertEquals("2h 50m", movie.runtimeText)
        assertEquals("December 15, 1995", movie.releaseDateText)
        assertEquals("Crime · Drama", movie.genresLine)
        assertEquals(listOf("4K", "HDR10", "5.1", "CC"), movie.mediaBadges)
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
        assertEquals("Regency Enterprises, Forward Pass", movie.about.production)
        assertEquals("EN", movie.about.language)
        assertEquals("$60,000,000", movie.about.budget)
        assertEquals("$187,436,818", movie.about.revenue)
        assertEquals(0.176f, requireNotNull(movie.progress).fraction, 0.001f)
        assertEquals("140 min left", movie.progress?.minutesLeftLabel)
        assertEquals(false, movie.watched)
        assertEquals(true, movie.liked)
        assertEquals(
            "Rated 8.2 out of 10, R, 4K, HDR10, 5.1 surround sound, subtitles available, " +
                "2 hours 50 minutes, released December 15, 1995",
            movie.metadataDescription,
        )
    }

    @Test
    fun `a details failure is a full-screen error and retry recovers`() = runTest {
        var fail = true
        val http = routedHttp(
            details = {
                if (fail) {
                    jsonResponse("""{"error":true,"message":"boom"}""", HttpStatusCode.InternalServerError)
                } else {
                    jsonResponse(populatedDetailsJson())
                }
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.awaitError()

        fail = false
        viewModel.retry()

        assertEquals("Heat", viewModel.awaitLoaded().title)
    }

    @Test
    fun `secondary failures degrade instead of failing the screen`() = runTest {
        val http = routedHttp(
            technical = { jsonResponse("""{"error":true,"message":"x"}""", HttpStatusCode.InternalServerError) },
            progress = { jsonResponse("""{"error":true,"message":"x"}""", HttpStatusCode.InternalServerError) },
            likeStatus = { jsonResponse("""{"error":true,"message":"x"}""", HttpStatusCode.InternalServerError) },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        val movie = viewModel.awaitLoaded()

        assertEquals("Heat", movie.title)
        assertTrue(movie.mediaBadges.isEmpty())
        assertNull(movie.progress)
        assertNull(movie.watched)
        assertNull(movie.liked)
    }

    @Test
    fun `badge tiers follow the web thresholds`() = runTest {
        suspend fun badges(video: String, audio: String, subtitles: List<String>): List<String> {
            val http = routedHttp(
                technical = {
                    jsonResponse(
                        technicalDetailsJson(
                            videoStreams = listOf(video),
                            audioStreams = listOf(audio),
                            subtitles = subtitles,
                        ),
                    )
                },
            )
            val viewModel = viewModel(http)
            viewModel.open(1)
            return viewModel.uiState
                .first {
                    val details = it.details
                    details is MovieDetailsState.Loaded && details.movie.mediaBadges.isNotEmpty()
                }
                .let { (it.details as MovieDetailsState.Loaded).movie.mediaBadges }
        }

        // Scope 4K by width; height alone under the bar.
        assertEquals(
            listOf("4K", "HDR10", "5.1", "CC"),
            badges(
                videoStreamJson(width = 3840, height = 1600, colorTransfer = "smpte2084"),
                audioStreamJson(channels = 6, channelLayout = "5.1(side)"),
                listOf(subtitleJson()),
            ),
        )
        // 1080p HLG, 7.1 layout, no subs.
        assertEquals(
            listOf("HD", "HLG", "7.1"),
            badges(
                videoStreamJson(width = 1920, height = 1080, colorTransfer = "arib-std-b67"),
                audioStreamJson(channels = 8, channelLayout = "7.1"),
                emptyList(),
            ),
        )
        // SD SDR: only the surround word for six channels without a named layout.
        assertEquals(
            listOf("Surround"),
            badges(
                videoStreamJson(width = 720, height = 576, colorTransfer = null),
                audioStreamJson(channels = 6, channelLayout = null),
                emptyList(),
            ),
        )
        // Stereo stays badge-free.
        assertEquals(
            listOf("HD"),
            badges(
                videoStreamJson(width = 1920, height = 1080, colorTransfer = null),
                audioStreamJson(channels = 2, channelLayout = "stereo"),
                emptyList(),
            ),
        )
    }

    @Test
    fun `the progress strip needs thirty seconds and a position under 98 percent`() = runTest {
        suspend fun progressFor(progressSec: Double?, durationSec: Double?): ProgressUi? {
            val http = routedHttp(
                progress = {
                    jsonResponse(watchProgressJson(progressSec = progressSec, durationSec = durationSec))
                },
            )
            val viewModel = viewModel(http)
            viewModel.open(1)
            return viewModel.uiState
                .first {
                    val details = it.details
                    details is MovieDetailsState.Loaded && details.movie.watched != null
                }
                .let { (it.details as MovieDetailsState.Loaded).movie.progress }
        }

        assertNull(progressFor(29.0, 7200.0))
        assertEquals("120 min left", progressFor(30.0, 7200.0)?.minutesLeftLabel)
        assertNull(progressFor(7100.0, 7200.0))
        assertNull(progressFor(null, null))
    }

    @Test
    fun `cast is capped at ten in cast order`() = runTest {
        val http = routedHttp(
            details = {
                jsonResponse(
                    movieDetailsJson(
                        cast = (12 downTo 1).map {
                            castMemberJson(
                                id = it.toLong(),
                                artistId = it.toLong(),
                                castOrder = it.toLong(),
                                artistName = "Actor $it",
                            )
                        },
                    ),
                )
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        val movie = viewModel.awaitLoaded()

        assertEquals((1..10).map { "Actor $it" }, movie.cast.map { it.name })
    }

    @Test
    fun `key crew is directors then up to three writing credits`() = runTest {
        val http = routedHttp(
            details = {
                jsonResponse(
                    movieDetailsJson(
                        crew = listOf(
                            crewMemberJson(id = 1, job = "Novel", department = "Writing", artistName = "W1"),
                            crewMemberJson(id = 2, job = "Director", department = "Directing", artistName = "D"),
                            crewMemberJson(id = 3, job = "Screenplay", department = "Writing", artistName = "W2"),
                            crewMemberJson(id = 4, job = "Screenplay", department = "Writing", artistName = "W3"),
                            crewMemberJson(id = 5, job = "Story", department = "Writing", artistName = "W4"),
                            crewMemberJson(id = 6, job = "Producer", department = "Production", artistName = "P"),
                        ),
                    ),
                )
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        val movie = viewModel.awaitLoaded()

        assertEquals(
            listOf(
                CrewEntry("Director", "D"),
                CrewEntry("Novel", "W1"),
                CrewEntry("Screenplay", "W2"),
                CrewEntry("Screenplay", "W3"),
            ),
            movie.keyCrew,
        )
    }

    @Test
    fun `scraper zeros never render as data`() = runTest {
        val http = routedHttp(
            details = {
                jsonResponse(
                    movieDetailsJson(
                        criticRating = 0.0,
                        runTimeMinutes = 0,
                        budget = 0.0,
                        revenue = 0.0,
                        tagLine = null,
                        releaseDate = null,
                    ),
                )
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        val movie = viewModel.awaitLoaded()

        assertNull(movie.ratingBadge)
        assertNull(movie.runtimeText)
        assertNull(movie.about.budget)
        assertNull(movie.about.revenue)
        assertNull(movie.tagline)
        assertNull(movie.releaseDateText)
    }

    @Test
    fun `toggle watched is optimistic and adopts the server's answer`() = runTest {
        val http = routedHttp(
            progress = { jsonResponse(watchProgressJson(progressSec = 1800.0, durationSec = 7200.0)) },
            setWatched = { jsonResponse(watchedUpdateJson(watched = true)) },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
        }

        viewModel.toggleWatched()
        val movie = viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == true
        }.let { (it.details as MovieDetailsState.Loaded).movie }

        assertEquals(true, movie.watched)
        // Marking watched hides the strip even though a position is still stored.
        assertNull(movie.progress)
    }

    @Test
    fun `a failed watched toggle flips the button back`() = runTest {
        val http = routedHttp(
            setWatched = {
                jsonResponse("""{"error":true,"message":"x"}""", HttpStatusCode.InternalServerError)
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
        }

        viewModel.toggleWatched()

        // Optimistically true first; the failure response flips it back.
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
        }
    }

    @Test
    fun `a failed like toggle flips the button back`() = runTest {
        val http = routedHttp(
            likeStatus = { jsonResponse(likeStatusJson(isLiked = false)) },
            likeToggle = {
                jsonResponse("""{"error":true,"message":"x"}""", HttpStatusCode.InternalServerError)
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.liked == false
        }

        viewModel.toggleLike()

        // Optimistically true first; the failure response flips it back.
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.liked == false
        }
    }

    @Test
    fun `a background refresh failure keeps loaded content`() = runTest {
        var fail = false
        val http = routedHttp(
            details = {
                if (fail) {
                    jsonResponse("""{"error":true,"message":"x"}""", HttpStatusCode.InternalServerError)
                } else {
                    jsonResponse(populatedDetailsJson())
                }
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.awaitLoaded()

        fail = true
        viewModel.refresh()

        assertEquals("Heat", viewModel.awaitLoaded().title)
    }

    /**
     * Leaving the screen must not take the user's change with it: the toggle is a write they
     * asked for, and Back arriving a frame later is normal remote behaviour. The request is
     * held open across the close so cancellation has a window to happen in — without that, the
     * unconfined dispatcher delivers it before Back is even pressed and the test proves nothing.
     */
    @Test
    fun `closing right after a toggle still delivers the write`() = runTest {
        val reachedServer = CompletableDeferred<Unit>()
        val releaseServer = CompletableDeferred<Unit>()
        val answered = CompletableDeferred<Unit>()
        val http = routedHttp(
            setWatched = {
                reachedServer.complete(Unit)
                releaseServer.await()
                answered.complete(Unit)
                jsonResponse(watchedUpdateJson(watched = true))
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
        }

        viewModel.toggleWatched()
        reachedServer.await()
        viewModel.close()
        releaseServer.complete(Unit)

        // Cancelling the job would cancel the handler's continuation, and this would never land.
        withTimeout(WRITE_TIMEOUT_MS) { answered.await() }
        assertNull(viewModel.uiState.value.openMovieId)
    }

    /**
     * The dangerous read is not the one a toggle cancels — it is the one issued *after* the
     * write starts (a resume calls `refresh`) and answered by the server from before that write
     * commits. Finishing later does not make it newer, so it must not set the flag.
     */
    @Test
    fun `a status read issued mid-write does not undo the write`() = runTest {
        val writeReached = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val staleReadRequested = CompletableDeferred<Unit>()
        val releaseStaleRead = CompletableDeferred<Unit>()
        val staleReadAnswered = CompletableDeferred<Unit>()
        var progressReads = 0
        val http = routedHttp(
            progress = {
                progressReads += 1
                if (progressReads > 1) {
                    staleReadRequested.complete(Unit)
                    releaseStaleRead.await()
                    staleReadAnswered.complete(Unit)
                }
                // Both reads carry the server's pre-flip truth.
                jsonResponse(watchProgressJson(watched = false))
            },
            setWatched = {
                writeReached.complete(Unit)
                releaseWrite.await()
                jsonResponse(watchedUpdateJson(watched = true))
            },
        )

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.uiState.first {
            (it.details as? MovieDetailsState.Loaded)?.movie?.watched == false
        }

        viewModel.toggleWatched()
        writeReached.await()
        // The TV resumes mid-write and re-reads everything.
        viewModel.refresh()
        staleReadRequested.await()
        releaseWrite.complete(Unit)
        releaseStaleRead.complete(Unit)
        withTimeout(WRITE_TIMEOUT_MS) { staleReadAnswered.await() }
        testScheduler.advanceUntilIdle()

        assertEquals(true, viewModel.awaitLoaded().watched)
    }

    @Test
    fun `close clears the overlay state`() = runTest {
        val http = routedHttp()

        val viewModel = viewModel(http)
        viewModel.open(1)
        viewModel.awaitLoaded()

        viewModel.close()

        assertNull(viewModel.uiState.value.openMovieId)
        assertEquals(MovieDetailsState.Loading, viewModel.uiState.value.details)
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 5_000L

        fun populatedDetailsJson(id: Long = 1) = movieDetailsJson(
            id = id,
            cast = listOf(
                // Wire order is not cast order: the mapper must sort.
                castMemberJson(
                    id = 2,
                    artistId = 2,
                    castOrder = 1,
                    artistName = "Robert De Niro",
                    character = "Neil McCauley",
                    artistProfile = "/deniro.jpg",
                ),
                castMemberJson(
                    id = 1,
                    artistId = 1,
                    castOrder = 0,
                    artistName = "Al Pacino",
                    character = "Vincent Hanna",
                    artistProfile = "/pacino.jpg",
                ),
            ),
            crew = listOf(
                crewMemberJson(id = 1, job = "Director", department = "Directing", artistName = "Michael Mann"),
                crewMemberJson(id = 2, job = "Writer", department = "Writing", artistName = "Michael Mann"),
            ),
            genres = listOf(movieGenreJson(id = 1, tag = "Crime"), movieGenreJson(id = 2, tag = "Drama")),
            productionCompanies = listOf(
                productionCompanyJson(id = 1, name = "Regency Enterprises"),
                productionCompanyJson(id = 2, name = "Forward Pass"),
            ),
        )
    }
}
