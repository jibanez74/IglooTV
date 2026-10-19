package com.igloo.blindpenguincoder.feature.home

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.ratingBadgeSpec
import com.igloo.blindpenguincoder.data.model.PlaybackMode
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.audioStreamJson
import com.igloo.blindpenguincoder.data.repository.continueWatchingEpisodeJson
import com.igloo.blindpenguincoder.data.repository.continueWatchingMovieJson
import com.igloo.blindpenguincoder.data.repository.continueWatchingJson
import com.igloo.blindpenguincoder.data.repository.episodePlaybackJson
import com.igloo.blindpenguincoder.data.repository.episodeTechnicalDetailsJson
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.latestAlbumsJson
import com.igloo.blindpenguincoder.data.repository.latestMovieJson
import com.igloo.blindpenguincoder.data.repository.latestMoviesJson
import com.igloo.blindpenguincoder.data.repository.latestShowJson
import com.igloo.blindpenguincoder.data.repository.latestShowsJson
import com.igloo.blindpenguincoder.data.repository.movieDetailsJson
import com.igloo.blindpenguincoder.data.repository.showDetailsJson
import com.igloo.blindpenguincoder.data.repository.showSeasonSummaryJson
import com.igloo.blindpenguincoder.data.repository.simpleAlbumJson
import com.igloo.blindpenguincoder.data.repository.theaterMovieJson
import com.igloo.blindpenguincoder.data.repository.theaterMoviesJson
import com.igloo.blindpenguincoder.data.repository.videoStreamJson
import com.igloo.blindpenguincoder.data.repository.watchProgressJson
import com.igloo.blindpenguincoder.feature.music.AlbumCardUi
import com.igloo.blindpenguincoder.feature.shared.PosterItem
import com.igloo.blindpenguincoder.playback.model.PlaybackMediaRef
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The host's start effect is what fires the first load; there is no fetch in `init`. */
    private fun viewModel(
        http: TestHttp,
        canPlayAudioMime: Boolean = true,
        canPlayVideoMime: Boolean = true,
    ) = newViewModel(http, canPlayAudioMime, canPlayVideoMime).also { it.refresh() }

    private fun newViewModel(
        http: TestHttp,
        canPlayAudioMime: Boolean = true,
        canPlayVideoMime: Boolean = true,
    ) = HomeViewModel(
        http.movieRepository,
        http.showRepository,
        http.musicRepository,
        http.serverUrl,
        canPlayVideoMime = { canPlayVideoMime },
        canPlayAudioMime = { _, _ -> canPlayAudioMime },
    )

    private fun List<HomeContinueItem>.movieTitles() =
        map { (it as HomeContinueItem.Movie).movie.title }

    /**
     * A refresh fires all five rails' requests, enriching the movie and show heroes from their lists.
     * The show library is empty unless a test says otherwise, so the movie-page cases see the
     * hero they always did: one page, or none.
     */
    private fun routedHttp(
        engineDispatcher: CoroutineDispatcher? = null,
        continueWatching: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(continueWatchingJson()) },
        latest: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(latestMoviesJson()) },
        details: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(movieDetailsJson()) },
        albums: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(latestAlbumsJson()) },
        theaters: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(theaterMoviesJson()) },
        latestShows: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(latestShowsJson()) },
        showDetails: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(showDetailsJson()) },
        episode: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(episodePlaybackJson()) },
        episodeTechnical: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(episodeTechnicalDetailsJson()) },
        episodeProgress: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(watchProgressJson(progressSec = 600.0, durationSec = 3300.0)) },
    ) = TestHttp(engineDispatcher) { request ->
        val path = request.url.encodedPath
        when {
            path == "/api/continue-watching" -> continueWatching(request)
            path.endsWith("/technical-details") && path.startsWith("/api/shows/episodes/") ->
                episodeTechnical(request)
            path.endsWith("/watch-progress") && path.startsWith("/api/shows/episodes/") ->
                episodeProgress(request)
            path.startsWith("/api/shows/episodes/") -> episode(request)
            path == "/api/music/albums/latest" -> albums(request)
            path == "/api/tmdb/movies/in-theaters" -> theaters(request)
            path == "/api/shows/latest" -> latestShows(request)
            path.startsWith("/api/shows/details/") -> showDetails(request)
            // Before the catch-all: the details call must never silently get a latest-shaped body.
            path.startsWith("/api/movies/details/") -> details(request)
            else -> latest(request)
        }
    }

    private suspend fun HomeViewModel.awaitLatest(): IglooRailState.Loaded<PosterItem> =
        uiState.first { it.latestMovies is IglooRailState.Loaded }
            .latestMovies as IglooRailState.Loaded

    private suspend fun HomeViewModel.awaitShows(): IglooRailState.Loaded<PosterItem> =
        uiState.first { it.latestShows is IglooRailState.Loaded }
            .latestShows as IglooRailState.Loaded

    private suspend fun HomeViewModel.awaitShowsError(): IglooRailState.Error =
        uiState.first { it.latestShows is IglooRailState.Error }
            .latestShows as IglooRailState.Error

    private suspend fun HomeViewModel.awaitContinue(): IglooRailState.Loaded<HomeContinueItem> =
        uiState.first { it.continueWatching is IglooRailState.Loaded }
            .continueWatching as IglooRailState.Loaded

    private suspend fun HomeViewModel.awaitHeroPages(): List<HomeHero> =
        (uiState.first { it.hero is HomeHeroState.Loaded }.hero as HomeHeroState.Loaded).pages

    /** The movie page, for the cases where the show library is empty and it is the only one. */
    private suspend fun HomeViewModel.awaitHero(): HomeHero = awaitHeroPages().single()

    private suspend fun HomeViewModel.awaitHiddenHero() {
        uiState.first { it.hero is HomeHeroState.Hidden }
    }

    private suspend fun HomeViewModel.awaitLatestError(): IglooRailState.Error =
        uiState.first { it.latestMovies is IglooRailState.Error }
            .latestMovies as IglooRailState.Error

    private suspend fun HomeViewModel.awaitContinueError(): IglooRailState.Error =
        uiState.first { it.continueWatching is IglooRailState.Error }
            .continueWatching as IglooRailState.Error

    private suspend fun HomeViewModel.awaitAlbums(): IglooRailState.Loaded<AlbumCardUi> =
        uiState.first { it.latestAlbums is IglooRailState.Loaded }
            .latestAlbums as IglooRailState.Loaded

    private suspend fun HomeViewModel.awaitAlbumsError(): IglooRailState.Error =
        uiState.first { it.latestAlbums is IglooRailState.Error }
            .latestAlbums as IglooRailState.Error

    private suspend fun HomeViewModel.awaitTheaters(): IglooRailState.Loaded<HomeTheaterMovie> =
        uiState.first { it.inTheaters is IglooRailState.Loaded }
            .inTheaters as IglooRailState.Loaded

    private suspend fun HomeViewModel.awaitTheatersError(): IglooRailState.Error =
        uiState.first { it.inTheaters is IglooRailState.Error }
            .inTheaters as IglooRailState.Error

    @Test
    fun `nothing loads until the host asks for it`() = runTest {
        var requests = 0
        val http = routedHttp(
            continueWatching = { requests++; jsonResponse(continueWatchingJson()) },
            latest = { requests++; jsonResponse(latestMoviesJson()) },
            albums = { requests++; jsonResponse(latestAlbumsJson()) },
            theaters = { requests++; jsonResponse(theaterMoviesJson()) },
            latestShows = { requests++; jsonResponse(latestShowsJson()) },
        )

        val viewModel = newViewModel(http)

        assertEquals(0, requests)
        assertEquals(IglooRailState.Loading, viewModel.uiState.value.continueWatching)
        assertEquals(IglooRailState.Loading, viewModel.uiState.value.latestMovies)
        assertEquals(IglooRailState.Loading, viewModel.uiState.value.latestShows)
        assertEquals(IglooRailState.Loading, viewModel.uiState.value.latestAlbums)
        assertEquals(IglooRailState.Loading, viewModel.uiState.value.inTheaters)
    }

    @Test
    fun `movies map to render-ready cards with normalized poster URLs`() = runTest {
        val http = routedHttp(
            latest = {
                jsonResponse(
                    latestMoviesJson(
                        latestMovieJson(id = 1, title = "Heat", posterPath = "/heat.jpg", year = 1995),
                        latestMovieJson(id = 2, title = "Arrival", posterPath = "arrival.jpg", year = 2016),
                    ),
                )
            },
        )

        val state = viewModel(http).awaitLatest()

        // Both wire shapes — with and without the leading slash — build the same proxy URL form.
        assertEquals(
            listOf(
                PosterItem(
                    1,
                    "Heat",
                    1995,
                    "http://igloo.test:8080/api/tmdb/images/w500/heat.jpg",
                ),
                PosterItem(
                    2,
                    "Arrival",
                    2016,
                    "http://igloo.test:8080/api/tmdb/images/w500/arrival.jpg",
                ),
            ),
            state.items,
        )
    }

    @Test
    fun `absent poster and year map to nulls`() = runTest {
        val http = routedHttp(
            latest = {
                jsonResponse(latestMoviesJson(latestMovieJson(posterPath = null, year = null)))
            },
        )

        val state = viewModel(http).awaitLatest()

        assertNull(state.items.single().posterUrl)
        assertNull(state.items.single().year)
    }

    @Test
    fun `an empty library loads as an empty rail, not an error`() = runTest {
        val state = viewModel(routedHttp()).awaitLatest()

        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `a server failure surfaces the backend message with a retryable state`() = runTest {
        val http = routedHttp(
            latest = {
                jsonResponse(
                    """{"error":true,"message":"scan in progress"}""",
                    HttpStatusCode.InternalServerError,
                )
            },
        )

        val state = viewModel(http).awaitLatestError()

        assertEquals("scan in progress", state.message)
    }

    @Test
    fun `a dead token reads as an expired session, not a mistyped password`() = runTest {
        val http = routedHttp(
            latest = {
                jsonResponse("""{"error":true,"message":"gone"}""", HttpStatusCode.Unauthorized)
            },
        )

        val state = viewModel(http).awaitLatestError()

        // The sign-in copy for Unauthorized makes no sense on a screen with no password field.
        assertEquals(
            "Your session has expired. Sign in again to see your library.",
            state.message,
        )
    }

    @Test
    fun `retry re-enters loading and then succeeds`() = runTest {
        var failed = false
        val gate = CompletableDeferred<Unit>()
        val http = routedHttp(
            latest = {
                if (!failed) {
                    failed = true
                    jsonResponse("""{"error":true,"message":"boom"}""", HttpStatusCode.InternalServerError)
                } else {
                    gate.await()
                    jsonResponse(latestMoviesJson(latestMovieJson(id = 3, title = "Ran")))
                }
            },
        )
        val viewModel = viewModel(http)
        viewModel.awaitLatestError()

        viewModel.retry(HomeRail.LatestMovies)

        // The gated second request holds the rail in Loading so the skeleton renders again.
        assertEquals(IglooRailState.Loading, viewModel.uiState.value.latestMovies)
        gate.complete(Unit)
        assertEquals(listOf("Ran"), viewModel.awaitLatest().items.map { it.title })
    }

    @Test
    fun `a refresh keeps the loaded rail on screen until its replacement arrives`() = runTest {
        var requests = 0
        val gate = CompletableDeferred<Unit>()
        val http = routedHttp(
            latest = {
                requests += 1
                if (requests > 1) gate.await()
                jsonResponse(
                    latestMoviesJson(
                        latestMovieJson(id = 1, title = if (requests == 1) "Heat" else "Ran"),
                    ),
                )
            },
        )
        val viewModel = viewModel(http)
        assertEquals(listOf("Heat"), viewModel.awaitLatest().items.map { it.title })

        viewModel.refresh()

        // No skeleton flash: the rail re-anchors focus on every state swap, so dropping back to
        // Loading here would pull focus off whatever card the user is sitting on.
        val during = viewModel.uiState.value.latestMovies
        assertTrue("a refresh must not re-enter Loading", during is IglooRailState.Loaded)
        assertEquals(listOf("Heat"), (during as IglooRailState.Loaded).items.map { it.title })

        gate.complete(Unit)
        // Awaiting `Loaded` would match the value already on screen, so wait on the content.
        val after = viewModel.uiState.first {
            (it.latestMovies as? IglooRailState.Loaded)?.items?.single()?.title == "Ran"
        }
        assertTrue(after.latestMovies is IglooRailState.Loaded)
    }

    @Test
    fun `targeted continue watching refresh retains its rail and fetches no others`() = runTest {
        var continueRequests = 0
        var latestRequests = 0
        var albumRequests = 0
        var theaterRequests = 0
        val refreshReached = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val http = routedHttp(
            continueWatching = {
                continueRequests += 1
                if (continueRequests == 2) {
                    refreshReached.complete(Unit)
                    releaseRefresh.await()
                }
                jsonResponse(
                    continueWatchingJson(
                        continueWatchingMovieJson(
                            id = if (continueRequests == 1) 1 else 2,
                            title = if (continueRequests == 1) "Heat" else "Arrival",
                        ),
                    ),
                )
            },
            latest = {
                latestRequests += 1
                jsonResponse(latestMoviesJson())
            },
            albums = {
                albumRequests += 1
                jsonResponse(latestAlbumsJson())
            },
            theaters = {
                theaterRequests += 1
                jsonResponse(theaterMoviesJson())
            },
        )
        val viewModel = viewModel(http)
        assertEquals(listOf("Heat"), viewModel.awaitContinue().items.movieTitles())

        viewModel.refreshContinueWatching()
        refreshReached.await()

        val during = viewModel.uiState.value.continueWatching
        assertTrue(during is IglooRailState.Loaded)
        assertEquals(listOf("Heat"), (during as IglooRailState.Loaded).items.movieTitles())
        assertEquals(2, continueRequests)
        assertEquals(1, latestRequests)
        assertEquals(1, albumRequests)
        assertEquals(1, theaterRequests)

        releaseRefresh.complete(Unit)
        viewModel.uiState.first {
            (it.continueWatching as? IglooRailState.Loaded)?.items?.movieTitles() == listOf("Arrival")
        }
    }

    @Test
    fun `a refresh that fails leaves the loaded rail alone`() = runTest {
        var requests = 0
        // The engine shares the test scheduler, so a request is done being handled by the time
        // the call that triggered it returns — the refresh needs no gate to be observed.
        val http = routedHttp(
            engineDispatcher = UnconfinedTestDispatcher(testScheduler),
            latest = {
                requests += 1
                if (requests == 1) {
                    jsonResponse(latestMoviesJson(latestMovieJson(id = 1, title = "Heat")))
                } else {
                    jsonResponse(
                        """{"error":true,"message":"scan in progress"}""",
                        HttpStatusCode.InternalServerError,
                    )
                }
            },
        )
        val viewModel = viewModel(http)
        assertEquals(listOf("Heat"), viewModel.awaitLatest().items.map { it.title })

        viewModel.refresh()

        // A moment of bad wifi as the TV wakes must not replace a working Home with an error.
        assertEquals(2, requests)
        assertEquals(listOf("Heat"), viewModel.awaitLatest().items.map { it.title })

        // A Retry the user asked for still tells the truth.
        viewModel.retry(HomeRail.LatestMovies)
        assertEquals("scan in progress", viewModel.awaitLatestError().message)
    }

    @Test
    fun `continue watching maps to bar-only cards with spoken remaining time`() = runTest {
        val http = routedHttp(
            continueWatching = {
                jsonResponse(
                    continueWatchingJson(
                        continueWatchingMovieJson(
                            id = 1,
                            title = "Heat",
                            posterPath = "/heat.jpg",
                            year = 1995,
                            progressSec = 1800.0,
                            durationSec = 10200.0,
                        ),
                    ),
                )
            },
        )

        val state = viewModel(http).awaitContinue()

        assertEquals(
            listOf(
                HomeContinueItem.Movie(
                    movie = PosterItem(
                        1,
                        "Heat",
                        1995,
                        "http://igloo.test:8080/api/tmdb/images/w500/heat.jpg",
                    ),
                    progressFraction = (1800.0 / 10200.0).toFloat(),
                    progressDescription = "2 hours and 20 minutes remaining",
                ),
            ),
            state.items,
        )
    }

    @Test
    fun `overshot and zero-duration progress clamp instead of breaking the card`() = runTest {
        val http = routedHttp(
            continueWatching = {
                jsonResponse(
                    continueWatchingJson(
                        continueWatchingMovieJson(id = 1, progressSec = 7300.0, durationSec = 7200.0),
                        continueWatchingMovieJson(id = 2, progressSec = 60.0, durationSec = 0.0),
                    ),
                )
            },
        )

        val state = viewModel(http).awaitContinue()

        val (overshot, zeroDuration) = state.items
        assertEquals(1f, overshot.progressFraction, 0f)
        assertEquals("Less than 1 minute remaining", overshot.progressDescription)
        assertEquals(0f, zeroDuration.progressFraction, 0f)
        assertEquals("In progress", zeroDuration.progressDescription)
    }

    @Test
    fun `an in-progress episode becomes a card wearing the show poster and naming the episode`() =
        runTest {
            val http = routedHttp(
                continueWatching = {
                    jsonResponse(
                        continueWatchingJson(
                            continueWatchingEpisodeJson(
                                id = 900,
                                showTitle = "Severance",
                                seasonNumber = 1,
                                episodeNumber = 3,
                                episodeName = "In Perpetuity",
                                progressSec = 600.0,
                                durationSec = 3300.0,
                            ),
                            continueWatchingMovieJson(id = 1, title = "Heat"),
                        ),
                    )
                },
            )

            val state = viewModel(http).awaitContinue()

            // Server order: the episode was watched most recently, so it leads.
            val (episode, movie) = state.items
            assertEquals(
                HomeContinueItem.Episode(
                    episodeId = 900,
                    showTitle = "Severance",
                    episodeCode = "S1 E3",
                    episodeName = "In Perpetuity",
                    posterUrl = "http://igloo.test:8080/api/tmdb/images/w500/severance.jpg",
                    progressFraction = (600.0 / 3300.0).toFloat(),
                    progressDescription = "45 minutes remaining",
                ),
                episode,
            )
            assertEquals("S1 E3 · In Perpetuity", (episode as HomeContinueItem.Episode).subtitle)
            assertEquals("Heat", (movie as HomeContinueItem.Movie).movie.title)
        }

    /** A movie and an episode can share an id; the rail's keys must still be distinct. */
    @Test
    fun `a movie and an episode with the same id get different rail keys`() = runTest {
        val http = routedHttp(
            continueWatching = {
                jsonResponse(
                    continueWatchingJson(
                        continueWatchingEpisodeJson(id = 5),
                        continueWatchingMovieJson(id = 5),
                    ),
                )
            },
        )

        val state = viewModel(http).awaitContinue()

        assertEquals(2, state.items.map { it.railKey }.toSet().size)
    }

    @Test
    fun `resuming an episode prepares it and emits a play request for the player`() = runTest {
        val paths = mutableListOf<String>()
        val http = routedHttp(
            episode = { paths += it.url.encodedPath; jsonResponse(episodePlaybackJson()) },
            episodeTechnical = {
                paths += it.url.encodedPath
                jsonResponse(episodeTechnicalDetailsJson(durationSec = 3300.0))
            },
            episodeProgress = {
                paths += it.url.encodedPath
                jsonResponse(watchProgressJson(progressSec = 600.0, durationSec = 3200.0))
            },
        )
        val viewModel = viewModel(http)

        viewModel.resumeEpisode(900)
        val request = viewModel.playRequests.first()

        assertEquals(PlaybackMediaRef.Episode(900), request.media)
        assertEquals("Severance · S1 E3 · In Perpetuity", request.title)
        assertEquals("http://igloo.test:8080/api/tmdb/images/w500/severance.jpg", request.posterUrl)
        assertEquals("video/x-matroska", request.mimeType)
        assertEquals(PlaybackMode.Direct, request.mode)
        assertEquals(600.0, request.resumeAtSec)
        // The saved duration wins over the probed one, as it does for a movie.
        assertEquals(3200.0, request.durationSec)
        assertEquals(
            setOf(
                "/api/shows/episodes/900",
                "/api/shows/episodes/900/technical-details",
                "/api/shows/episodes/900/watch-progress",
            ),
            paths.toSet(),
        )
        assertNull(viewModel.uiState.value.playbackNotice)
    }

    @Test
    fun `an episode with no saved duration falls back to the probed file duration`() = runTest {
        val http = routedHttp(
            episodeTechnical = { jsonResponse(episodeTechnicalDetailsJson(durationSec = 3300.0)) },
            episodeProgress = { jsonResponse(watchProgressJson()) },
        )
        val viewModel = viewModel(http)

        viewModel.resumeEpisode(900)
        val request = viewModel.playRequests.first()

        assertEquals(3300.0, request.durationSec)
        assertNull(request.resumeAtSec)
    }

    @Test
    fun `a failed episode read surfaces a notice and launches nothing`() = runTest {
        var launched = false
        val http = routedHttp(
            episodeTechnical = {
                jsonResponse(
                    """{"error":true,"message":"probe failed"}""",
                    HttpStatusCode.InternalServerError,
                )
            },
        )
        val viewModel = viewModel(http)
        val watcher = backgroundScope.launch { viewModel.playRequests.collect { launched = true } }

        viewModel.resumeEpisode(900)
        val notice = viewModel.uiState.first { it.playbackNotice != null }.playbackNotice

        assertEquals("Couldn't prepare playback: probe failed", notice)
        assertFalse(launched)
        watcher.cancel()

        // A refresh — the host's start effect — starts clean.
        viewModel.refresh()
        assertNull(viewModel.uiState.value.playbackNotice)
    }

    @Test
    fun `a failed episode header answers the press without waiting out the other reads`() = runTest {
        val http = routedHttp(
            episode = {
                jsonResponse(
                    """{"error":true,"message":"episode not found"}""",
                    HttpStatusCode.NotFound,
                )
            },
            episodeTechnical = { awaitCancellation() },
            episodeProgress = { awaitCancellation() },
        )
        val viewModel = viewModel(http)

        viewModel.resumeEpisode(900)
        val notice = viewModel.uiState.first { it.playbackNotice != null }.playbackNotice

        assertEquals("Couldn't prepare playback: episode not found", notice)
    }

    @Test
    fun `repeated presses while preparing coalesce into one launch`() = runTest {
        var headerRequests = 0
        val release = CompletableDeferred<Unit>()
        val http = routedHttp(
            episode = {
                headerRequests += 1
                release.await()
                jsonResponse(episodePlaybackJson())
            },
        )
        val viewModel = viewModel(http)

        viewModel.resumeEpisode(900)
        viewModel.resumeEpisode(900)
        viewModel.resumeEpisode(900)
        release.complete(Unit)
        val request = viewModel.playRequests.first()

        assertEquals(1, headerRequests)
        assertEquals(PlaybackMediaRef.Episode(900), request.media)
    }

    @Test
    fun `a refresh abandons a preparation still in flight`() = runTest {
        var launched = false
        val release = CompletableDeferred<Unit>()
        val http = routedHttp(
            engineDispatcher = UnconfinedTestDispatcher(testScheduler),
            episode = {
                release.await()
                jsonResponse(episodePlaybackJson())
            },
        )
        val viewModel = viewModel(http)
        val watcher = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.playRequests.collect { launched = true }
        }

        viewModel.resumeEpisode(900)
        viewModel.refresh()
        release.complete(Unit)
        advanceUntilIdle()

        assertFalse(launched)
        assertNull(viewModel.uiState.value.playbackNotice)
        watcher.cancel()
    }

    @Test
    fun `an address cleared before preparation finishes reads as a notice, not a crash`() =
        runTest {
            var launched = false
            lateinit var http: TestHttp
            http = routedHttp(
                episodeProgress = {
                    http.serverUrl.set(null)
                    jsonResponse(watchProgressJson(progressSec = 600.0, durationSec = 3300.0))
                },
            )
            val viewModel = viewModel(http)
            val watcher =
                backgroundScope.launch { viewModel.playRequests.collect { launched = true } }

            viewModel.resumeEpisode(900)
            val notice = viewModel.uiState.first { it.playbackNotice != null }.playbackNotice

            assertTrue(notice!!.startsWith("Couldn't prepare playback: "))
            assertFalse(launched)
            watcher.cancel()
        }

    /**
     * Home has no Playback Settings to point the user at, so a Direct request the device cannot
     * decode is relaunched as Remux instead of being refused with unreachable guidance.
     */
    @Test
    fun `an undecodable default track falls back to remux instead of blocking`() = runTest {
        val http = routedHttp(
            episodeTechnical = {
                jsonResponse(
                    episodeTechnicalDetailsJson(
                        audioStreams = listOf(
                            audioStreamJson(ownerKey = "file_id", codec = "truehd", channels = 8),
                        ),
                    ),
                )
            },
        )
        val viewModel = viewModel(http, canPlayAudioMime = false)

        viewModel.resumeEpisode(900)
        val request = viewModel.playRequests.first()

        assertEquals(PlaybackMode.Remux, request.mode)
        assertEquals("truehd", request.selectedAudioTrack?.codec)
        assertNull(viewModel.uiState.value.playbackNotice)
    }

    /**
     * Media3 extracts a DivX 3 AVI's video yet nothing decodes it, and it then plays the sound
     * over a black screen. Remux is the way out: the server transcodes video it cannot copy.
     */
    @Test
    fun `an undecodable video falls back to remux instead of playing sound alone`() = runTest {
        val http = routedHttp(
            episodeTechnical = {
                jsonResponse(
                    episodeTechnicalDetailsJson(
                        mimeType = "video/x-msvideo",
                        videoStreams = listOf(
                            videoStreamJson(
                                ownerKey = "file_id",
                                codec = "msmpeg4v3",
                                width = 512,
                                height = 384,
                            ),
                        ),
                        audioStreams = listOf(
                            audioStreamJson(ownerKey = "file_id", codec = "mp3", channels = 2),
                        ),
                    ),
                )
            },
        )
        val viewModel = viewModel(http, canPlayVideoMime = false)

        viewModel.resumeEpisode(900)
        val request = viewModel.playRequests.first()

        assertEquals(PlaybackMode.Remux, request.mode)
        assertEquals("msmpeg4v3", request.videoCodec)
        assertNull(viewModel.uiState.value.playbackNotice)
    }

    @Test
    fun `nothing in progress loads as an empty rail, not an error`() = runTest {
        val state = viewModel(routedHttp()).awaitContinue()

        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `a continue watching failure leaves the latest rail standing`() = runTest {
        val http = routedHttp(
            continueWatching = {
                jsonResponse(
                    """{"error":true,"message":"scan in progress"}""",
                    HttpStatusCode.InternalServerError,
                )
            },
            latest = { jsonResponse(latestMoviesJson(latestMovieJson(id = 3, title = "Ran"))) },
        )
        val viewModel = viewModel(http)

        val continueState = viewModel.awaitContinueError()
        val latestState = viewModel.awaitLatest()

        assertEquals("scan in progress", continueState.message)
        assertEquals(listOf("Ran"), latestState.items.map { it.title })
    }

    @Test
    fun `the hero features the newest movie, enriched by one details request`() = runTest {
        var detailsRequests = 0
        val http = routedHttp(
            latest = {
                jsonResponse(
                    latestMoviesJson(
                        latestMovieJson(id = 7, title = "Heat"),
                        latestMovieJson(id = 2, title = "Arrival"),
                        latestMovieJson(id = 3, title = "Ran"),
                    ),
                )
            },
            details = { request ->
                detailsRequests++
                assertEquals("/api/movies/details/7", request.url.encodedPath)
                jsonResponse(
                    movieDetailsJson(
                        id = 7,
                        title = "Heat",
                        backdropPath = "/heat-backdrop.jpg",
                        overview = "Neil McCauley leads a top-notch crew.",
                        year = 1995,
                        certification = "R",
                        runTimeMinutes = 170,
                        criticRating = 8.2,
                    ),
                )
            },
        )

        val hero = viewModel(http).awaitHero()

        assertEquals(
            HomeHero(
                kind = HomeHeroKind.Movie,
                id = 7,
                title = "Heat",
                backdropUrl = "http://igloo.test:8080/api/tmdb/images/w1280/heat-backdrop.jpg",
                overview = "Neil McCauley leads a top-notch crew.",
                year = "1995",
                certification = "R",
                ratingBadge = ratingBadgeSpec(8.2),
                extent = "2h 50m",
                metadataDescription = "Rated 8.2 out of 10, R, 1995, 2 hours and 50 minutes",
            ),
            hero,
        )
        assertEquals(1, detailsRequests)
    }

    @Test
    fun `an empty library hides the hero`() = runTest {
        var detailsRequests = 0
        val http = routedHttp(details = { detailsRequests++; jsonResponse(movieDetailsJson()) })

        viewModel(http).awaitHiddenHero()

        assertEquals(0, detailsRequests)
    }

    @Test
    fun `a failed latest fetch hides the hero`() = runTest {
        val http = routedHttp(
            latest = {
                jsonResponse("""{"error":true,"message":"boom"}""", HttpStatusCode.InternalServerError)
            },
        )

        viewModel(http).awaitHiddenHero()
    }

    /**
     * The session keeps the address set while Home is composed; a sign-out that clears it between
     * a response and its mapping must fail the load, not throw out of the coroutine.
     */
    @Test
    fun `an address cleared before a response is mapped fails the rail and hides the hero`() =
        runTest {
            lateinit var http: TestHttp
            http = routedHttp(
                latest = {
                    http.serverUrl.set(null)
                    jsonResponse(latestMoviesJson())
                },
            )
            val viewModel = viewModel(http)

            viewModel.awaitLatestError()
            viewModel.awaitHiddenHero()
        }

    @Test
    fun `a failed details fetch hides the hero but leaves the rail standing`() = runTest {
        val http = routedHttp(
            latest = { jsonResponse(latestMoviesJson(latestMovieJson(id = 1, title = "Heat"))) },
            details = {
                jsonResponse("""{"error":true,"message":"boom"}""", HttpStatusCode.InternalServerError)
            },
        )
        val viewModel = viewModel(http)

        viewModel.awaitHiddenHero()

        assertEquals(listOf("Heat"), viewModel.awaitLatest().items.map { it.title })
    }

    @Test
    fun `a refresh whose details fetch fails keeps the loaded hero`() = runTest {
        var detailsRequests = 0
        val http = routedHttp(
            engineDispatcher = UnconfinedTestDispatcher(testScheduler),
            latest = { jsonResponse(latestMoviesJson(latestMovieJson(id = 1, title = "Heat"))) },
            details = {
                detailsRequests += 1
                if (detailsRequests == 1) {
                    jsonResponse(movieDetailsJson(id = 1, title = "Heat"))
                } else {
                    jsonResponse("""{"error":true,"message":"boom"}""", HttpStatusCode.InternalServerError)
                }
            },
        )
        val viewModel = viewModel(http)
        assertEquals("Heat", viewModel.awaitHero().title)

        viewModel.refresh()

        // The same bad-wifi rule as the rails: a background failure must not blank the hero.
        assertEquals(2, detailsRequests)
        assertEquals("Heat", viewModel.awaitHero().title)

        // A Retry the user asked for still tells the truth.
        viewModel.retry(HomeRail.LatestMovies)
        viewModel.awaitHiddenHero()
    }

    @Test
    fun `retrying the latest rail re-enters the hero's loading state`() = runTest {
        var failed = false
        val gate = CompletableDeferred<Unit>()
        val http = routedHttp(
            latest = {
                if (!failed) {
                    failed = true
                    jsonResponse("""{"error":true,"message":"boom"}""", HttpStatusCode.InternalServerError)
                } else {
                    gate.await()
                    jsonResponse(latestMoviesJson(latestMovieJson(id = 3, title = "Ran")))
                }
            },
            details = { jsonResponse(movieDetailsJson(id = 3, title = "Ran")) },
        )
        val viewModel = viewModel(http)
        viewModel.awaitHiddenHero()

        viewModel.retry(HomeRail.LatestMovies)

        assertEquals(HomeHeroState.Loading, viewModel.uiState.value.hero)
        gate.complete(Unit)
        assertEquals("Ran", viewModel.awaitHero().title)
    }

    @Test
    fun `hero fields absent on the wire map to nulls`() = runTest {
        val http = routedHttp(
            latest = { jsonResponse(latestMoviesJson(latestMovieJson(id = 1, title = "Heat"))) },
            details = {
                jsonResponse(
                    movieDetailsJson(
                        id = 1,
                        title = "Heat",
                        backdropPath = null,
                        overview = null,
                        year = null,
                        certification = null,
                        runTimeMinutes = null,
                        criticRating = null,
                    ),
                )
            },
        )

        val hero = viewModel(http).awaitHero()

        assertEquals(
            HomeHero(
                kind = HomeHeroKind.Movie,
                id = 1,
                title = "Heat",
                backdropUrl = null,
                overview = null,
                year = null,
                certification = null,
                ratingBadge = null,
                extent = null,
                metadataDescription = null,
            ),
            hero,
        )
    }

    @Test
    fun `hero fields the scraper wrote as a valid zero are dropped, not rendered`() = runTest {
        // TMDB's "no data" reaches us as Valid = true with a zero payload; treating that as a
        // real value puts "· 0.0" on every unrated movie.
        val http = routedHttp(
            latest = { jsonResponse(latestMoviesJson(latestMovieJson(id = 1, title = "Heat"))) },
            details = {
                jsonResponse(
                    movieDetailsJson(
                        id = 1,
                        title = "Heat",
                        year = 0,
                        certification = "R",
                        runTimeMinutes = 0,
                        criticRating = 0.0,
                    ),
                )
            },
        )

        val hero = viewModel(http).awaitHero()

        assertNull(hero.year)
        assertNull(hero.extent)
        assertNull(hero.ratingBadge)
        assertEquals("R", hero.metadataDescription)
    }

    @Test
    fun `runtimes format as hours and minutes`() = runTest {
        suspend fun extent(runTimeMinutes: Long): String? {
            val http = routedHttp(
                latest = { jsonResponse(latestMoviesJson(latestMovieJson(id = 1))) },
                details = {
                    jsonResponse(
                        movieDetailsJson(
                            id = 1,
                            year = null,
                            certification = null,
                            criticRating = null,
                            runTimeMinutes = runTimeMinutes,
                        ),
                    )
                },
            )
            return viewModel(http).awaitHero().extent
        }

        assertEquals("45m", extent(45))
        assertEquals("2h", extent(120))
        assertEquals("2h 50m", extent(170))
    }

    @Test
    fun `the hero features the newest show after the movie, enriched by one details request`() =
        runTest {
            var detailsRequests = 0
            var latestRequests = 0
            val http = routedHttp(
                latest = { jsonResponse(latestMoviesJson(latestMovieJson(id = 7, title = "Heat"))) },
                details = { jsonResponse(movieDetailsJson(id = 7, title = "Heat")) },
                latestShows = {
                    latestRequests++
                    jsonResponse(
                        latestShowsJson(
                            latestShowJson(id = 40, name = "Severance"),
                            latestShowJson(id = 12, name = "Friends"),
                        ),
                    )
                },
                showDetails = { request ->
                    detailsRequests++
                    assertEquals("/api/shows/details/40", request.url.encodedPath)
                    jsonResponse(
                        showDetailsJson(
                            id = 40,
                            name = "Severance",
                            backdropPath = "/severance-backdrop.jpg",
                            overview = "Mark leads a team of office workers.",
                            premiereYear = 2022,
                            certification = "TV-MA",
                            voteAverage = 8.4,
                            seasons = listOf(showSeasonSummaryJson(1), showSeasonSummaryJson(2)),
                        ),
                    )
                },
            )

            val viewModel = viewModel(http)
            val pages = viewModel.awaitHeroPages()
            assertEquals(listOf(40L, 12L), viewModel.awaitShows().items.map { it.id })
            assertEquals(1, latestRequests)

            assertEquals(listOf(HomeHeroKind.Movie, HomeHeroKind.Show), pages.map { it.kind })
            assertEquals(
                HomeHero(
                    kind = HomeHeroKind.Show,
                    id = 40,
                    title = "Severance",
                    backdropUrl = "http://igloo.test:8080/api/tmdb/images/w1280/severance-backdrop.jpg",
                    overview = "Mark leads a team of office workers.",
                    year = "2022",
                    certification = "TV-MA",
                    ratingBadge = ratingBadgeSpec(8.4),
                    extent = "2 Seasons",
                    metadataDescription = "Rated 8.4 out of 10, TV-MA, 2022, 2 seasons",
                ),
                pages[1],
            )
            assertEquals(1, detailsRequests)
        }

    @Test
    fun `specials do not count as a season, and one season is singular`() = runTest {
        val http = routedHttp(
            latestShows = { jsonResponse(latestShowsJson(latestShowJson(id = 40))) },
            showDetails = {
                jsonResponse(
                    showDetailsJson(
                        id = 40,
                        seasons = listOf(showSeasonSummaryJson(1), showSeasonSummaryJson(0)),
                    ),
                )
            },
        )

        val show = viewModel(http).awaitHero()

        assertEquals("1 Season", show.extent)
        assertEquals("Rated 8.4 out of 10, TV-MA, 2022, 1 season", show.metadataDescription)
    }

    @Test
    fun `show fields absent or zero on the wire are dropped, not rendered`() = runTest {
        val http = routedHttp(
            latestShows = { jsonResponse(latestShowsJson(latestShowJson(id = 40))) },
            showDetails = {
                jsonResponse(
                    showDetailsJson(
                        id = 40,
                        backdropPath = null,
                        overview = "  ",
                        premiereYear = 0,
                        certification = null,
                        voteAverage = 0.0,
                        // A show the library holds only the specials of has no season to count.
                        seasons = listOf(showSeasonSummaryJson(0)),
                    ),
                )
            },
        )

        val show = viewModel(http).awaitHero()

        assertEquals(HomeHeroKind.Show, show.kind)
        assertNull(show.backdropUrl)
        assertNull(show.overview)
        assertNull(show.year)
        assertNull(show.certification)
        assertNull(show.ratingBadge)
        assertNull(show.extent)
        assertNull(show.metadataDescription)
    }

    @Test
    fun `the hero stays loading until both pages have settled`() = runTest {
        val showGate = CompletableDeferred<Unit>()
        val http = routedHttp(
            latest = { jsonResponse(latestMoviesJson(latestMovieJson(id = 7, title = "Heat"))) },
            details = { jsonResponse(movieDetailsJson(id = 7, title = "Heat")) },
            latestShows = { jsonResponse(latestShowsJson(latestShowJson(id = 40))) },
            showDetails = {
                showGate.await()
                jsonResponse(showDetailsJson(id = 40))
            },
        )
        val viewModel = viewModel(http)

        // The movie page is ready, but showing it alone would put "1 of 1" under the user's
        // focus and then grow a second page beside it.
        viewModel.awaitLatest()
        assertEquals(HomeHeroState.Loading, viewModel.uiState.value.hero)

        showGate.complete(Unit)
        assertEquals(
            listOf(HomeHeroKind.Movie, HomeHeroKind.Show),
            viewModel.awaitHeroPages().map { it.kind },
        )
    }

    @Test
    fun `a show page that cannot load leaves the movie page alone`() = runTest {
        suspend fun pagesWhen(
            latestShows: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
            showDetails: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        ): List<HomeHeroKind> {
            val http = routedHttp(
                latest = { jsonResponse(latestMoviesJson(latestMovieJson(id = 7, title = "Heat"))) },
                details = { jsonResponse(movieDetailsJson(id = 7, title = "Heat")) },
                latestShows = latestShows,
                showDetails = showDetails,
            )
            return viewModel(http).awaitHeroPages().map { it.kind }
        }
        val boom: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
            jsonResponse("""{"error":true,"message":"boom"}""", HttpStatusCode.InternalServerError)
        }

        // An empty show library, a failed latest fetch, and a failed details fetch.
        assertEquals(
            listOf(HomeHeroKind.Movie),
            pagesWhen({ jsonResponse(latestShowsJson()) }, { jsonResponse(showDetailsJson()) }),
        )
        assertEquals(listOf(HomeHeroKind.Movie), pagesWhen(boom, { jsonResponse(showDetailsJson()) }))
        assertEquals(
            listOf(HomeHeroKind.Movie),
            pagesWhen({ jsonResponse(latestShowsJson(latestShowJson(id = 40))) }, boom),
        )
    }

    @Test
    fun `the show page stands alone when the movie library is empty`() = runTest {
        val http = routedHttp(
            latestShows = { jsonResponse(latestShowsJson(latestShowJson(id = 40))) },
            showDetails = { jsonResponse(showDetailsJson(id = 40, name = "Severance")) },
        )

        assertEquals("Severance", viewModel(http).awaitHero().title)
    }

    @Test
    fun `a refresh whose show details fail keeps the loaded show page`() = runTest {
        var showRequests = 0
        val http = routedHttp(
            engineDispatcher = UnconfinedTestDispatcher(testScheduler),
            latest = { jsonResponse(latestMoviesJson(latestMovieJson(id = 7, title = "Heat"))) },
            details = { jsonResponse(movieDetailsJson(id = 7, title = "Heat")) },
            latestShows = { jsonResponse(latestShowsJson(latestShowJson(id = 40))) },
            showDetails = {
                showRequests += 1
                if (showRequests == 1) {
                    jsonResponse(showDetailsJson(id = 40, name = "Severance"))
                } else {
                    jsonResponse("""{"error":true,"message":"boom"}""", HttpStatusCode.InternalServerError)
                }
            },
        )
        val viewModel = viewModel(http)
        assertEquals(listOf("Heat", "Severance"), viewModel.awaitHeroPages().map { it.title })

        viewModel.refresh()

        assertEquals(2, showRequests)
        assertEquals(listOf("Heat", "Severance"), viewModel.awaitHeroPages().map { it.title })
    }

    @Test
    fun `retrying the latest rail reloads the movie page and keeps the show page`() = runTest {
        var latestRequests = 0
        val gate = CompletableDeferred<Unit>()
        var showRequests = 0
        val http = routedHttp(
            latest = {
                latestRequests += 1
                if (latestRequests == 2) gate.await()
                jsonResponse(latestMoviesJson(latestMovieJson(id = 7, title = "Heat")))
            },
            details = { jsonResponse(movieDetailsJson(id = 7, title = "Heat")) },
            latestShows = {
                showRequests += 1
                jsonResponse(latestShowsJson(latestShowJson(id = 40)))
            },
            showDetails = { jsonResponse(showDetailsJson(id = 40, name = "Severance")) },
        )
        val viewModel = viewModel(http)
        assertEquals(2, viewModel.awaitHeroPages().size)

        viewModel.retry(HomeRail.LatestMovies)

        assertEquals(HomeHeroState.Loading, viewModel.uiState.value.hero)
        gate.complete(Unit)
        assertEquals(listOf("Heat", "Severance"), viewModel.awaitHeroPages().map { it.title })
        // Movie Retry leaves both the show rail and its hero untouched.
        assertEquals(1, showRequests)
    }

    @Test
    fun `retrying continue watching reloads only that rail`() = runTest {
        var failed = false
        val gate = CompletableDeferred<Unit>()
        var latestRequests = 0
        val http = routedHttp(
            continueWatching = {
                if (!failed) {
                    failed = true
                    jsonResponse("""{"error":true,"message":"boom"}""", HttpStatusCode.InternalServerError)
                } else {
                    gate.await()
                    jsonResponse(continueWatchingJson(continueWatchingMovieJson(id = 3, title = "Ran")))
                }
            },
            latest = {
                latestRequests++
                jsonResponse(latestMoviesJson(latestMovieJson(id = 9, title = "Alien")))
            },
        )
        val viewModel = viewModel(http)
        viewModel.awaitContinueError()
        viewModel.awaitLatest()

        viewModel.retry(HomeRail.ContinueWatching)

        assertEquals(IglooRailState.Loading, viewModel.uiState.value.continueWatching)
        assertTrue(viewModel.uiState.value.latestMovies is IglooRailState.Loaded)
        gate.complete(Unit)
        assertEquals(listOf("Ran"), viewModel.awaitContinue().items.movieTitles())
        assertEquals(1, latestRequests)
    }

    @Test
    fun `albums map to cards keeping the cover URL the backend sent`() = runTest {
        val http = routedHttp(
            albums = {
                jsonResponse(
                    latestAlbumsJson(
                        simpleAlbumJson(
                            id = 211,
                            title = "The Foundation",
                            cover = "https://i.scdn.co/image/foundation.jpg",
                            musician = "Zac Brown Band",
                        ),
                    ),
                )
            },
        )

        val album = viewModel(http).awaitAlbums().items.single()

        assertEquals(211L, album.id)
        assertEquals("The Foundation", album.title)
        assertEquals("Zac Brown Band", album.subtitle)
        // Verbatim: there is no music image proxy to rewrite a Spotify URL through.
        assertEquals("https://i.scdn.co/image/foundation.jpg", album.coverUrl)
    }

    @Test
    fun `absent cover and musician map to nulls`() = runTest {
        val http = routedHttp(
            albums = { jsonResponse(latestAlbumsJson(simpleAlbumJson(cover = null, musician = null))) },
        )

        val album = viewModel(http).awaitAlbums().items.single()

        assertNull(album.coverUrl)
        assertNull(album.subtitle)
    }

    @Test
    fun `a blank cover or musician is absent, not an empty line under the title`() = runTest {
        val http = routedHttp(
            albums = { jsonResponse(latestAlbumsJson(simpleAlbumJson(cover = " ", musician = ""))) },
        )

        val album = viewModel(http).awaitAlbums().items.single()

        assertNull(album.coverUrl)
        assertNull(album.subtitle)
    }

    @Test
    fun `a blank album title renders as Untitled album`() = runTest {
        val http = routedHttp(
            albums = { jsonResponse(latestAlbumsJson(simpleAlbumJson(title = "   "))) },
        )

        val album = viewModel(http).awaitAlbums().items.single()

        assertEquals("Untitled album", album.title)
    }

    @Test
    fun `the albums rail keeps the server's order`() = runTest {
        val http = routedHttp(
            albums = {
                jsonResponse(
                    latestAlbumsJson(
                        simpleAlbumJson(id = 3, title = "Zenyatta Mondatta"),
                        simpleAlbumJson(id = 1, title = "Help!"),
                        simpleAlbumJson(id = 2, title = "1984"),
                    ),
                )
            },
        )

        val titles = viewModel(http).awaitAlbums().items.map { it.title }

        assertEquals(listOf("Zenyatta Mondatta", "Help!", "1984"), titles)
    }

    @Test
    fun `an empty music library loads as an empty rail, not an error`() = runTest {
        val http = routedHttp(albums = { jsonResponse(latestAlbumsJson()) })

        assertTrue(viewModel(http).awaitAlbums().items.isEmpty())
    }

    @Test
    fun `an albums failure leaves the movie rails standing`() = runTest {
        val http = routedHttp(
            albums = {
                jsonResponse(
                    """{"error":true,"message":"music scan in progress"}""",
                    HttpStatusCode.InternalServerError,
                )
            },
            latest = { jsonResponse(latestMoviesJson(latestMovieJson(id = 3, title = "Ran"))) },
        )
        val viewModel = viewModel(http)

        assertEquals("music scan in progress", viewModel.awaitAlbumsError().message)
        assertEquals(listOf("Ran"), viewModel.awaitLatest().items.map { it.title })
    }

    @Test
    fun `retrying the albums rail reloads only that rail`() = runTest {
        var failed = false
        val gate = CompletableDeferred<Unit>()
        var latestRequests = 0
        val http = routedHttp(
            albums = {
                if (!failed) {
                    failed = true
                    jsonResponse("""{"error":true,"message":"boom"}""", HttpStatusCode.InternalServerError)
                } else {
                    gate.await()
                    jsonResponse(latestAlbumsJson(simpleAlbumJson(id = 4, title = "Tribalistas")))
                }
            },
            latest = {
                latestRequests++
                jsonResponse(latestMoviesJson(latestMovieJson(id = 9, title = "Alien")))
            },
        )
        val viewModel = viewModel(http)
        viewModel.awaitAlbumsError()
        viewModel.awaitLatest()

        viewModel.retry(HomeRail.LatestAlbums)

        assertEquals(IglooRailState.Loading, viewModel.uiState.value.latestAlbums)
        assertTrue(viewModel.uiState.value.latestMovies is IglooRailState.Loaded)
        gate.complete(Unit)
        assertEquals(listOf("Tribalistas"), viewModel.awaitAlbums().items.map { it.title })
        assertEquals(1, latestRequests)
    }

    @Test
    fun `theater movies map to render-ready cards sorted by release date descending`() = runTest {
        val http = routedHttp(
            theaters = {
                jsonResponse(
                    theaterMoviesJson(
                        theaterMovieJson(id = 1, title = "Older", releaseDate = "2026-06-15", voteAverage = 5.1),
                        theaterMovieJson(id = 2, title = "Newest", releaseDate = "2026-08-01", voteAverage = 7.9),
                        theaterMovieJson(id = 3, title = "Middle", releaseDate = "2026-07-10", voteAverage = 9.3),
                    ),
                )
            },
        )

        val state = viewModel(http).awaitTheaters()

        // The TMDB route's order is not a contract; newest release first, like the web client.
        assertEquals(
            listOf(
                HomeTheaterMovie(
                    id = 2,
                    title = "Newest",
                    year = "2026",
                    posterUrl = "http://igloo.test:8080/api/tmdb/images/w500/heat2.jpg",
                    rating = 7.9,
                ),
                HomeTheaterMovie(
                    id = 3,
                    title = "Middle",
                    year = "2026",
                    posterUrl = "http://igloo.test:8080/api/tmdb/images/w500/heat2.jpg",
                    rating = 9.3,
                ),
                HomeTheaterMovie(
                    id = 1,
                    title = "Older",
                    year = "2026",
                    posterUrl = "http://igloo.test:8080/api/tmdb/images/w500/heat2.jpg",
                    rating = 5.1,
                ),
            ),
            state.items,
        )
    }

    @Test
    fun `an unrated theater movie maps to a null rating, not zero`() = runTest {
        val http = routedHttp(
            theaters = { jsonResponse(theaterMoviesJson(theaterMovieJson(voteAverage = 0.0))) },
        )

        assertNull(viewModel(http).awaitTheaters().items.single().rating)
    }

    @Test
    fun `a blank theater release date maps to a null year`() = runTest {
        val http = routedHttp(
            theaters = { jsonResponse(theaterMoviesJson(theaterMovieJson(releaseDate = ""))) },
        )

        assertNull(viewModel(http).awaitTheaters().items.single().year)
    }

    @Test
    fun `no theater movies loads as an empty rail, not an error`() = runTest {
        assertTrue(viewModel(routedHttp()).awaitTheaters().items.isEmpty())
    }

    @Test
    fun `a theaters failure leaves the library rails standing`() = runTest {
        val http = routedHttp(
            theaters = {
                jsonResponse(
                    """{"error":true,"message":"tmdb unavailable"}""",
                    HttpStatusCode.InternalServerError,
                )
            },
            latest = { jsonResponse(latestMoviesJson(latestMovieJson(id = 3, title = "Ran"))) },
        )
        val viewModel = viewModel(http)

        assertEquals("tmdb unavailable", viewModel.awaitTheatersError().message)
        assertEquals(listOf("Ran"), viewModel.awaitLatest().items.map { it.title })
    }

    @Test
    fun `retrying the theaters rail reloads only that rail`() = runTest {
        var failed = false
        val gate = CompletableDeferred<Unit>()
        var latestRequests = 0
        val http = routedHttp(
            theaters = {
                if (!failed) {
                    failed = true
                    jsonResponse("""{"error":true,"message":"boom"}""", HttpStatusCode.InternalServerError)
                } else {
                    gate.await()
                    jsonResponse(theaterMoviesJson(theaterMovieJson(id = 4, title = "Heat 2")))
                }
            },
            latest = {
                latestRequests++
                jsonResponse(latestMoviesJson(latestMovieJson(id = 9, title = "Alien")))
            },
        )
        val viewModel = viewModel(http)
        viewModel.awaitTheatersError()
        viewModel.awaitLatest()

        viewModel.retry(HomeRail.InTheaters)

        assertEquals(IglooRailState.Loading, viewModel.uiState.value.inTheaters)
        assertTrue(viewModel.uiState.value.latestMovies is IglooRailState.Loaded)
        gate.complete(Unit)
        assertEquals(listOf("Heat 2"), viewModel.awaitTheaters().items.map { it.title })
        assertEquals(1, latestRequests)
    }

    @Test
    fun `a refresh that fails leaves the loaded theaters rail alone`() = runTest {
        var requests = 0
        val http = routedHttp(
            engineDispatcher = UnconfinedTestDispatcher(testScheduler),
            theaters = {
                requests += 1
                if (requests == 1) {
                    jsonResponse(theaterMoviesJson(theaterMovieJson(id = 1, title = "Heat 2")))
                } else {
                    jsonResponse(
                        """{"error":true,"message":"tmdb unavailable"}""",
                        HttpStatusCode.InternalServerError,
                    )
                }
            },
        )
        val viewModel = viewModel(http)
        assertEquals(listOf("Heat 2"), viewModel.awaitTheaters().items.map { it.title })

        viewModel.refresh()

        assertEquals(2, requests)
        assertEquals(listOf("Heat 2"), viewModel.awaitTheaters().items.map { it.title })

        viewModel.retry(HomeRail.InTheaters)
        assertEquals("tmdb unavailable", viewModel.awaitTheatersError().message)
    }

    @Test
    fun `a refresh that fails leaves the loaded albums alone`() = runTest {
        var requests = 0
        val http = routedHttp(
            engineDispatcher = UnconfinedTestDispatcher(testScheduler),
            albums = {
                requests += 1
                if (requests == 1) {
                    jsonResponse(latestAlbumsJson(simpleAlbumJson(id = 1, title = "Help!")))
                } else {
                    jsonResponse(
                        """{"error":true,"message":"music scan in progress"}""",
                        HttpStatusCode.InternalServerError,
                    )
                }
            },
        )
        val viewModel = viewModel(http)
        assertEquals(listOf("Help!"), viewModel.awaitAlbums().items.map { it.title })

        viewModel.refresh()

        assertEquals(2, requests)
        assertEquals(listOf("Help!"), viewModel.awaitAlbums().items.map { it.title })

        viewModel.retry(HomeRail.LatestAlbums)
        assertEquals("music scan in progress", viewModel.awaitAlbumsError().message)
    }
    @Test
    fun `shows map in server order with normalized posters and nullable years`() = runTest {
        val http = routedHttp(
            latestShows = {
                jsonResponse(
                    latestShowsJson(
                        latestShowJson(id = 40, posterPath = "/severance.jpg"),
                        latestShowJson(id = 12, name = "Friends", posterPath = "friends.jpg", premiereYear = 1994),
                        latestShowJson(id = 7, name = "Untitled", posterPath = null, premiereYear = null),
                    ),
                )
            },
        )

        assertEquals(
            listOf(
                PosterItem(40, "Severance", 2022, "http://igloo.test:8080/api/tmdb/images/w500/severance.jpg"),
                PosterItem(12, "Friends", 1994, "http://igloo.test:8080/api/tmdb/images/w500/friends.jpg"),
                PosterItem(7, "Untitled", null, null),
            ),
            viewModel(http).awaitShows().items,
        )
    }

    @Test
    fun `an empty show library clears the rail without requesting details`() = runTest {
        var detailsRequests = 0
        val viewModel = viewModel(
            routedHttp(showDetails = { detailsRequests++; jsonResponse(showDetailsJson()) }),
        )

        assertTrue(viewModel.awaitShows().items.isEmpty())
        viewModel.awaitHiddenHero()
        assertEquals(0, detailsRequests)
    }

    @Test
    fun `show retry reloads only shows and publishes cards before hero details`() = runTest {
        var requests = 0
        var detailsRequests = 0
        val listGate = CompletableDeferred<Unit>()
        val detailsGate = CompletableDeferred<Unit>()
        val otherRequests = mutableListOf<String>()
        val http = routedHttp(
            engineDispatcher = UnconfinedTestDispatcher(testScheduler),
            continueWatching = { otherRequests += "continue"; jsonResponse(continueWatchingJson()) },
            latest = { otherRequests += "movies"; jsonResponse(latestMoviesJson(latestMovieJson())) },
            albums = { otherRequests += "albums"; jsonResponse(latestAlbumsJson()) },
            theaters = { otherRequests += "theaters"; jsonResponse(theaterMoviesJson()) },
            details = { otherRequests += "movie details"; jsonResponse(movieDetailsJson()) },
            latestShows = {
                requests++
                if (requests == 1) {
                    jsonResponse("""{"error":true,"message":"shows unavailable"}""", HttpStatusCode.InternalServerError)
                } else {
                    listGate.await()
                    jsonResponse(latestShowsJson(latestShowJson(id = 40), latestShowJson(id = 12)))
                }
            },
            showDetails = { request ->
                detailsRequests++
                assertEquals("/api/shows/details/40", request.url.encodedPath)
                detailsGate.await()
                jsonResponse(showDetailsJson(id = 40))
            },
        )
        val viewModel = viewModel(http)
        assertEquals("shows unavailable", viewModel.awaitShowsError().message)
        assertEquals(listOf(HomeHeroKind.Movie), viewModel.awaitHeroPages().map { it.kind })
        val otherBefore = otherRequests.toList()

        viewModel.retry(HomeRail.LatestShows)

        assertEquals(IglooRailState.Loading, viewModel.uiState.value.latestShows)
        assertEquals(HomeHeroState.Loading, viewModel.uiState.value.hero)
        listGate.complete(Unit)
        assertEquals(listOf(40L, 12L), viewModel.awaitShows().items.map { it.id })
        assertEquals(HomeHeroState.Loading, viewModel.uiState.value.hero)
        detailsGate.complete(Unit)
        assertEquals(listOf(HomeHeroKind.Movie, HomeHeroKind.Show), viewModel.awaitHeroPages().map { it.kind })
        assertEquals(2, requests)
        assertEquals(1, detailsRequests)
        assertEquals(otherBefore, otherRequests)
    }

    @Test
    fun `a failed show details request hides only the hero page`() = runTest {
        val viewModel = viewModel(
            routedHttp(
                latestShows = { jsonResponse(latestShowsJson(latestShowJson())) },
                showDetails = {
                    jsonResponse(
                        """{"error":true,"message":"details unavailable"}""",
                        HttpStatusCode.InternalServerError,
                    )
                },
            ),
        )

        viewModel.awaitHiddenHero()
        assertEquals(listOf("Severance"), viewModel.awaitShows().items.map { it.title })
    }

    @Test
    fun `a failed shows refresh retains cards and hero but explicit retry reports the failure`() = runTest {
        var requests = 0
        var detailsRequests = 0
        val refreshGate = CompletableDeferred<Unit>()
        val http = routedHttp(
            engineDispatcher = UnconfinedTestDispatcher(testScheduler),
            latestShows = {
                requests++
                if (requests == 1) {
                    jsonResponse(latestShowsJson(latestShowJson()))
                } else {
                    refreshGate.await()
                    jsonResponse("""{"error":true,"message":"shows unavailable"}""", HttpStatusCode.InternalServerError)
                }
            },
            showDetails = { detailsRequests++; jsonResponse(showDetailsJson()) },
        )
        val viewModel = viewModel(http)
        val cards = viewModel.awaitShows()
        val hero = viewModel.awaitHeroPages()

        viewModel.refresh()
        assertEquals(cards, viewModel.uiState.value.latestShows)
        assertEquals(hero, viewModel.awaitHeroPages())
        refreshGate.complete(Unit)
        assertEquals(2, requests)
        assertEquals(cards, viewModel.uiState.value.latestShows)
        assertEquals(hero, viewModel.awaitHeroPages())
        assertEquals(1, detailsRequests)

        viewModel.retry(HomeRail.LatestShows)
        assertEquals("shows unavailable", viewModel.awaitShowsError().message)
        viewModel.awaitHiddenHero()
    }

    @Test
    fun `a successful empty shows refresh removes its cards and hero page`() = runTest {
        var requests = 0
        var detailsRequests = 0
        val http = routedHttp(
            engineDispatcher = UnconfinedTestDispatcher(testScheduler),
            latest = { jsonResponse(latestMoviesJson(latestMovieJson())) },
            latestShows = {
                requests++
                jsonResponse(if (requests == 1) latestShowsJson(latestShowJson()) else latestShowsJson())
            },
            showDetails = { detailsRequests++; jsonResponse(showDetailsJson()) },
        )
        val viewModel = viewModel(http)
        assertEquals(2, viewModel.awaitHeroPages().size)
        assertEquals(1, viewModel.awaitShows().items.size)

        viewModel.refresh()

        assertTrue(viewModel.awaitShows().items.isEmpty())
        assertEquals(listOf(HomeHeroKind.Movie), viewModel.awaitHeroPages().map { it.kind })
        assertEquals(1, detailsRequests)
    }

    @Test
    fun `a new show retry cancels the earlier list request`() = runTest {
        var requests = 0
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val http = routedHttp(
            latestShows = {
                requests++
                if (requests == 1) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                } else {
                    jsonResponse(latestShowsJson(latestShowJson(id = 12, name = "Friends")))
                }
            },
            showDetails = { jsonResponse(showDetailsJson(id = 12, name = "Friends")) },
        )
        val viewModel = viewModel(http)
        started.await()

        viewModel.retry(HomeRail.LatestShows)

        cancelled.await()
        assertEquals(listOf("Friends"), viewModel.awaitShows().items.map { it.title })
        assertEquals("Friends", viewModel.awaitHero().title)
        assertEquals(2, requests)
    }

    @Test
    fun `a new refresh cancels show details and replaces both rail and hero`() = runTest {
        var requests = 0
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val http = routedHttp(
            latestShows = {
                requests++
                val newest = if (requests == 1) latestShowJson() else latestShowJson(id = 12, name = "Friends")
                jsonResponse(latestShowsJson(newest))
            },
            showDetails = { request ->
                if (request.url.encodedPath.endsWith("/40")) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                } else {
                    jsonResponse(showDetailsJson(id = 12, name = "Friends"))
                }
            },
        )
        val viewModel = viewModel(http)
        started.await()
        assertEquals(listOf("Severance"), viewModel.awaitShows().items.map { it.title })

        viewModel.refresh()

        cancelled.await()
        viewModel.uiState.first { (it.latestShows as? IglooRailState.Loaded)?.items?.singleOrNull()?.id == 12L }
        assertEquals("Friends", viewModel.awaitHero().title)
        assertEquals(2, requests)
    }
}
