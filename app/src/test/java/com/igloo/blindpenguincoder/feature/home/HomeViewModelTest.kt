package com.igloo.blindpenguincoder.feature.home

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.continueWatchingMovieJson
import com.igloo.blindpenguincoder.data.repository.continueWatchingMoviesJson
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.latestMovieJson
import com.igloo.blindpenguincoder.data.repository.latestMoviesJson
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
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

    private fun viewModel(http: TestHttp) = HomeViewModel(http.movieRepository, http.serverUrl)

    /** The view model fires both rails' requests on init, so every handler routes by path. */
    private fun routedHttp(
        continueWatching: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(continueWatchingMoviesJson()) },
        latest: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(latestMoviesJson()) },
    ) = TestHttp { request ->
        when (request.url.encodedPath) {
            "/api/movies/continue-watching" -> continueWatching(request)
            else -> latest(request)
        }
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

        val state = viewModel(http).latestMovies
            .first { it is IglooRailState.Loaded } as IglooRailState.Loaded

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

        val state = viewModel(http).latestMovies
            .first { it is IglooRailState.Loaded } as IglooRailState.Loaded

        assertNull(state.items.single().posterUrl)
        assertNull(state.items.single().year)
    }

    @Test
    fun `an empty library loads as an empty rail, not an error`() = runTest {
        val http = routedHttp()

        val state = viewModel(http).latestMovies
            .first { it is IglooRailState.Loaded } as IglooRailState.Loaded

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

        val state = viewModel(http).latestMovies
            .first { it is IglooRailState.Error } as IglooRailState.Error

        assertEquals("scan in progress", state.message)
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
        viewModel.latestMovies.first { it is IglooRailState.Error }

        viewModel.retryLatestMovies()

        // The gated second request holds the rail in Loading so the skeleton renders again.
        assertEquals(IglooRailState.Loading, viewModel.latestMovies.value)
        gate.complete(Unit)
        val state = viewModel.latestMovies
            .first { it is IglooRailState.Loaded } as IglooRailState.Loaded
        assertEquals(listOf("Ran"), state.items.map { it.title })
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

        val state = viewModel(http).continueWatching
            .first { it is IglooRailState.Loaded } as IglooRailState.Loaded

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

        val state = viewModel(http).continueWatching
            .first { it is IglooRailState.Loaded } as IglooRailState.Loaded

        val (overshot, zeroDuration) = state.items
        assertEquals(1f, overshot.progressFraction, 0f)
        assertEquals("1 min left", overshot.progressLabel)
        assertEquals(0f, zeroDuration.progressFraction, 0f)
        assertEquals("In progress", zeroDuration.progressLabel)
    }

    @Test
    fun `nothing in progress loads as an empty rail, not an error`() = runTest {
        val http = routedHttp()

        val state = viewModel(http).continueWatching
            .first { it is IglooRailState.Loaded } as IglooRailState.Loaded

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

        val continueState = viewModel.continueWatching
            .first { it is IglooRailState.Error } as IglooRailState.Error
        val latestState = viewModel.latestMovies
            .first { it is IglooRailState.Loaded } as IglooRailState.Loaded

        assertEquals("scan in progress", continueState.message)
        assertEquals(listOf("Ran"), latestState.items.map { it.title })
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
        viewModel.continueWatching.first { it is IglooRailState.Error }
        viewModel.latestMovies.first { it is IglooRailState.Loaded }

        viewModel.retryContinueWatching()

        assertEquals(IglooRailState.Loading, viewModel.continueWatching.value)
        assertTrue(viewModel.latestMovies.value is IglooRailState.Loaded)
        gate.complete(Unit)
        val state = viewModel.continueWatching
            .first { it is IglooRailState.Loaded } as IglooRailState.Loaded
        assertEquals(listOf("Ran"), state.items.map { it.movie.title })
        assertEquals(1, latestRequests)
    }
}
