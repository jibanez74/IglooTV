package com.igloo.blindpenguincoder.feature.home

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.continueWatchingMovieJson
import com.igloo.blindpenguincoder.data.repository.continueWatchingMoviesJson
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.latestAlbumsJson
import com.igloo.blindpenguincoder.data.repository.latestMovieJson
import com.igloo.blindpenguincoder.data.repository.latestMoviesJson
import com.igloo.blindpenguincoder.data.repository.movieDetailsJson
import com.igloo.blindpenguincoder.data.repository.simpleAlbumJson
import com.igloo.blindpenguincoder.data.repository.theaterMovieJson
import com.igloo.blindpenguincoder.data.repository.theaterMoviesJson
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
    private fun viewModel(http: TestHttp) =
        HomeViewModel(http.movieRepository, http.musicRepository, http.serverUrl)
            .also { it.refresh() }

    /** A refresh fires all four rails' requests plus the hero's, so handlers route by path. */
    private fun routedHttp(
        engineDispatcher: CoroutineDispatcher? = null,
        continueWatching: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(continueWatchingMoviesJson()) },
        latest: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(latestMoviesJson()) },
        details: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(movieDetailsJson()) },
        albums: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(latestAlbumsJson()) },
        theaters: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(theaterMoviesJson()) },
    ) = TestHttp(engineDispatcher) { request ->
        val path = request.url.encodedPath
        when {
            path == "/api/movies/continue-watching" -> continueWatching(request)
            path == "/api/music/albums/latest" -> albums(request)
            path == "/api/tmdb/movies/in-theaters" -> theaters(request)
            // Before the catch-all: the details call must never silently get a latest-shaped body.
            path.startsWith("/api/movies/details/") -> details(request)
            else -> latest(request)
        }
    }

    private suspend fun HomeViewModel.awaitLatest(): IglooRailState.Loaded<HomeMovie> =
        uiState.first { it.latestMovies is IglooRailState.Loaded }
            .latestMovies as IglooRailState.Loaded

    private suspend fun HomeViewModel.awaitContinue(): IglooRailState.Loaded<HomeContinueMovie> =
        uiState.first { it.continueWatching is IglooRailState.Loaded }
            .continueWatching as IglooRailState.Loaded

    private suspend fun HomeViewModel.awaitHero(): HomeHero =
        (uiState.first { it.hero is HomeHeroState.Loaded }.hero as HomeHeroState.Loaded).hero

    private suspend fun HomeViewModel.awaitHiddenHero() {
        uiState.first { it.hero is HomeHeroState.Hidden }
    }

    private suspend fun HomeViewModel.awaitLatestError(): IglooRailState.Error =
        uiState.first { it.latestMovies is IglooRailState.Error }
            .latestMovies as IglooRailState.Error

    private suspend fun HomeViewModel.awaitContinueError(): IglooRailState.Error =
        uiState.first { it.continueWatching is IglooRailState.Error }
            .continueWatching as IglooRailState.Error

    private suspend fun HomeViewModel.awaitAlbums(): IglooRailState.Loaded<HomeAlbum> =
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
            continueWatching = { requests++; jsonResponse(continueWatchingMoviesJson()) },
            latest = { requests++; jsonResponse(latestMoviesJson()) },
            albums = { requests++; jsonResponse(latestAlbumsJson()) },
            theaters = { requests++; jsonResponse(theaterMoviesJson()) },
        )

        val viewModel = HomeViewModel(http.movieRepository, http.musicRepository, http.serverUrl)

        assertEquals(0, requests)
        assertEquals(IglooRailState.Loading, viewModel.uiState.value.continueWatching)
        assertEquals(IglooRailState.Loading, viewModel.uiState.value.latestMovies)
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
                HomeMovie(1, "Heat", 1995, "http://igloo.test:8080/api/tmdb/images/w500/heat.jpg"),
                HomeMovie(2, "Arrival", 2016, "http://igloo.test:8080/api/tmdb/images/w500/arrival.jpg"),
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
                    continueWatchingMoviesJson(
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
        assertEquals(listOf("Heat"), viewModel.awaitContinue().items.map { it.movie.title })

        viewModel.refreshContinueWatching()
        refreshReached.await()

        val during = viewModel.uiState.value.continueWatching
        assertTrue(during is IglooRailState.Loaded)
        assertEquals(listOf("Heat"), (during as IglooRailState.Loaded).items.map { it.movie.title })
        assertEquals(2, continueRequests)
        assertEquals(1, latestRequests)
        assertEquals(1, albumRequests)
        assertEquals(1, theaterRequests)

        releaseRefresh.complete(Unit)
        viewModel.uiState.first {
            (it.continueWatching as? IglooRailState.Loaded)?.items?.single()?.movie?.title == "Arrival"
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
    fun `continue watching maps to cards with progress fraction and minutes left`() = runTest {
        val http = routedHttp(
            continueWatching = {
                jsonResponse(
                    continueWatchingMoviesJson(
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
                HomeContinueMovie(
                    movie = HomeMovie(
                        1,
                        "Heat",
                        1995,
                        "http://igloo.test:8080/api/tmdb/images/w500/heat.jpg",
                    ),
                    progressFraction = (1800.0 / 10200.0).toFloat(),
                    progressLabel = "140 min left",
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
                    continueWatchingMoviesJson(
                        continueWatchingMovieJson(id = 1, progressSec = 7300.0, durationSec = 7200.0),
                        continueWatchingMovieJson(id = 2, progressSec = 60.0, durationSec = 0.0),
                    ),
                )
            },
        )

        val state = viewModel(http).awaitContinue()

        val (overshot, zeroDuration) = state.items
        assertEquals(1f, overshot.progressFraction, 0f)
        assertEquals("1 min left", overshot.progressLabel)
        assertEquals(0f, zeroDuration.progressFraction, 0f)
        assertEquals("In progress", zeroDuration.progressLabel)
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
                id = 7,
                title = "Heat",
                backdropUrl = "http://igloo.test:8080/api/tmdb/images/w1280/heat-backdrop.jpg",
                overview = "Neil McCauley leads a top-notch crew.",
                metadataLine = "1995 · R · 2h 50m · 8.2",
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

        assertEquals(HomeHero(1, "Heat", null, null, null), hero)
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

        assertEquals("R", viewModel(http).awaitHero().metadataLine)
    }

    @Test
    fun `runtimes format as hours and minutes`() = runTest {
        suspend fun metadataLine(runTimeMinutes: Long): String? {
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
            return viewModel(http).awaitHero().metadataLine
        }

        assertEquals("45m", metadataLine(45))
        assertEquals("2h", metadataLine(120))
        assertEquals("2h 50m", metadataLine(170))
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
                    jsonResponse(continueWatchingMoviesJson(continueWatchingMovieJson(id = 3, title = "Ran")))
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
        assertEquals(listOf("Ran"), viewModel.awaitContinue().items.map { it.movie.title })
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
        assertEquals("Zac Brown Band", album.musician)
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
        assertNull(album.musician)
    }

    @Test
    fun `a blank cover or musician is absent, not an empty line under the title`() = runTest {
        val http = routedHttp(
            albums = { jsonResponse(latestAlbumsJson(simpleAlbumJson(cover = " ", musician = ""))) },
        )

        val album = viewModel(http).awaitAlbums().items.single()

        assertNull(album.coverUrl)
        assertNull(album.musician)
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
}
