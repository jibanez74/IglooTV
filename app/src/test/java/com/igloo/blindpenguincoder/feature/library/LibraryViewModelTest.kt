package com.igloo.blindpenguincoder.feature.library

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.movieGenreWithCountJson
import com.igloo.blindpenguincoder.data.repository.movieLibraryItemJson
import com.igloo.blindpenguincoder.data.repository.moviesGenresJson
import com.igloo.blindpenguincoder.data.repository.moviesLibraryJson
import com.igloo.blindpenguincoder.data.repository.moviesStatsJson
import com.igloo.blindpenguincoder.feature.movies.movieLibrarySource
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.feature.shared.TAB_SWITCH_DEBOUNCE_MS
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
class LibraryViewModelTest {

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
        assertEquals(96L, model.uiState.value.total)
        assertEquals(AppendState.Idle, model.uiState.value.append)
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
        assertEquals(AppendState.End, state.append)
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
        assertEquals(AppendState.Idle, model.uiState.value.append)
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

        assertEquals(97L, model.uiState.value.total)
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
        assertEquals(AppendState.End, model.uiState.value.append)
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

        assertEquals(AppendState.End, model.uiState.value.append)
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
    fun `a fully overlapping append advances its generation only after the backoff`() = runTest {
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

        // The page landed all-duplicates, so the walk holds: an immediate generation bump
        // would re-arm the prefetch effect and chase every remaining page at line rate.
        assertEquals(generationBefore, model.uiState.value.appendGeneration)
        assertEquals(AppendState.Loading, model.uiState.value.append)

        advanceUntilIdle()

        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
        assertEquals(generationBefore + 1, model.uiState.value.appendGeneration)
        assertEquals(AppendState.Idle, model.uiState.value.append)
    }

    @Test
    fun `a failed append keeps the loaded items and offers a retry tail`() = runTest {
        val http = routedHttp(library = failingAfterFirstPage())
        val model = loaded(http)

        model.loadMore()

        assertEquals((1L..48L).toList(), model.uiState.value.gridIds())
        assertTrue(model.uiState.value.append is AppendState.Error)
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
        assertEquals(AppendState.End, model.uiState.value.append)
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
        assertEquals(AppendState.Loading, model.uiState.value.append)
        model.reload()

        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
        assertEquals(AppendState.Idle, model.uiState.value.append)
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

    /** The address clears only mid-teardown; a page caught in that window must not crash. */
    @Test
    fun `a page landing after the server address clears is dropped without crashing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var gated = false
        val http = routedHttp(
            library = {
                if (gated) gate.await()
                jsonResponse(page(number = 1, totalPages = 2, ids = 1L..3L))
            },
        )
        val model = loaded(http)

        gated = true
        model.reload()
        http.test.serverUrl.set(null)
        gate.complete(Unit)

        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
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

        assertEquals(73L, state.total)
        assertEquals(listOf(1L, 2L, 3L), state.gridIds())
    }

    @Test
    fun `a stale stats response cannot overwrite a newer one`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var statsCalls = 0
        val http = routedHttp(
            stats = {
                statsCalls += 1
                if (statsCalls == 1) {
                    gate.await()
                    jsonResponse(moviesStatsJson(totalMovies = 42))
                } else {
                    jsonResponse(moviesStatsJson(totalMovies = 96))
                }
            },
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        )
        // The start effect re-fires on every return to the foreground, so two stats reads can
        // overlap; the older response resuming last must not win.
        val model = loaded(http)

        model.refresh()
        gate.complete(Unit)

        assertEquals(96L, model.uiState.value.total)
    }

    @Test
    fun `a stale genres response cannot overwrite a newer list`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var genresCalls = 0
        val http = routedHttp(
            genres = {
                genresCalls += 1
                if (genresCalls == 1) {
                    gate.await()
                    jsonResponse(moviesGenresJson(movieGenreWithCountJson(id = 1, tag = "Stale")))
                } else {
                    jsonResponse(moviesGenresJson(movieGenreWithCountJson(id = 2, tag = "Fresh")))
                }
            },
        )
        val model = loaded(http)

        model.refresh()
        gate.complete(Unit)

        assertEquals("Fresh", model.uiState.value.genres.single().tag)
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

        assertEquals(96L, model.uiState.value.total)
    }

    // --- filters and sort -------------------------------------------------------------------

    @Test
    fun `selecting the liked tab requests page one of the liked endpoint`() = runTest {
        val http = routedHttp(
            liked = { jsonResponse(page(number = 1, total = 3, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)

        landOn(model, LibraryTab.Liked)

        assertEquals(listOf("1"), http.likedPages)
        assertEquals(LibraryFilter.Liked, model.uiState.value.filter)
        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
        assertEquals(3L, model.uiState.value.total)
    }

    @Test
    fun `selecting a genre requests that genre's movies at the contract's page size`() = runTest {
        val http = routedHttp(
            genreMovies = { jsonResponse(page(number = 1, total = 26, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)

        model.selectGenre(LibraryFilter.Genre(id = 7, tag = "Action"))

        assertEquals(listOf("7:1"), http.genrePages)
        assertEquals(listOf("48", "48"), http.perPages)
        assertEquals(26L, model.uiState.value.total)
        assertEquals(LibraryTab.Genres, model.uiState.value.tab)
    }

    @Test
    fun `selecting the genres tab picks the first genre and requests its movies`() = runTest {
        val http = routedHttp(
            genres = {
                jsonResponse(
                    moviesGenresJson(
                        movieGenreWithCountJson(id = 7, tag = "Action"),
                        movieGenreWithCountJson(id = 9, tag = "Drama"),
                    ),
                )
            },
            genreMovies = { jsonResponse(page(number = 1, total = 26, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)

        landOn(model, LibraryTab.Genres)

        assertEquals(listOf("7:1"), http.genrePages)
        assertEquals(LibraryFilter.Genre(id = 7, tag = "Action"), model.uiState.value.filter)
    }

    @Test
    fun `selecting the genres tab reuses the remembered genre while it is still listed`() = runTest {
        val http = routedHttp(
            genres = {
                jsonResponse(
                    moviesGenresJson(
                        movieGenreWithCountJson(id = 7, tag = "Action"),
                        movieGenreWithCountJson(id = 9, tag = "Drama"),
                    ),
                )
            },
            genreMovies = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)
        model.selectGenre(LibraryFilter.Genre(id = 9, tag = "Drama"))

        landOn(model, LibraryTab.All)
        landOn(model, LibraryTab.Genres)

        assertEquals(listOf("9:1", "9:1"), http.genrePages)
    }

    @Test
    fun `a renamed genre keeps its selection and takes the list's new tag`() = runTest {
        var renamed = false
        val http = routedHttp(
            genres = {
                val tag = if (renamed) "Action & Adventure" else "Action"
                jsonResponse(moviesGenresJson(movieGenreWithCountJson(id = 7, tag = tag)))
            },
            genreMovies = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)
        landOn(model, LibraryTab.Genres)

        renamed = true
        model.reload()

        assertEquals(LibraryFilter.Genre(id = 7, tag = "Action & Adventure"), model.uiState.value.genre)
        assertEquals(listOf("7:1", "7:1"), http.genrePages)
    }

    @Test
    fun `a genre disappearing during refresh selects and pages the first remaining genre`() =
        runTest {
            var refreshed = false
            val http = routedHttp(
                genres = {
                    if (refreshed) {
                        jsonResponse(
                            moviesGenresJson(
                                movieGenreWithCountJson(id = 9, tag = "Drama"),
                                movieGenreWithCountJson(id = 11, tag = "Comedy"),
                            ),
                        )
                    } else {
                        jsonResponse(
                            moviesGenresJson(
                                movieGenreWithCountJson(id = 7, tag = "Action"),
                                movieGenreWithCountJson(id = 9, tag = "Drama"),
                            ),
                        )
                    }
                },
                genreMovies = {
                    val genreId = GENRE_MOVIES_PATH
                        .matchEntire(it.url.encodedPath)
                        ?.groupValues
                        ?.get(1)
                    when (genreId to it.page()) {
                        "7" to "1" -> jsonResponse(
                            page(number = 1, total = 3, totalPages = 1, ids = 1L..3L),
                        )
                        "9" to "1" -> jsonResponse(
                            page(number = 1, total = 6, totalPages = 2, ids = 10L..12L),
                        )
                        else -> jsonResponse(
                            page(number = 2, total = 6, totalPages = 2, ids = 13L..15L),
                        )
                    }
                },
            )
            val model = loaded(http)
            model.selectGenre(LibraryFilter.Genre(id = 7, tag = "Action"))

            refreshed = true
            model.refresh()

            assertEquals(LibraryFilter.Genre(id = 9, tag = "Drama"), model.uiState.value.genre)
            assertEquals(listOf(10L, 11L, 12L), model.uiState.value.gridIds())
            assertEquals(6L, model.uiState.value.total)
            assertEquals(listOf("7:1", "9:1"), http.genrePages)
            assertTrue(!model.uiState.value.refreshing)

            model.loadMore()

            assertEquals(listOf("7:1", "9:1", "9:2"), http.genrePages)
            assertEquals((10L..15L).toList(), model.uiState.value.gridIds())
        }

    @Test
    fun `a successful empty genre refresh clears selection and retains the hidden grid`() =
        runTest {
            var empty = false
            val http = routedHttp(
                genres = {
                    if (empty) {
                        jsonResponse(moviesGenresJson())
                    } else {
                        jsonResponse(
                            moviesGenresJson(movieGenreWithCountJson(id = 7, tag = "Action")),
                        )
                    }
                },
                library = {
                    jsonResponse(page(number = 1, totalPages = 1, ids = 20L..22L))
                },
                genreMovies = {
                    jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L))
                },
            )
            val model = loaded(http)
            model.selectGenre(LibraryFilter.Genre(id = 7, tag = "Action"))

            empty = true
            model.refresh()

            assertTrue(model.uiState.value.genres.isEmpty())
            assertNull(model.uiState.value.genre)
            assertNull(model.uiState.value.filter)
            assertEquals(LibraryContent.NoGenres, model.uiState.value.toLibraryContent())
            assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
            assertEquals(listOf("7:1"), http.genrePages)

            landOn(model, LibraryTab.All)
            landOn(model, LibraryTab.Genres)

            assertNull(model.uiState.value.genre)
            assertNull(model.uiState.value.filter)
            assertEquals(LibraryContent.NoGenres, model.uiState.value.toLibraryContent())
            assertEquals(listOf("7:1"), http.genrePages)
        }

    @Test
    fun `selecting the genres tab with no genres issues no request and shows the placeholder`() =
        runTest {
            val http = routedHttp(
                genres = { jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) },
                library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
            )
            val model = loaded(http)

            landOn(model, LibraryTab.Genres)

            assertEquals(emptyList<String>(), http.genrePages)
            assertEquals(LibraryTab.Genres, model.uiState.value.tab)
            assertNull(model.uiState.value.filter)
            assertTrue(!model.uiState.value.refreshing)
            // The committed list is untouched underneath the placeholder.
            assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
        }

    @Test
    fun `genres landing on the waiting genres tab auto-select the first and fetch it`() = runTest {
        var fail = true
        val http = routedHttp(
            genres = {
                if (fail) jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
                else jsonResponse(moviesGenresJson(movieGenreWithCountJson(id = 7, tag = "Action")))
            },
            genreMovies = { jsonResponse(page(number = 1, total = 26, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)
        landOn(model, LibraryTab.Genres)
        val generationBefore = model.uiState.value.contentGeneration

        fail = false
        model.reload()

        assertEquals(listOf("7:1"), http.genrePages)
        assertEquals(LibraryFilter.Genre(id = 7, tag = "Action"), model.uiState.value.filter)
        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
        assertEquals(generationBefore + 1, model.uiState.value.contentGeneration)
        assertTrue(!model.uiState.value.refreshing)
    }

    /**
     * An empty genre list cannot tell a request still in flight from a library with no genres,
     * and only one of those is a failure the user should be told about.
     */
    @Test
    fun `the genres tab waits on the list rather than calling it unavailable`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val http = routedHttp(
            genres = {
                gate.await()
                jsonResponse(moviesGenresJson(movieGenreWithCountJson(id = 7, tag = "Action")))
            },
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
            genreMovies = { jsonResponse(page(number = 1, total = 26, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)

        landOn(model, LibraryTab.Genres)

        assertTrue(!model.uiState.value.genresLoaded)
        assertEquals(LibraryContent.GenresLoading, model.uiState.value.toLibraryContent())
        assertEquals(emptyList<String>(), http.genrePages)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(LibraryFilter.Genre(id = 7, tag = "Action"), model.uiState.value.filter)
    }

    /** A failed read settles it too: the wait is over, whatever the answer turned out to be. */
    @Test
    fun `a failed genres read settles the tab into the placeholder`() = runTest {
        val http = routedHttp(
            genres = { jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) },
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)

        landOn(model, LibraryTab.Genres)

        assertTrue(model.uiState.value.genresLoaded)
        assertEquals(LibraryContent.NoGenres, model.uiState.value.toLibraryContent())
    }

    /** The placeholder tells the user to refresh, so the press has to visibly do something. */
    @Test
    fun `a refresh from the genres placeholder reports that it is working`() = runTest {
        var genreListCalls = 0
        val gate = CompletableDeferred<Unit>()
        val http = routedHttp(
            genres = {
                genreListCalls += 1
                if (genreListCalls == 1) {
                    jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
                } else {
                    gate.await()
                    jsonResponse(moviesGenresJson(movieGenreWithCountJson(id = 7, tag = "Action")))
                }
            },
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
            genreMovies = { jsonResponse(page(number = 1, total = 26, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)
        landOn(model, LibraryTab.Genres)
        assertEquals(LibraryContent.NoGenres, model.uiState.value.toLibraryContent())

        model.reload()

        // Page one has nothing to request here, so the genres round trip carries the label.
        assertTrue(model.uiState.value.refreshing)

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(!model.uiState.value.refreshing)
        assertEquals(LibraryFilter.Genre(id = 7, tag = "Action"), model.uiState.value.filter)
    }

    @Test
    fun `a refresh that still cannot reach the genres stops reporting`() = runTest {
        val http = routedHttp(
            genres = { jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) },
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)
        landOn(model, LibraryTab.Genres)

        model.reload()
        advanceUntilIdle()

        assertTrue(!model.uiState.value.refreshing)
        assertEquals(LibraryContent.NoGenres, model.uiState.value.toLibraryContent())
    }

    /** Flipping the label with no list to sort would leave the header claiming a hidden order. */
    @Test
    fun `sorting from the genres placeholder changes nothing`() = runTest {
        val http = routedHttp(
            genres = { jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) },
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)
        landOn(model, LibraryTab.Genres)
        val requestsBefore = http.libraryPages.size

        model.toggleSort()

        assertEquals(SortOrder.Ascending, model.uiState.value.sort)
        assertEquals(requestsBefore, http.libraryPages.size)
    }

    /**
     * The endpoint-less tab still supersedes: a page already on the wire belongs to the list the
     * user just left, and letting its failure land would snap the tab back out from under them.
     */
    @Test
    fun `a page in flight cannot revert the tab that left it behind`() = runTest {
        var libraryCalls = 0
        val gate = CompletableDeferred<Unit>()
        val http = routedHttp(
            genres = { jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) },
            library = {
                libraryCalls += 1
                if (libraryCalls == 1) {
                    jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L))
                } else {
                    gate.await()
                    jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
                }
            },
        )
        val model = loaded(http)
        model.reload()
        val generationBefore = model.uiState.value.contentGeneration

        landOn(model, LibraryTab.Genres)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(LibraryTab.Genres, model.uiState.value.tab)
        assertEquals(LibraryContent.NoGenres, model.uiState.value.toLibraryContent())
        assertNull(model.uiState.value.notice)
        assertEquals(generationBefore, model.uiState.value.contentGeneration)
        assertTrue(!model.uiState.value.refreshing)
    }

    /**
     * The screen leaves focus on a tab whose switch failed *because* no generation moved. A
     * regression that bumped one here would pass every other test and yank focus on device.
     */
    @Test
    fun `a failed tab switch leaves the focus generations untouched`() = runTest {
        val http = routedHttp(
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
            liked = { jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) },
        )
        val model = loaded(http)
        val before = model.uiState.value

        landOn(model, LibraryTab.Liked)

        assertEquals(before.contentGeneration, model.uiState.value.contentGeneration)
        assertEquals(
            before.silentReconcileGeneration,
            model.uiState.value.silentReconcileGeneration,
        )
    }

    @Test
    fun `a failed tab switch leaves the append cursor on the committed list`() = runTest {
        val http = routedHttp(
            library = {
                val requested = it.page().toLong()
                jsonResponse(
                    page(
                        number = requested,
                        totalPages = 3,
                        ids = (requested * 48 - 47)..(requested * 48),
                    ),
                )
            },
            liked = { jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) },
        )
        val model = loaded(http)
        model.loadMore()
        assertEquals(listOf("1", "2"), http.libraryPages)

        landOn(model, LibraryTab.Liked)
        model.loadMore()

        assertEquals(listOf("1", "2", "3"), http.libraryPages)
        assertEquals(LibraryTab.All, model.uiState.value.tab)
    }

    /** Every lifecycle START re-reads the genres; an unchanged list must not re-page the grid. */
    @Test
    fun `a genres list landing unchanged does not re-request the page`() = runTest {
        val http = routedHttp(
            genres = {
                jsonResponse(moviesGenresJson(movieGenreWithCountJson(id = 7, tag = "Action")))
            },
            genreMovies = { jsonResponse(page(number = 1, total = 26, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)
        landOn(model, LibraryTab.Genres)
        assertEquals(listOf("7:1"), http.genrePages)

        model.refresh()
        advanceUntilIdle()

        assertEquals(listOf("7:1"), http.genrePages)
    }

    /**
     * The revert restores the list the grid is still in — but not a genre the refreshed list has
     * since dropped, which would strand the tab on a genre with no chip left to change it.
     */
    @Test
    fun `a failed page whose genre the list has dropped falls back to the placeholder`() = runTest {
        var listsAction = true
        var failTheGenrePage = false
        val gate = CompletableDeferred<Unit>()
        val http = routedHttp(
            genres = {
                if (listsAction) {
                    jsonResponse(moviesGenresJson(movieGenreWithCountJson(id = 7, tag = "Action")))
                } else {
                    jsonResponse(moviesGenresJson())
                }
            },
            genreMovies = {
                if (failTheGenrePage) {
                    gate.await()
                    jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
                } else {
                    jsonResponse(page(number = 1, total = 26, totalPages = 1, ids = 1L..3L))
                }
            },
        )
        val model = loaded(http)
        landOn(model, LibraryTab.Genres)
        assertEquals(LibraryFilter.Genre(id = 7, tag = "Action"), model.uiState.value.filter)

        // The refreshed list is authoritatively empty, and it lands first — the page request it
        // went out with fails afterwards.
        listsAction = false
        failTheGenrePage = true
        model.reload()
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(model.uiState.value.genre)
        assertEquals(LibraryContent.NoGenres, model.uiState.value.toLibraryContent())
    }

    @Test
    fun `a tab passed over on the way to another never reaches the wire`() = runTest {
        val http = routedHttp(
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
            genreMovies = { jsonResponse(page(number = 1, totalPages = 1, ids = 10L..12L)) },
            liked = { jsonResponse(page(number = 1, totalPages = 1, ids = 20L..22L)) },
        )
        val model = loaded(http)
        landOn(model, LibraryTab.Liked)

        // D-pad from Liked back to All passes over Genres, which selects on focus. The highlight
        // moves through it; the request never does, because All arrives inside the debounce.
        model.selectTab(LibraryTab.Genres)
        model.selectTab(LibraryTab.All)
        advanceUntilIdle()

        assertEquals(LibraryTab.All, model.uiState.value.tab)
        assertEquals(emptyList<String>(), http.genrePages)
        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
        assertTrue(!model.uiState.value.refreshing)
    }

    /** The pass-over's chrome must stay put too: the label belongs to the request, not the focus. */
    @Test
    fun `a tab passed over never raises the refreshing label`() = runTest {
        val http = routedHttp(
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
            liked = { jsonResponse(page(number = 1, totalPages = 1, ids = 20L..22L)) },
        )
        val model = loaded(http)

        model.selectTab(LibraryTab.Liked)

        assertEquals(LibraryTab.Liked, model.uiState.value.tab)
        assertTrue(!model.uiState.value.refreshing)
        assertEquals(emptyList<String>(), http.likedPages)
    }

    @Test
    fun `focus selecting a tab waits for the debounce before requesting it`() = runTest {
        val http = routedHttp(
            liked = { jsonResponse(page(number = 1, totalPages = 1, ids = 20L..22L)) },
        )
        val model = loaded(http)

        model.selectTab(LibraryTab.Liked)
        advanceTimeBy(TAB_SWITCH_DEBOUNCE_MS - 1)

        assertEquals(emptyList<String>(), http.likedPages)

        advanceTimeBy(2)

        assertEquals(listOf("1"), http.likedPages)
    }

    @Test
    fun `pressing the pending tab requests immediately without a delayed duplicate`() = runTest {
        val http = routedHttp(
            liked = { jsonResponse(page(number = 1, totalPages = 1, ids = 20L..22L)) },
        )
        val model = loaded(http)

        model.selectTab(LibraryTab.Liked)
        model.pressTab(LibraryTab.Liked)

        assertEquals(listOf("1"), http.likedPages)

        advanceTimeBy(TAB_SWITCH_DEBOUNCE_MS + 1)

        assertEquals(listOf("1"), http.likedPages)
    }

    /** A press is deliberate, so it skips the wait — and still supersedes what was in flight. */
    @Test
    fun `pressing a tab fetches at once and cancels the switch it interrupts`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val http = routedHttp(
            genres = {
                jsonResponse(moviesGenresJson(movieGenreWithCountJson(id = 7, tag = "Action")))
            },
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
            genreMovies = {
                gate.await()
                jsonResponse(page(number = 1, totalPages = 1, ids = 10L..12L))
            },
            liked = { jsonResponse(page(number = 1, totalPages = 1, ids = 20L..22L)) },
        )
        val model = loaded(http)
        model.pressTab(LibraryTab.Genres)
        assertEquals(listOf("7:1"), http.genrePages)

        model.pressTab(LibraryTab.Liked)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(LibraryTab.Liked, model.uiState.value.tab)
        assertEquals(listOf(20L, 21L, 22L), model.uiState.value.gridIds())
        assertTrue(!model.uiState.value.refreshing)
    }

    @Test
    fun `toggling sort re-requests page one of the current filter in the other direction`() = runTest {
        val http = routedHttp(
            genreMovies = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)
        model.selectGenre(LibraryFilter.Genre(id = 7, tag = "Action"))

        model.toggleSort()

        // The flip stays on the genre endpoint; the library is not re-read.
        assertEquals(listOf("7:1", "7:1"), http.genrePages)
        assertEquals(listOf("1"), http.libraryPages)
        assertEquals(listOf("asc", "asc", "desc"), http.sorts)
        assertEquals(SortOrder.Descending, model.uiState.value.sort)
    }

    @Test
    fun `a failed tab switch keeps the grid, reverts the tab, and reports a notice`() = runTest {
        val http = routedHttp(
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
            liked = { jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) },
        )
        val model = loaded(http)

        landOn(model, LibraryTab.Liked)

        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.gridIds())
        assertEquals(LibraryTab.All, model.uiState.value.tab)
        assertEquals(LibraryFilter.All, model.uiState.value.filter)
        assertTrue(model.uiState.value.notice != null)
    }

    @Test
    fun `a failed genre switch reverts to the committed genre`() = runTest {
        var failingGenre: Long? = null
        val http = routedHttp(
            genres = {
                jsonResponse(
                    moviesGenresJson(
                        movieGenreWithCountJson(id = 7, tag = "Action"),
                        movieGenreWithCountJson(id = 9, tag = "Drama"),
                    ),
                )
            },
            genreMovies = {
                if (it.url.encodedPath.contains("/genres/$failingGenre/")) {
                    jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
                } else {
                    jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L))
                }
            },
        )
        val model = loaded(http)
        model.selectGenre(LibraryFilter.Genre(id = 7, tag = "Action"))

        failingGenre = 9
        model.selectGenre(LibraryFilter.Genre(id = 9, tag = "Drama"))

        assertEquals(LibraryFilter.Genre(id = 7, tag = "Action"), model.uiState.value.genre)
        assertEquals(LibraryTab.Genres, model.uiState.value.tab)
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

        landOn(model, LibraryTab.Liked)
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
        landOn(model, LibraryTab.Liked)

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
        model.selectGenre(LibraryFilter.Genre(id = 7, tag = "Action"))

        // The start effect re-fires on every return to the foreground and re-reads stats.
        model.refresh()

        assertEquals(26L, model.uiState.value.total)
    }

    @Test
    fun `re-selecting the current tab is a no-op`() = runTest {
        val http = routedHttp(
            liked = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)
        landOn(model, LibraryTab.Liked)

        landOn(model, LibraryTab.Liked)

        assertEquals(listOf("1"), http.likedPages)
    }

    @Test
    fun `pressing the committed tab is a no-op`() = runTest {
        val http = routedHttp(
            liked = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)
        landOn(model, LibraryTab.Liked)

        model.pressTab(LibraryTab.Liked)

        assertEquals(listOf("1"), http.likedPages)
    }

    @Test
    fun `re-pressing the selected genre chip is a no-op`() = runTest {
        val http = routedHttp(
            genreMovies = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        )
        val model = loaded(http)
        model.selectGenre(LibraryFilter.Genre(id = 7, tag = "Action"))

        model.selectGenre(LibraryFilter.Genre(id = 7, tag = "Action"))

        assertEquals(listOf("7:1"), http.genrePages)
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

        assertEquals("Action", model.uiState.value.genres.single().tag)
    }

    // --- liked reconcile --------------------------------------------------------------------

    @Test
    fun `a like commit on the liked tab re-reads page one silently`() = runTest {
        var unliked = false
        val http = routedHttp(
            liked = {
                if (unliked) jsonResponse(page(number = 1, total = 2, totalPages = 1, ids = 1L..2L))
                else jsonResponse(page(number = 1, total = 3, totalPages = 1, ids = 1L..3L))
            },
        )
        val model = loaded(http)
        landOn(model, LibraryTab.Liked)
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
        landOn(model, LibraryTab.Liked)
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
            landOn(model, LibraryTab.Liked)

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
            landOn(model, LibraryTab.Liked)
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

        landOn(model, LibraryTab.Liked)
        assertTrue(model.uiState.value.refreshing)
        model.onLikeCommitted()
        assertTrue(!model.uiState.value.refreshing)
        reconcileGate.complete(Unit)

        assertEquals(LibraryFilter.Liked, model.uiState.value.filter)
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
        landOn(model, LibraryTab.Liked)
        model.onLikeCommitted()
        assertTrue(!model.uiState.value.refreshing)

        model.reload()

        assertEquals(listOf(4L, 5L, 6L), model.uiState.value.gridIds())
        assertTrue(!model.uiState.value.refreshing)
        assertEquals(3, likedCalls)
    }

    @Test
    fun `a like commit on another tab issues no request`() = runTest {
        val http = routedHttp()
        val model = loaded(http)

        model.onLikeCommitted()

        assertEquals(emptyList<String>(), http.likedPages)
    }

    // --- harness ----------------------------------------------------------------------------

    /** The host's start effect is what fires the first load; there is no fetch in `init`. */
    private fun loaded(http: RoutedHttp) =
        LibraryViewModel(movieLibrarySource(http.test.movieRepository), http.test.serverUrl)
            .also { it.refresh() }

    /**
     * A tab taking focus and being stayed on: the switch, plus the debounce it waits out. Tests
     * about the debounce itself call `selectTab` and drive the clock themselves.
     */
    private fun TestScope.landOn(model: LibraryViewModel, tab: LibraryTab) {
        model.selectTab(tab)
        // Past the debounce and no further: `advanceUntilIdle` here would run the virtual clock
        // into the client's request timeout in the tests that deliberately hold a response open.
        advanceTimeBy(TAB_SWITCH_DEBOUNCE_MS + 1)
    }

    private fun LibraryUiState.gridIds(): List<Long> =
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
