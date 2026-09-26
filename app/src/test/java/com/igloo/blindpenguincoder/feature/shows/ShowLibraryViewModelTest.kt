package com.igloo.blindpenguincoder.feature.shows

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.repository.TEST_SERVER
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.showGenreWithCountJson
import com.igloo.blindpenguincoder.data.repository.showLibraryItemJson
import com.igloo.blindpenguincoder.data.repository.showsGenresJson
import com.igloo.blindpenguincoder.data.repository.showsLibraryJson
import com.igloo.blindpenguincoder.data.repository.showsStatsJson
import com.igloo.blindpenguincoder.feature.library.LibraryFilter
import com.igloo.blindpenguincoder.feature.library.LibraryGenre
import com.igloo.blindpenguincoder.feature.library.LibraryKind
import com.igloo.blindpenguincoder.feature.library.LibraryTab
import com.igloo.blindpenguincoder.feature.library.LibraryUiState
import com.igloo.blindpenguincoder.feature.library.LibraryViewModel
import com.igloo.blindpenguincoder.feature.library.actions
import com.igloo.blindpenguincoder.feature.shared.PosterItem
import com.igloo.blindpenguincoder.feature.shared.TAB_SWITCH_DEBOUNCE_MS
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * What the TV Shows pane does differently over the shared paging machine: the show routes, the
 * show wire fields, and a strip with no Liked tab. The machine itself is pinned once, over the
 * movie routes, in `LibraryViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowLibraryViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the first load pages the show library at the contract's size and reads the show count`() = runTest {
        val http = routedHttp(
            stats = { jsonResponse(showsStatsJson(totalShows = 3)) },
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L, total = 3)) },
        )

        val state = loaded(http).uiState.value

        assertEquals(LibraryKind.Shows, state.kind)
        assertEquals(listOf(1L, 2L, 3L), state.gridIds())
        assertEquals(3L, state.total)
        assertEquals(listOf("/api/shows/stats", "/api/shows/genres", "/api/shows/library"), http.paths)
        assertEquals(listOf("48"), http.perPages)
        assertEquals(listOf("asc"), http.sorts)
    }

    @Test
    fun `a row renders the show's name, premiere year and poster`() = runTest {
        val http = routedHttp(
            library = {
                jsonResponse(
                    showsLibraryJson(
                        shows = arrayOf(
                            showLibraryItemJson(
                                id = 40,
                                name = "Severance",
                                posterPath = "/severance.jpg",
                                premiereYear = 2022,
                            ),
                        ),
                    ),
                )
            },
        )

        val items = (loaded(http).uiState.value.grid as IglooRailState.Loaded).items

        assertEquals(
            listOf(
                PosterItem(
                    id = 40,
                    title = "Severance",
                    year = 2022,
                    posterUrl = "$TEST_SERVER/tmdb/images/w500/severance.jpg",
                ),
            ),
            items,
        )
    }

    @Test
    fun `the strip offers all shows and genres only`() = runTest {
        val model = loaded(routedHttp())

        assertEquals(listOf(LibraryTab.All, LibraryTab.Genres), model.uiState.value.tabs)
    }

    /** The backend keeps no show likes, so no route exists to request; the tab is never drawn. */
    @Test
    fun `the liked tab can be neither selected nor pressed`() = runTest {
        val http = routedHttp()
        val model = loaded(http)
        val requestsBefore = http.paths.size

        model.selectTab(LibraryTab.Liked)
        advanceTimeBy(TAB_SWITCH_DEBOUNCE_MS + 1)
        model.pressTab(LibraryTab.Liked)

        assertEquals(LibraryTab.All, model.uiState.value.tab)
        assertEquals(requestsBefore, http.paths.size)
    }

    @Test
    fun `genres carry the show count and the genres tab pages that genre's shows`() = runTest {
        val http = routedHttp(
            genres = {
                jsonResponse(
                    showsGenresJson(
                        showGenreWithCountJson(id = 7, tag = "Comedy", showCount = 2),
                        showGenreWithCountJson(id = 9, tag = "Drama", showCount = 1),
                    ),
                )
            },
        )
        val model = loaded(http)
        assertEquals(
            listOf(LibraryGenre(7, "Comedy", 2), LibraryGenre(9, "Drama", 1)),
            model.uiState.value.genres,
        )

        landOn(model, LibraryTab.Genres)

        assertEquals(LibraryFilter.Genre(id = 7, tag = "Comedy"), model.uiState.value.genre)
        assertEquals(listOf("7:1"), http.genrePages)
    }

    @Test
    fun `toggling sort re-reads page one descending`() = runTest {
        val http = routedHttp()
        val model = loaded(http)

        model.toggleSort()

        assertEquals(listOf("asc", "desc"), http.sorts)
    }

    @Test
    fun `a stats failure leaves the page's own total standing`() = runTest {
        val http = routedHttp(
            stats = { jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) },
            library = { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L, total = 3)) },
        )

        assertEquals(3L, loaded(http).uiState.value.total)
    }

    /** The like reconcile is wired for the movie library; here it has no Liked grid to reconcile. */
    @Test
    fun `a like commit issues no request`() = runTest {
        val http = routedHttp()
        val model = loaded(http)
        val requestsBefore = http.paths.size

        model.onLikeCommitted()

        assertEquals(requestsBefore, http.paths.size)
    }

    /** The host wires the screen through `actions()`; a lambda bound to the wrong method would pass every other suite. */
    @Test
    fun `the screen's actions are bound to the view model's methods`() = runTest {
        val http = routedHttp()
        val model = loaded(http)
        val actions = model.actions()

        actions.onToggleSort()
        assertEquals("desc", http.sorts.last())

        actions.onSelectGenre(LibraryFilter.Genre(id = 9, tag = "Drama"))
        assertEquals(listOf("9:1"), http.genrePages)
        assertEquals(LibraryTab.Genres, model.uiState.value.tab)

        actions.onPressTab(LibraryTab.All)
        assertEquals(LibraryTab.All, model.uiState.value.tab)
        assertEquals(3, http.libraryPages.size)

        actions.onRefresh()
        assertEquals(2, http.paths.count { it == "/api/shows/stats" })
    }

    // --- harness ----------------------------------------------------------------------------

    private fun loaded(http: RoutedHttp) =
        LibraryViewModel(showLibrarySource(http.test.showRepository), http.test.serverUrl)
            .also { it.refresh() }

    private fun TestScope.landOn(model: LibraryViewModel, tab: LibraryTab) {
        model.selectTab(tab)
        advanceTimeBy(TAB_SWITCH_DEBOUNCE_MS + 1)
    }

    private fun LibraryUiState.gridIds(): List<Long> =
        (grid as IglooRailState.Loaded).items.map { it.id }

    private fun HttpRequestData.page(): String = url.parameters["page"].orEmpty()

    private fun page(
        number: Long,
        totalPages: Long,
        ids: LongRange,
        total: Long = 96,
    ): String = showsLibraryJson(
        page = number,
        total = total,
        totalPages = totalPages,
        shows = ids.map { showLibraryItemJson(id = it, name = "Show $it") }.toTypedArray(),
    )

    /** Records what the pane actually asked the backend for, so the routes can be asserted. */
    private class RoutedHttp {
        val paths = mutableListOf<String>()
        val libraryPages = mutableListOf<String>()

        /** `"genreId:page"` per request, so the path and the cursor assert together. */
        val genrePages = mutableListOf<String>()
        val perPages = mutableListOf<String>()
        val sorts = mutableListOf<String>()
        lateinit var test: TestHttp
    }

    private fun TestScope.routedHttp(
        stats: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(showsStatsJson()) },
        genres: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(showsGenresJson(showGenreWithCountJson())) },
        library: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..3L)) },
        genreShows: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(page(number = 1, totalPages = 1, ids = 1L..2L)) },
    ): RoutedHttp {
        val routed = RoutedHttp()
        fun recordListParams(request: HttpRequestData) {
            routed.perPages += request.url.parameters["per_page"].orEmpty()
            routed.sorts += request.url.parameters["sort"].orEmpty()
        }
        routed.test = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
            val path = request.url.encodedPath
            routed.paths += path
            val genreId = GENRE_SHOWS_PATH.matchEntire(path)?.groupValues?.get(1)
            when {
                path == "/api/shows/stats" -> stats(request)
                path == "/api/shows/genres" -> genres(request)
                path == "/api/shows/library" -> {
                    routed.libraryPages += request.page()
                    recordListParams(request)
                    library(request)
                }
                genreId != null -> {
                    routed.genrePages += "$genreId:${request.page()}"
                    recordListParams(request)
                    genreShows(request)
                }
                else -> error("unexpected request to $path")
            }
        }
        return routed
    }

    private companion object {
        const val ERROR_BODY = """{"error":true,"message":"nope"}"""
        val GENRE_SHOWS_PATH = Regex("/api/shows/genres/(\\d+)/shows")
    }
}
