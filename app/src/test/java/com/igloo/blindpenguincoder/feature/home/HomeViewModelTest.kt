package com.igloo.blindpenguincoder.feature.home

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.latestMovieJson
import com.igloo.blindpenguincoder.data.repository.latestMoviesJson
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

    @Test
    fun `movies map to render-ready cards with normalized poster URLs`() = runTest {
        val http = TestHttp {
            jsonResponse(
                latestMoviesJson(
                    latestMovieJson(id = 1, title = "Heat", posterPath = "/heat.jpg", year = 1995),
                    latestMovieJson(id = 2, title = "Arrival", posterPath = "arrival.jpg", year = 2016),
                ),
            )
        }

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
        val http = TestHttp {
            jsonResponse(latestMoviesJson(latestMovieJson(posterPath = null, year = null)))
        }

        val state = viewModel(http).latestMovies
            .first { it is IglooRailState.Loaded } as IglooRailState.Loaded

        assertNull(state.items.single().posterUrl)
        assertNull(state.items.single().year)
    }

    @Test
    fun `an empty library loads as an empty rail, not an error`() = runTest {
        val http = TestHttp { jsonResponse(latestMoviesJson()) }

        val state = viewModel(http).latestMovies
            .first { it is IglooRailState.Loaded } as IglooRailState.Loaded

        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `a server failure surfaces the backend message with a retryable state`() = runTest {
        val http = TestHttp {
            jsonResponse(
                """{"error":true,"message":"scan in progress"}""",
                HttpStatusCode.InternalServerError,
            )
        }

        val state = viewModel(http).latestMovies
            .first { it is IglooRailState.Error } as IglooRailState.Error

        assertEquals("scan in progress", state.message)
    }

    @Test
    fun `retry re-enters loading and then succeeds`() = runTest {
        var failed = false
        val gate = CompletableDeferred<Unit>()
        val http = TestHttp {
            if (!failed) {
                failed = true
                jsonResponse("""{"error":true,"message":"boom"}""", HttpStatusCode.InternalServerError)
            } else {
                gate.await()
                jsonResponse(latestMoviesJson(latestMovieJson(id = 3, title = "Ran")))
            }
        }
        val viewModel = viewModel(http)
        viewModel.latestMovies.first { it is IglooRailState.Error }

        viewModel.retry()

        // The gated second request holds the rail in Loading so the skeleton renders again.
        assertEquals(IglooRailState.Loading, viewModel.latestMovies.value)
        gate.complete(Unit)
        val state = viewModel.latestMovies
            .first { it is IglooRailState.Loaded } as IglooRailState.Loaded
        assertEquals(listOf("Ran"), state.items.map { it.title })
    }
}
