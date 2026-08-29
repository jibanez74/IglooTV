package com.igloo.blindpenguincoder.feature.movies

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.movieGenreWithCountJson
import com.igloo.blindpenguincoder.data.repository.movieLibraryItemJson
import com.igloo.blindpenguincoder.data.repository.moviesGenresJson
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
    fun `a successful append updates the library count from that page`() = runTest {
        val http = routedHttp(
            library = {
                when (it.page()) {
                    "1" -> jsonResponse(page(number = 1, total = 96, totalPages = 3, ids = 1L..3L))
                    else -> jsonResponse(page(number = 2, total = 97, totalPages = 3, ids = 4L..6L))
                }
            },
        )
        val model = loaded(http)

        model.loadMore()

        assertEquals(97L, model.uiState.value.totalMovies)
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
    fun `a fully overlapping append still advances its generation`() = runTest {
        val http = routedHttp(
            library = {
                jsonResponse(
                    page(
                        number = it.page().toLong(),
                        totalPages = 3,
                        ids = 1L..3L,
                    ),
                )
            },
        )
        val model = loaded(http)
        val generationBefore = model.uiState.value.appendGeneration

        model.loadMore()

        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
        assertEquals(generationBefore + 1, model.uiState.value.appendGeneration)
        assertEquals(MoviesAppendState.Idle, model.uiState.value.append)
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
    fun `a failed reload after canceling an append rearms the canceled page`() = runTest {
        val appendGate = CompletableDeferred<Unit>()
        var firstPageCalls = 0
        var secondPageCalls = 0
        val http = routedHttp(
            library = {
                when (it.page()) {
                    "1" -> {
                        firstPageCalls += 1
                        if (firstPageCalls == 1) {
                            jsonResponse(page(number = 1, totalPages = 3, ids = 1L..3L))
                        } else {
                            jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
                        }
                    }
                    else -> {
                        secondPageCalls += 1
                        if (secondPageCalls == 1) appendGate.await()
                        jsonResponse(page(number = 2, totalPages = 3, ids = 4L..6L))
                    }
                }
            },
        )
        val model = loaded(http)

        model.loadMore()
        assertEquals(MoviesAppendState.Loading, model.uiState.value.append)
        model.reload()

        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
        assertEquals(MoviesAppendState.Idle, model.uiState.value.append)
        assertTrue(!model.uiState.value.refreshing)

        model.loadMore()

        assertEquals(listOf("1", "2", "1", "2"), http.libraryPages)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), model.uiState.value.gridIds())
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
    fun `a stats failure uses the library page count`() = runTest {
        val http = routedHttp(
            stats = { jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) },
            library = {
                jsonResponse(page(number = 1, total = 73, totalPages = 1, ids = 1L..3L))
            },
        )

        val state = loaded(http).uiState.value

        assertEquals(73L, state.totalMovies)
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

    // --- filters and sort -------------------------------------------------------------------

    @Test
    fun `selecting the liked filter requests page one of the liked endpoint`() = runTest {
        val http = routedHttp(
            liked = { jsonResponse(page(number = 1, total = 3, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)

        model.selectFilter(MoviesFilter.Liked)

        assertEquals(listOf("1"), http.likedPages)
        assertEquals(MoviesFilter.Liked, model.uiState.value.filter)
        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
        assertEquals(3L, model.uiState.value.totalMovies)
    }

    @Test
    fun `selecting a genre requests that genre's movies at the contract's page size`() = runTest {
        val http = routedHttp(
            genreMovies = { jsonResponse(page(number = 1, total = 26, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)

        model.selectFilter(MoviesFilter.Genre(id = 7, tag = "Action"))

        assertEquals(listOf("7:1"), http.genrePages)
        assertEquals(listOf("48", "48"), http.perPages)
        assertEquals(26L, model.uiState.value.totalMovies)
    }

    @Test
    fun `toggling sort re-requests page one of the current filter in the other direction`() = runTest {
        val http = routedHttp(
            genreMovies = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)
        model.selectFilter(MoviesFilter.Genre(id = 7, tag = "Action"))

        model.toggleSort()

        // The flip stays on the genre endpoint; the library is not re-read.
        assertEquals(listOf("7:1", "7:1"), http.genrePages)
        assertEquals(listOf("1"), http.libraryPages)
        assertEquals(listOf("asc", "asc", "desc"), http.sorts)
        assertEquals(SortOrder.Descending, model.uiState.value.sort)
    }

    @Test
    fun `a failed filter switch keeps the grid, reverts the selection, and reports a notice`() = runTest {
        val http = routedHttp(
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
            liked = { jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) },
        )
        val model = loaded(http)

        model.selectFilter(MoviesFilter.Liked)

        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
        assertEquals(MoviesFilter.All, model.uiState.value.filter)
        assertTrue(model.uiState.value.notice != null)
    }

    @Test
    fun `a failed sort toggle reverts the direction`() = runTest {
        var fail = false
        val http = routedHttp(
            library = {
                if (fail) jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
                else jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L))
            },
        )
        val model = loaded(http)

        fail = true
        model.toggleSort()

        assertEquals(SortOrder.Ascending, model.uiState.value.sort)
        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
    }

    @Test
    fun `a filter switch mid-append discards the stale page`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val http = routedHttp(
            library = {
                when (it.page()) {
                    "1" -> jsonResponse(page(number = 1, totalPages = 3, ids = 1L..3L))
                    else -> {
                        gate.await()
                        jsonResponse(page(number = 2, totalPages = 3, ids = 4L..6L))
                    }
                }
            },
            liked = { jsonResponse(page(number = 1, totalPages = 1, ids = 10L..12L)) },
        )
        val model = loaded(http)
        model.loadMore()

        model.selectFilter(MoviesFilter.Liked)
        gate.complete(Unit)

        assertEquals(listOf(10L, 11L, 12L), model.uiState.value.gridIds())
    }

    @Test
    fun `appending in a filtered view pages the same endpoint`() = runTest {
        val http = routedHttp(
            liked = {
                when (it.page()) {
                    "1" -> jsonResponse(page(number = 1, totalPages = 2, ids = 1L..3L))
                    else -> jsonResponse(page(number = 2, totalPages = 2, ids = 4L..6L))
                }
            },
        )
        val model = loaded(http)
        model.selectFilter(MoviesFilter.Liked)

        model.loadMore()

        assertEquals(listOf("1", "2"), http.likedPages)
        assertEquals(listOf("1"), http.libraryPages)
        assertEquals((1L..6L).toList(), model.uiState.value.gridIds())
    }

    @Test
    fun `a filtered count comes from the response total and stats never overwrite it`() = runTest {
        val http = routedHttp(
            stats = { jsonResponse(moviesStatsJson(totalMovies = 96)) },
            genreMovies = { jsonResponse(page(number = 1, total = 26, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)
        model.selectFilter(MoviesFilter.Genre(id = 7, tag = "Action"))

        // The start effect re-fires on every return to the foreground and re-reads stats.
        model.refresh()

        assertEquals(26L, model.uiState.value.totalMovies)
    }

    @Test
    fun `re-pressing the selected filter is a no-op`() = runTest {
        val http = routedHttp(
            liked = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)
        model.selectFilter(MoviesFilter.Liked)

        model.selectFilter(MoviesFilter.Liked)

        assertEquals(listOf("1"), http.likedPages)
    }

    @Test
    fun `a genres failure keeps the last known genre list`() = runTest {
        var fail = false
        val http = routedHttp(
            genres = {
                if (fail) jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
                else jsonResponse(moviesGenresJson(movieGenreWithCountJson(id = 7, tag = "Action")))
            },
        )
        val model = loaded(http)
        assertEquals(1, model.uiState.value.genres.size)

        fail = true
        model.reload()

        assertEquals("Action", model.uiState.value.genres.single().genreTag)
    }

    // --- liked reconcile --------------------------------------------------------------------

    @Test
    fun `a like commit on the liked filter re-reads page one silently`() = runTest {
        var unliked = false
        val http = routedHttp(
            liked = {
                if (unliked) jsonResponse(page(number = 1, total = 2, totalPages = 1, ids = 1L..2L))
                else jsonResponse(page(number = 1, total = 3, totalPages = 1, ids = 1L..3L))
            },
        )
        val model = loaded(http)
        model.selectFilter(MoviesFilter.Liked)
        val generationBefore = model.uiState.value.contentGeneration
        val silentGenerationBefore = model.uiState.value.silentReconcileGeneration

        unliked = true
        model.onLikeCommitted()

        assertEquals(listOf("1", "1"), http.likedPages)
        assertEquals(listOf(1L, 2L), model.uiState.value.gridIds())
        // Silent: the reconcile happens under the open details overlay, where a refreshing
        // label or a generation bump would scroll and steal focus from it.
        assertTrue(!model.uiState.value.refreshing)
        assertEquals(generationBefore, model.uiState.value.contentGeneration)
        assertEquals(
            silentGenerationBefore + 1,
            model.uiState.value.silentReconcileGeneration,
        )
    }

    @Test
    fun `a failed liked reconcile keeps the grid selection notice and generations`() = runTest {
        var likedCalls = 0
        val http = routedHttp(
            liked = {
                likedCalls += 1
                if (likedCalls == 1) {
                    jsonResponse(page(number = 1, total = 3, totalPages = 1, ids = 1L..3L))
                } else {
                    jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
                }
            },
        )
        val model = loaded(http)
        model.selectFilter(MoviesFilter.Liked)
        model.reload()
        val before = model.uiState.value
        assertTrue(before.notice != null)

        model.onLikeCommitted()

        val after = model.uiState.value
        assertEquals(before.gridIds(), after.gridIds())
        assertEquals(before.filter, after.filter)
        assertEquals(before.notice, after.notice)
        assertEquals(before.contentGeneration, after.contentGeneration)
        assertEquals(before.silentReconcileGeneration, after.silentReconcileGeneration)
        assertTrue(!after.refreshing)
    }

    @Test
    fun `a reconcile superseding a liked reload clears refreshing immediately and terminally`() =
        runTest {
            var likedCalls = 0
            val reloadGate = CompletableDeferred<Unit>()
            val reconcileGate = CompletableDeferred<Unit>()
            val http = routedHttp(
                liked = {
                    likedCalls += 1
                    when (likedCalls) {
                        1 -> jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L))
                        2 -> {
                            reloadGate.await()
                            jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L))
                        }
                        else -> {
                            reconcileGate.await()
                            jsonResponse(page(number = 1, totalPages = 1, ids = 1L..2L))
                        }
                    }
                },
            )
            val model = loaded(http)
            model.selectFilter(MoviesFilter.Liked)

            model.reload()
            assertTrue(model.uiState.value.refreshing)
            model.onLikeCommitted()
            assertTrue(!model.uiState.value.refreshing)

            reconcileGate.complete(Unit)

            assertTrue(!model.uiState.value.refreshing)
            assertEquals(listOf(1L, 2L), model.uiState.value.gridIds())
        }

    @Test
    fun `a failed reconcile superseding a liked reload also leaves refreshing cleared`() =
        runTest {
            var likedCalls = 0
            val reloadGate = CompletableDeferred<Unit>()
            val reconcileGate = CompletableDeferred<Unit>()
            val http = routedHttp(
                liked = {
                    likedCalls += 1
                    when (likedCalls) {
                        1 -> jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L))
                        2 -> {
                            reloadGate.await()
                            jsonResponse(page(number = 1, totalPages = 1, ids = 4L..6L))
                        }
                        else -> {
                            reconcileGate.await()
                            jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
                        }
                    }
                },
            )
            val model = loaded(http)
            model.selectFilter(MoviesFilter.Liked)
            val silentGenerationBefore = model.uiState.value.silentReconcileGeneration

            model.reload()
            model.onLikeCommitted()
            assertTrue(!model.uiState.value.refreshing)
            reconcileGate.complete(Unit)

            assertTrue(!model.uiState.value.refreshing)
            assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
            assertEquals(
                silentGenerationBefore,
                model.uiState.value.silentReconcileGeneration,
            )
        }

    @Test
    fun `a reconcile superseding a liked filter request clears its pending state`() = runTest {
        var likedCalls = 0
        val filterGate = CompletableDeferred<Unit>()
        val reconcileGate = CompletableDeferred<Unit>()
        val http = routedHttp(
            liked = {
                likedCalls += 1
                if (likedCalls == 1) {
                    filterGate.await()
                    jsonResponse(page(number = 1, totalPages = 1, ids = 10L..12L))
                } else {
                    reconcileGate.await()
                    jsonResponse(page(number = 1, totalPages = 1, ids = 20L..21L))
                }
            },
        )
        val model = loaded(http)

        model.selectFilter(MoviesFilter.Liked)
        assertTrue(model.uiState.value.refreshing)
        model.onLikeCommitted()
        assertTrue(!model.uiState.value.refreshing)
        reconcileGate.complete(Unit)

        assertEquals(MoviesFilter.Liked, model.uiState.value.filter)
        assertEquals(listOf(20L, 21L), model.uiState.value.gridIds())
        assertTrue(!model.uiState.value.refreshing)
    }

    @Test
    fun `reload remains available while a silent reconcile is running`() = runTest {
        var likedCalls = 0
        val reconcileGate = CompletableDeferred<Unit>()
        val http = routedHttp(
            liked = {
                likedCalls += 1
                when (likedCalls) {
                    1 -> jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L))
                    2 -> {
                        reconcileGate.await()
                        jsonResponse(page(number = 1, totalPages = 1, ids = 1L..2L))
                    }
                    else -> jsonResponse(page(number = 1, totalPages = 1, ids = 4L..6L))
                }
            },
        )
        val model = loaded(http)
        model.selectFilter(MoviesFilter.Liked)
        model.onLikeCommitted()
        assertTrue(!model.uiState.value.refreshing)

        model.reload()

        assertEquals(listOf(4L, 5L, 6L), model.uiState.value.gridIds())
        assertTrue(!model.uiState.value.refreshing)
        assertEquals(3, likedCalls)
    }

    @Test
    fun `a like commit on another filter issues no request`() = runTest {
        val http = routedHttp()
        val model = loaded(http)

        model.onLikeCommitted()

        assertEquals(emptyList<String>(), http.likedPages)
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
    private class RoutedHttp {
        val libraryPages = mutableListOf<String>()
        val likedPages = mutableListOf<String>()

        /** `"genreId:page"` per request, so the path and the cursor assert together. */
        val genrePages = mutableListOf<String>()
        val perPages = mutableListOf<String>()
        val sorts = mutableListOf<String>()
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
        genres: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(moviesGenresJson()) },
        library: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(moviesLibraryJson()) },
        liked: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(moviesLibraryJson()) },
        genreMovies: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(moviesLibraryJson()) },
    ): RoutedHttp {
        val routed = RoutedHttp()
        fun recordListParams(request: HttpRequestData) {
            routed.perPages += request.url.parameters["per_page"].orEmpty()
            routed.sorts += request.url.parameters["sort"].orEmpty()
        }
        routed.test = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
            val path = request.url.encodedPath
            val genreId = GENRE_MOVIES_PATH.matchEntire(path)?.groupValues?.get(1)
            when {
                path == "/api/movies/stats" -> stats(request)
                path == "/api/movies/genres" -> genres(request)
                path == "/api/movies/library" -> {
                    routed.libraryPages += request.page()
                    recordListParams(request)
                    library(request)
                }
                path == "/api/movies/liked" -> {
                    routed.likedPages += request.page()
                    recordListParams(request)
                    liked(request)
                }
                genreId != null -> {
                    routed.genrePages += "$genreId:${request.page()}"
                    recordListParams(request)
                    genreMovies(request)
                }
                else -> error("unexpected request to $path")
            }
        }
        return routed
    }

    private companion object {
        const val ERROR_BODY = """{"error":true,"message":"nope"}"""
        val GENRE_MOVIES_PATH = Regex("/api/movies/genres/(\\d+)/movies")
    }
}
