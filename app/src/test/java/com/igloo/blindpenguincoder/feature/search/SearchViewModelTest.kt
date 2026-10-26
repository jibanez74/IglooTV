package com.igloo.blindpenguincoder.feature.search

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.movieLibraryItemJson
import com.igloo.blindpenguincoder.data.repository.searchAllJson
import com.igloo.blindpenguincoder.data.repository.searchPageJson
import com.igloo.blindpenguincoder.data.repository.searchSectionJson
import com.igloo.blindpenguincoder.data.repository.simpleAlbumJson
import com.igloo.blindpenguincoder.data.repository.trackListItemJson
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.feature.shared.PagedState
import com.igloo.blindpenguincoder.feature.shared.TAB_SWITCH_DEBOUNCE_MS
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Search pane's machine: a blank query sends nothing, typing is one request per settled
 * word, the keyboard's Search action skips the wait without repeating an answered query, a new
 * query forgets every tab and drops late answers, tabs load lazily on the Music pane's rules,
 * category tabs page, and a row's Play queues the rows shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Every request the view model put on the wire, as `path?q=…&page=…`. */
    private class RoutedHttp {
        val requests = mutableListOf<String>()
        lateinit var test: TestHttp

        fun paths(): List<String> = requests.map { it.substringBefore('?') }
    }

    private fun TestScope.routedHttp(
        all: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
            jsonResponse(allBody(request.url.parameters["q"].orEmpty()))
        },
        page: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
            jsonResponse(moviesPage(request.url.parameters["page"]!!.toLong(), totalPages = 1))
        },
    ): RoutedHttp {
        val routed = RoutedHttp()
        routed.test = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
            val path = request.url.encodedPath
            val q = request.url.parameters["q"].orEmpty()
            val pageNumber = request.url.parameters["page"]
            routed.requests += if (pageNumber == null) "$path?q=$q" else "$path?q=$q&page=$pageNumber"
            when {
                path == "/api/search" -> all(request)
                path.startsWith("/api/search/") -> page(request)
                else -> error("unexpected request to $path")
            }
        }
        return routed
    }

    private fun allBody(query: String, moviesTotal: Long = 12, tracksTotal: Long = 2) = searchAllJson(
        query = query,
        movies = searchSectionJson(listOf(movieLibraryItemJson(id = 3, title = "Help!")), total = moviesTotal),
        albums = searchSectionJson(listOf(simpleAlbumJson(id = 5)), total = 1),
        tracks = searchSectionJson(
            listOf(trackListItemJson(id = 1, title = "Help!"), trackListItemJson(id = 2, title = "Yesterday")),
            total = tracksTotal,
        ),
    )

    private fun moviesPage(number: Long, totalPages: Long, ids: LongRange = (number * 10)..(number * 10 + 2)) =
        searchPageJson(
            results = ids.map { movieLibraryItemJson(id = it, title = "Movie $it") },
            total = 6,
            page = number,
            totalPages = totalPages,
        )

    private fun tracksPage(number: Long, ids: LongRange) = searchPageJson(
        results = ids.map { trackListItemJson(id = it, title = "Track $it") },
        total = ids.count().toLong(),
        page = number,
        totalPages = 1,
    )

    private fun model(http: RoutedHttp) = SearchViewModel(http.test.searchRepository, http.test.serverUrl)

    private fun TestScope.type(model: SearchViewModel, text: String) {
        model.onQueryChange(text)
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
    }

    private fun TestScope.landOn(model: SearchViewModel, tab: SearchTab) {
        model.selectTab(tab)
        advanceTimeBy(TAB_SWITCH_DEBOUNCE_MS + 1)
    }

    private fun <T> PagedState<T>.items(): List<T> = (content as IglooRailState.Loaded).items

    private fun TestScope.collectPlayRequests(model: SearchViewModel): Pair<MutableList<MusicPlayRequest>, Job> {
        val requests = mutableListOf<MusicPlayRequest>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.playRequests.collect { requests += it } }
        return requests to job
    }

    // --- the query ---

    @Test
    fun `a blank query sends nothing and shows the prompt`() = runTest {
        val http = routedHttp()
        val model = model(http)

        type(model, "   ")

        assertTrue(http.requests.isEmpty())
        assertEquals("   ", model.uiState.value.query)
        assertEquals("", model.uiState.value.searched)
        assertEquals(SearchContent.Prompt, model.uiState.value.toSearchContent())
    }

    @Test
    fun `keystrokes within the debounce cost one request for the settled word`() = runTest {
        val http = routedHttp()
        val model = model(http)

        model.onQueryChange("h")
        model.onQueryChange("he")
        model.onQueryChange("hel")
        advanceTimeBy(SEARCH_DEBOUNCE_MS - 1)
        assertTrue(http.requests.isEmpty())
        assertEquals("hel", model.uiState.value.query)
        model.onQueryChange("help")
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)

        assertEquals(listOf("/api/search?q=help"), http.requests)
        assertEquals("help", model.uiState.value.searched)
        val sections = model.uiState.value.all.items()
        assertEquals(listOf(12L, 0L, 1L, 0L, 2L), sections.map { it.total })
        assertEquals(SearchContent.Populated, model.uiState.value.toSearchContent())
    }

    @Test
    fun `the query is sent trimmed and a change of whitespace is not a new search`() = runTest {
        val http = routedHttp()
        val model = model(http)

        type(model, "  help ")
        type(model, "help  ")

        assertEquals(listOf("/api/search?q=help"), http.requests)
    }

    @Test
    fun `the keyboard's Search action sends the query at once`() = runTest {
        val http = routedHttp()
        val model = model(http)

        model.onQueryChange("help")
        model.searchNow()

        assertEquals(listOf("/api/search?q=help"), http.requests)
        // The debounce was cancelled: nothing follows when its time would have come.
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `Search on an answered query does not fetch it again`() = runTest {
        val http = routedHttp()
        val model = model(http)
        type(model, "help")

        model.searchNow()

        assertEquals(1, http.requests.size)
        assertEquals(SearchContent.Populated, model.uiState.value.toSearchContent())
    }

    @Test
    fun `Search on a failed query retries it`() = runTest {
        var fail = true
        val http = routedHttp(all = { request ->
            if (fail) jsonResponse("""{"error":true,"message":"down"}""", HttpStatusCode.InternalServerError) else jsonResponse(allBody(request.url.parameters["q"].orEmpty()))
        })
        val model = model(http)
        type(model, "help")
        assertEquals(SearchContent.Error("down"), model.uiState.value.toSearchContent())

        fail = false
        model.searchNow()

        assertEquals(2, http.requests.size)
        assertEquals(SearchContent.Populated, model.uiState.value.toSearchContent())
    }

    @Test
    fun `a failed query giving way to a new search counts as a replacement`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val http = routedHttp(all = { request ->
            if (request.url.parameters["q"] == "help") {
                jsonResponse("""{"error":true,"message":"down"}""", HttpStatusCode.InternalServerError)
            } else {
                gate.await()
                jsonResponse(allBody(request.url.parameters["q"].orEmpty()))
            }
        })
        val model = model(http)
        type(model, "help")
        assertEquals(SearchContent.Error("down"), model.uiState.value.toSearchContent())
        val failedGeneration = model.uiState.value.all.contentGeneration

        // The Retry the user may be standing on disposes here, so the screen must re-anchor.
        type(model, "helpers")

        assertEquals(SearchContent.Loading, model.uiState.value.toSearchContent())
        assertEquals(failedGeneration + 1, model.uiState.value.all.contentGeneration)
        gate.complete(Unit)
    }

    @Test
    fun `a new query forgets every tab and fetches only the selected one`() = runTest {
        val http = routedHttp()
        val model = model(http)
        type(model, "help")
        model.pressTab(SearchTab.Movies)
        assertEquals(listOf("/api/search?q=help", "/api/search/movies?q=help&page=1"), http.requests)

        type(model, "yesterday")

        assertEquals("/api/search/movies?q=yesterday&page=1", http.requests.last())
        assertEquals(3, http.requests.size)
        assertEquals(IglooRailState.Loading, model.uiState.value.all.content)
        assertTrue(model.uiState.value.movies.content is IglooRailState.Loaded)
        assertEquals(listOf(10L, 11L, 12L), model.uiState.value.movies.items().map { it.id })
    }

    @Test
    fun `an answer to an earlier query is dropped`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var slowOnce = true
        val http = routedHttp(all = { request ->
            if (slowOnce) {
                slowOnce = false
                gate.await()
            }
            jsonResponse(allBody(request.url.parameters["q"].orEmpty(), moviesTotal = if (request.url.parameters["q"] == "first") 99 else 12))
        })
        val model = model(http)
        type(model, "first")
        assertEquals(IglooRailState.Loading, model.uiState.value.all.content)

        type(model, "second")
        gate.complete(Unit)

        assertEquals(listOf("/api/search?q=first", "/api/search?q=second"), http.requests)
        assertEquals(12L, model.uiState.value.all.items().first().total)
    }

    @Test
    fun `clearing empties the field and every tab`() = runTest {
        val http = routedHttp()
        val model = model(http)
        type(model, "help")
        model.pressTab(SearchTab.Movies)

        model.clear()

        assertEquals("", model.uiState.value.query)
        assertEquals("", model.uiState.value.searched)
        // The prompt draws no strip, so the next search starts on All, not a hidden Movies.
        assertEquals(SearchTab.All, model.uiState.value.tab)
        assertEquals(SearchContent.Prompt, model.uiState.value.toSearchContent())
        assertEquals(IglooRailState.Loading, model.uiState.value.movies.content)
        assertEquals(IglooRailState.Loading, model.uiState.value.all.content)
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `clearing while a search is out drops its answer`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val http = routedHttp(all = { request ->
            gate.await()
            jsonResponse(allBody(request.url.parameters["q"].orEmpty()))
        })
        val model = model(http)
        type(model, "help")

        model.clear()
        gate.complete(Unit)

        assertEquals(IglooRailState.Loading, model.uiState.value.all.content)
        assertEquals(SearchContent.Prompt, model.uiState.value.toSearchContent())
    }

    @Test
    fun `a sign-out while a search is out fails the load rather than crashing`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val http = routedHttp(all = { request ->
            gate.await()
            jsonResponse(allBody(request.url.parameters["q"].orEmpty()))
        })
        val model = model(http)
        type(model, "help")

        // The session tears down: the address clears before the answer lands.
        http.test.serverUrl.set(null)
        gate.complete(Unit)

        assertTrue(model.uiState.value.all.content is IglooRailState.Error)
    }

    @Test
    fun `clearing drops a tab focus still waiting out its debounce`() = runTest {
        val http = routedHttp()
        val model = model(http)
        type(model, "help")

        model.selectTab(SearchTab.Movies)
        model.clear()
        advanceTimeBy(TAB_SWITCH_DEBOUNCE_MS + 1)

        assertEquals(listOf("/api/search?q=help"), http.requests)
        assertEquals(IglooRailState.Loading, model.uiState.value.movies.content)
    }

    @Test
    fun `Search with a blank field and Retry with nothing searched send nothing`() = runTest {
        val http = routedHttp()
        val model = model(http)

        model.onQueryChange("   ")
        model.searchNow()
        model.retryFirstPage()
        model.loadMore()

        assertTrue(http.requests.isEmpty())
    }

    // --- tabs ---

    @Test
    fun `a tab merely crossed by the d-pad requests nothing`() = runTest {
        val http = routedHttp()
        val model = model(http)
        type(model, "help")

        model.selectTab(SearchTab.Movies)
        model.selectTab(SearchTab.Shows)
        advanceTimeBy(TAB_SWITCH_DEBOUNCE_MS - 1)

        assertEquals(1, http.requests.size)
        assertEquals(SearchTab.Shows, model.uiState.value.tab)
    }

    @Test
    fun `a tab landed on loads and keeps its pages across a switch`() = runTest {
        val http = routedHttp()
        val model = model(http)
        type(model, "help")

        landOn(model, SearchTab.Movies)
        landOn(model, SearchTab.All)
        landOn(model, SearchTab.Movies)

        assertEquals(listOf("/api/search?q=help", "/api/search/movies?q=help&page=1"), http.requests)
        assertEquals(listOf(10L, 11L, 12L), model.uiState.value.movies.items().map { it.id })
    }

    @Test
    fun `landing on a tab that already failed does not refetch it`() = runTest {
        var fail = true
        val http = routedHttp(page = { request ->
            if (fail) {
                jsonResponse("""{"error":true,"message":"down"}""", HttpStatusCode.InternalServerError)
            } else {
                jsonResponse(moviesPage(request.url.parameters["page"]!!.toLong(), totalPages = 1))
            }
        })
        val model = model(http)
        type(model, "help")
        model.pressTab(SearchTab.Movies)
        assertEquals(SearchContent.Error("down"), model.uiState.value.toSearchContent())

        fail = false
        landOn(model, SearchTab.All)
        landOn(model, SearchTab.Movies)

        // The error card and its Retry stay; a slide back across the strip is not a retry.
        assertEquals(2, http.requests.size)
        assertEquals(SearchContent.Error("down"), model.uiState.value.toSearchContent())
    }

    @Test
    fun `a tab selected with no query waits for one`() = runTest {
        val http = routedHttp()
        val model = model(http)

        model.pressTab(SearchTab.Albums)
        landOn(model, SearchTab.Tracks)
        assertTrue(http.requests.isEmpty())

        type(model, "help")

        assertEquals(listOf("/api/search/tracks?q=help&page=1"), http.requests)
    }

    // --- paging ---

    @Test
    fun `a category tab pages by page and per_page until the last page`() = runTest {
        val http = routedHttp(page = { request ->
            jsonResponse(moviesPage(request.url.parameters["page"]!!.toLong(), totalPages = 2))
        })
        val model = model(http)
        type(model, "help")
        model.pressTab(SearchTab.Movies)
        assertEquals(AppendState.Idle, model.uiState.value.movies.append)

        model.loadMore()

        assertEquals("/api/search/movies?q=help&page=2", http.requests.last())
        assertEquals(listOf(10L, 11L, 12L, 20L, 21L, 22L), model.uiState.value.movies.items().map { it.id })
        assertEquals(AppendState.End, model.uiState.value.movies.append)
        assertEquals(6L, model.uiState.value.movies.total)
    }

    @Test
    fun `a failed first page offers Retry and a failed tail keeps the pages`() = runTest {
        var fail = true
        val http = routedHttp(page = { request ->
            if (fail) {
                jsonResponse("""{"error":true,"message":"down"}""", HttpStatusCode.InternalServerError)
            } else {
                jsonResponse(moviesPage(request.url.parameters["page"]!!.toLong(), totalPages = 2))
            }
        })
        val model = model(http)
        type(model, "help")
        model.pressTab(SearchTab.Movies)
        assertEquals(SearchContent.Error("down"), model.uiState.value.toSearchContent())

        fail = false
        model.retryFirstPage()
        assertEquals(SearchContent.Populated, model.uiState.value.toSearchContent())

        fail = true
        model.loadMore()
        assertEquals(AppendState.Error("down"), model.uiState.value.movies.append)
        assertEquals(3, model.uiState.value.movies.items().size)

        fail = false
        model.retryAppend()
        assertEquals(6, model.uiState.value.movies.items().size)
        assertEquals(AppendState.End, model.uiState.value.movies.append)
    }

    // --- playback ---

    @Test
    fun `Play on the Tracks tab queues the loaded rows from the pressed one`() = runTest {
        val http = routedHttp(page = { request ->
            jsonResponse(tracksPage(request.url.parameters["page"]!!.toLong(), 1L..3L))
        })
        val model = model(http)
        val (requests, job) = collectPlayRequests(model)
        type(model, "help")
        model.pressTab(SearchTab.Tracks)

        model.playTrack(2)

        val request = requests.single()
        assertEquals(MusicQueueSource.TrackList, request.source)
        assertEquals(1, request.startIndex)
        assertEquals(listOf(1L, 2L, 3L), request.tracks.map { it.id })
        job.cancel()
    }

    @Test
    fun `Play on the All view queues the rows its tracks section shows`() = runTest {
        val http = routedHttp()
        val model = model(http)
        val (requests, job) = collectPlayRequests(model)
        type(model, "help")

        model.playTrack(2)
        model.playTrack(99)

        val request = requests.single()
        assertEquals(1, request.startIndex)
        assertEquals(listOf(1L, 2L), request.tracks.map { it.id })
        job.cancel()
    }
}
