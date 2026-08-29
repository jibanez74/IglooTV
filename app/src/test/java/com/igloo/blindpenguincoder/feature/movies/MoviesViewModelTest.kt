package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.movieLibraryItemJson
import com.igloo.blindpenguincoder.data.repository.moviesLibraryJson
import com.igloo.blindpenguincoder.data.repository.moviesStatsJson
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
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
class MoviesViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- first load -------------------------------------------------------------------------

    @Test
    fun `the first load requests page one and exposes the library count`() = runTest {
        val http = routedHttp(
            stats = { jsonResponse(moviesStatsJson(totalMovies = 96)) },
            library = { jsonResponse(page(number = 1, totalPages = 2, ids = 1L..48L)) },
        )

        val model = loaded(http)

        assertEquals((1L..48L).toList(), model.uiState.value.gridIds())
        assertEquals(96L, model.uiState.value.totalMovies)
        assertEquals(MoviesAppendState.Idle, model.uiState.value.append)
        assertEquals(listOf("1"), http.libraryPages)
    }

    @Test
    fun `every page is requested at the contract's maximum size, ascending`() = runTest {
        val http = routedHttp(
            library = { jsonResponse(page(number = it.page().toLong(), totalPages = 3, ids = 1L..2L)) },
        )
        val model = loaded(http)

        model.loadMore()

        assertEquals(listOf("48", "48"), http.perPages)
        assertEquals(listOf("asc", "asc"), http.sorts)
    }

    @Test
    fun `an empty library loads as an empty grid rather than an error`() = runTest {
        val http = routedHttp(
            library = { jsonResponse(page(number = 1, total = 0, totalPages = 0, ids = LongRange.EMPTY)) },
        )

        val state = loaded(http).uiState.value

        assertTrue(state.grid is IglooRailState.Loaded)
        assertEquals(emptyList<Long>(), state.gridIds())
        assertEquals(MoviesAppendState.End, state.append)
    }

    @Test
    fun `a first load that fails shows the error card and its retry reloads`() = runTest {
        var fail = true
        val http = routedHttp(
            library = {
                if (fail) jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
                else jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L))
            },
        )
        val model = loaded(http)
        assertTrue(model.uiState.value.grid is IglooRailState.Error)
        // Nothing was on screen to protect, so there is no notice — the card owns the message.
        assertNull(model.uiState.value.notice)

        fail = false
        model.retryFirstPage()

        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
    }

    // --- appending --------------------------------------------------------------------------

    @Test
    fun `load more appends the next page after the first`() = runTest {
        val http = routedHttp(
            library = {
                when (it.page()) {
                    "1" -> jsonResponse(page(number = 1, totalPages = 3, ids = 1L..48L))
                    else -> jsonResponse(page(number = 2, totalPages = 3, ids = 49L..96L))
                }
            },
        )
        val model = loaded(http)

        model.loadMore()

        assertEquals((1L..96L).toList(), model.uiState.value.gridIds())
        assertEquals(MoviesAppendState.Idle, model.uiState.value.append)
        assertEquals(listOf("1", "2"), http.libraryPages)
    }

    @Test
    fun `concurrent load more calls issue a single request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val http = routedHttp(
            library = {
                if (it.page() != "1") gate.await()
                jsonResponse(page(number = it.page().toLong(), totalPages = 3, ids = 1L..2L))
            },
        )
        val model = loaded(http)

        // The prefetch trigger is a scroll threshold: a focus change, a recomposition and a
        // resize can each re-fire it for the same page before the first response lands.
        model.loadMore()
        model.loadMore()
        model.loadMore()
        gate.complete(Unit)

        assertEquals(listOf("1", "2"), http.libraryPages)
    }

    @Test
    fun `the last page ends the tail and no further page is requested`() = runTest {
        val http = routedHttp(
            library = { jsonResponse(page(number = it.page().toLong(), totalPages = 2, ids = 1L..2L)) },
        )
        val model = loaded(http)

        model.loadMore()
        assertEquals(MoviesAppendState.End, model.uiState.value.append)
        model.loadMore()

        assertEquals(listOf("1", "2"), http.libraryPages)
    }

    /** A library shrinking mid-scroll can return nothing for page N while still claiming more. */
    @Test
    fun `an empty page ends the tail even when total pages disagrees`() = runTest {
        val http = routedHttp(
            library = {
                when (it.page()) {
                    "1" -> jsonResponse(page(number = 1, totalPages = 5, ids = 1L..3L))
                    else -> jsonResponse(page(number = 2, totalPages = 5, ids = LongRange.EMPTY))
                }
            },
        )
        val model = loaded(http)

        model.loadMore()

        assertEquals(MoviesAppendState.End, model.uiState.value.append)
        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
    }

    /**
     * A–Z paging over a library being scanned shifts rows between pages. A repeated id is a
     * duplicate key, which is a crash in LazyVerticalGrid rather than a visual glitch.
     */
    @Test
    fun `a movie repeated across pages is appended only once`() = runTest {
        val http = routedHttp(
            library = {
                when (it.page()) {
                    "1" -> jsonResponse(page(number = 1, totalPages = 3, ids = 1L..3L))
                    else -> jsonResponse(page(number = 2, totalPages = 3, ids = 3L..5L))
                }
            },
        )
        val model = loaded(http)

        model.loadMore()

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), model.uiState.value.gridIds())
    }

    @Test
    fun `a failed append keeps the loaded items and offers a retry tail`() = runTest {
        val http = routedHttp(library = failingAfterFirstPage())
        val model = loaded(http)

        model.loadMore()

        assertEquals((1L..48L).toList(), model.uiState.value.gridIds())
        assertTrue(model.uiState.value.append is MoviesAppendState.Error)
    }

    @Test
    fun `retrying a failed append refetches the same page`() = runTest {
        var fail = true
        val http = routedHttp(
            library = {
                when {
                    it.page() == "1" -> jsonResponse(page(number = 1, totalPages = 2, ids = 1L..3L))
                    fail -> jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
                    else -> jsonResponse(page(number = 2, totalPages = 2, ids = 4L..6L))
                }
            },
        )
        val model = loaded(http)
        model.loadMore()

        fail = false
        model.retryAppend()

        assertEquals(listOf("1", "2", "2"), http.libraryPages)
        assertEquals((1L..6L).toList(), model.uiState.value.gridIds())
        assertEquals(MoviesAppendState.End, model.uiState.value.append)
    }

    /** Only retryAppend clears an error tail; the scroll trigger must not hammer a down server. */
    @Test
    fun `load more does nothing while the tail is in its error state`() = runTest {
        val http = routedHttp(library = failingAfterFirstPage())
        val model = loaded(http)
        model.loadMore()

        model.loadMore()
        model.loadMore()

        assertEquals(listOf("1", "2"), http.libraryPages)
    }

    // --- refresh ----------------------------------------------------------------------------

    @Test
    fun `refresh does not reload a grid that is already loaded`() = runTest {
        val http = routedHttp(
            library = { jsonResponse(page(number = it.page().toLong(), totalPages = 3, ids = 1L..3L)) },
        )
        val model = loaded(http)
        model.loadMore()

        // A TV waking from standby must keep the pages the user scrolled through.
        model.refresh()

        assertEquals(listOf("1", "2"), http.libraryPages)
        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
    }

    @Test
    fun `reload drops the appended pages and refetches from page one`() = runTest {
        val http = routedHttp(
            library = {
                when (it.page()) {
                    "1" -> jsonResponse(page(number = 1, totalPages = 3, ids = 1L..3L))
                    else -> jsonResponse(page(number = 2, totalPages = 3, ids = 4L..6L))
                }
            },
        )
        val model = loaded(http)
        model.loadMore()
        val generationBefore = model.uiState.value.contentGeneration

        model.reload()

        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
        assertEquals(listOf("1", "2", "1"), http.libraryPages)
        // The screen scrolls to top and re-anchors focus off this change.
        assertTrue(model.uiState.value.contentGeneration > generationBefore)
    }

    /** Blanking the grid mid-request would dispose the focused cell and drop focus. */
    @Test
    fun `the loaded grid stays on screen while a reload is in flight`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var gated = false
        val http = routedHttp(
            library = {
                if (gated) gate.await()
                jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L))
            },
        )
        val model = loaded(http)

        gated = true
        model.reload()

        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
        assertTrue(model.uiState.value.refreshing)
        gate.complete(Unit)
        assertTrue(!model.uiState.value.refreshing)
    }

    @Test
    fun `a second reload while one is running is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var gated = false
        val http = routedHttp(
            library = {
                if (gated) gate.await()
                jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L))
            },
        )
        val model = loaded(http)

        gated = true
        model.reload()
        model.reload()
        gate.complete(Unit)

        assertEquals(listOf("1", "1"), http.libraryPages)
    }

    @Test
    fun `a failed reload keeps the grid and reports through a notice`() = runTest {
        var fail = false
        val http = routedHttp(
            library = {
                if (fail) jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
                else jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L))
            },
        )
        val model = loaded(http)

        fail = true
        model.reload()

        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
        // A notice beside the Refresh button, not an error card offering a redundant Retry.
        assertTrue(model.uiState.value.notice != null)
        assertTrue(!model.uiState.value.refreshing)
    }

    @Test
    fun `a failed background refresh keeps the loaded grid`() = runTest {
        val http = routedHttp(
            stats = { jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) },
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)

        model.refresh()

        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
        assertNull(model.uiState.value.notice)
    }

    // --- stats ------------------------------------------------------------------------------

    @Test
    fun `a stats failure leaves the grid alone`() = runTest {
        val http = routedHttp(
            stats = { jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) },
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        )

        val state = loaded(http).uiState.value

        assertNull(state.totalMovies)
        assertEquals(listOf(1L, 2L, 3L), state.gridIds())
    }

    @Test
    fun `a stats failure on a later refresh keeps the last known count`() = runTest {
        var fail = false
        val http = routedHttp(
            stats = {
                if (fail) jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
                else jsonResponse(moviesStatsJson(totalMovies = 96))
            },
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)

        fail = true
        model.refresh()

        assertEquals(96L, model.uiState.value.totalMovies)
    }

    // --- harness ----------------------------------------------------------------------------

    /** The host's start effect is what fires the first load; there is no fetch in `init`. */
    private fun loaded(http: RoutedHttp) =
        MoviesViewModel(http.test.movieRepository, http.test.serverUrl).also { it.refresh() }

    private fun MoviesUiState.gridIds(): List<Long> =
        (grid as IglooRailState.Loaded).items.map { it.id }

    private fun HttpRequestData.page(): String = url.parameters["page"].orEmpty()

    private fun failingAfterFirstPage():
        suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
        if (it.page() == "1") {
            jsonResponse(page(number = 1, totalPages = 3, ids = 1L..48L))
        } else {
            jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
        }
    }

    private fun page(
        number: Long,
        totalPages: Long,
        ids: LongRange,
        total: Long = 96,
    ): String = moviesLibraryJson(
        page = number,
        total = total,
        totalPages = totalPages,
        movies = ids.map { movieLibraryItemJson(id = it, title = "Movie $it") }.toTypedArray(),
    )

    /** Records what the grid actually asked the backend for, so the paging can be asserted. */
    private class RoutedHttp(
        val libraryPages: MutableList<String>,
        val perPages: MutableList<String>,
        val sorts: MutableList<String>,
    ) {
        lateinit var test: TestHttp
    }

    /**
     * The mock engine is put on the caller's test scheduler so a response and the view model
     * share one clock; on its production default a real thread hop escapes `runTest` and every
     * assertion would read state that has not been written yet.
     */
    private fun TestScope.routedHttp(
        stats: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(moviesStatsJson()) },
        library: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(moviesLibraryJson()) },
    ): RoutedHttp {
        val routed = RoutedHttp(mutableListOf(), mutableListOf(), mutableListOf())
        routed.test = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
            when (request.url.encodedPath) {
                "/api/movies/stats" -> stats(request)
                "/api/movies/library" -> {
                    routed.libraryPages += request.url.parameters["page"].orEmpty()
                    routed.perPages += request.url.parameters["per_page"].orEmpty()
                    routed.sorts += request.url.parameters["sort"].orEmpty()
                    library(request)
                }
                else -> error("unexpected request to ${request.url.encodedPath}")
            }
        }
        return routed
    }

    private companion object {
        const val ERROR_BODY = """{"error":true,"message":"nope"}"""
    }
}
