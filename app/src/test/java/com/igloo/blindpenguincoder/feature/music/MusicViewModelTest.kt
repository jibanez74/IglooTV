package com.igloo.blindpenguincoder.feature.music

import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.albumsJson
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.musicStatsJson
import com.igloo.blindpenguincoder.data.repository.musiciansJson
import com.igloo.blindpenguincoder.data.repository.shuffleTracksJson
import com.igloo.blindpenguincoder.data.repository.simpleAlbumJson
import com.igloo.blindpenguincoder.data.repository.simpleMusicianJson
import com.igloo.blindpenguincoder.data.repository.trackListItemJson
import com.igloo.blindpenguincoder.data.repository.tracksJson
import com.igloo.blindpenguincoder.feature.shared.TAB_SWITCH_DEBOUNCE_MS
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.playback.model.MusicPlayRequest
import com.igloo.blindpenguincoder.playback.model.MusicQueueSource
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Music pane's paging machine: each tab loads on first selection and keeps its pages, a
 * tab merely crossed by the d-pad requests nothing, the two grids page by `page`/`per_page`
 * while the track list pages by `limit`/`offset`, and the three playback launches map the
 * loaded rows the way the web client does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Records what each tab asked the backend for, so the paging can be asserted. */
    private class RoutedHttp {
        val musicianPages = mutableListOf<String>()
        val albumPages = mutableListOf<String>()
        val trackOffsets = mutableListOf<String>()
        val perPages = mutableListOf<String>()
        var shuffleCalls = 0
        var statsCalls = 0
        lateinit var test: TestHttp
    }

    private fun TestScope.routedHttp(
        musicians: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(musiciansPage(1, totalPages = 1, ids = 1L..3L)) },
        albums: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(albumsPage(1, totalPages = 1, ids = 1L..3L)) },
        tracks: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(tracksPage(0, ids = 1L..3L, total = 3, hasMore = false)) },
        shuffle: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData =
            { jsonResponse(shuffleTracksJson(trackListItemJson(id = 7, title = "Seven"))) },
    ): RoutedHttp {
        val routed = RoutedHttp()
        routed.test = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
            val path = request.url.encodedPath
            when (path) {
                "/api/music/stats" -> {
                    routed.statsCalls += 1
                    jsonResponse(musicStatsJson(albums = 90, tracks = 5, musicians = 60))
                }
                "/api/music/musicians" -> {
                    routed.musicianPages += request.url.parameters["page"].orEmpty()
                    routed.perPages += request.url.parameters["per_page"].orEmpty()
                    musicians(request)
                }
                "/api/music/albums" -> {
                    routed.albumPages += request.url.parameters["page"].orEmpty()
                    routed.perPages += request.url.parameters["per_page"].orEmpty()
                    albums(request)
                }
                "/api/music/tracks" -> {
                    routed.trackOffsets += request.url.parameters["offset"].orEmpty()
                    tracks(request)
                }
                "/api/music/tracks/shuffle" -> {
                    routed.shuffleCalls += 1
                    shuffle(request)
                }
                else -> error("unexpected request to $path")
            }
        }
        return routed
    }

    private fun musiciansPage(number: Long, totalPages: Long, ids: LongRange, total: Long = 60) =
        musiciansJson(ids.map { simpleMusicianJson(id = it, name = "Musician $it") }, total, number, 48, totalPages)

    private fun albumsPage(number: Long, totalPages: Long, ids: LongRange, total: Long = 90) =
        albumsJson(ids.map { simpleAlbumJson(id = it, title = "Album $it") }, total, number, 48, totalPages)

    private fun tracksPage(offset: Long, ids: LongRange, total: Long, hasMore: Boolean) =
        tracksJson(ids.map { trackListItemJson(id = it, title = "Track $it") }, total, offset, 50, hasMore)

    /** The host's start effect is what fires the first load; there is no fetch in `init`. */
    private fun loaded(http: RoutedHttp) = MusicViewModel(http.test.musicRepository).also { it.refresh() }

    private fun TestScope.landOn(model: MusicViewModel, tab: MusicTab) {
        model.selectTab(tab)
        advanceTimeBy(TAB_SWITCH_DEBOUNCE_MS + 1)
    }

    private fun <T> PagedState<T>.items(): List<T> = (content as IglooRailState.Loaded).items

    private fun TestScope.collectPlayRequests(model: MusicViewModel): Pair<MutableList<MusicPlayRequest>, Job> {
        val requests = mutableListOf<MusicPlayRequest>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.playRequests.collect { requests += it } }
        return requests to job
    }

    // --- first load ---

    @Test
    fun `start loads the stats and the selected tab only`() = runTest {
        val http = routedHttp()
        val model = loaded(http)

        assertEquals(1, http.statsCalls)
        assertEquals(listOf("1"), http.musicianPages)
        assertEquals(listOf("48"), http.perPages)
        assertTrue(http.albumPages.isEmpty())
        assertTrue(http.trackOffsets.isEmpty())
        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.musicians.items().map { it.id })
        assertEquals(60L, model.uiState.value.musicians.total)
        assertEquals(AppendState.End, model.uiState.value.musicians.append)
        assertTrue(model.uiState.value.albums.content is IglooRailState.Loading)
        assertEquals(90L, model.uiState.value.stats?.totalAlbums)
    }

    @Test
    fun `a second start keeps the pages and re-reads only the stats`() = runTest {
        val http = routedHttp()
        val model = loaded(http)

        model.refresh()

        assertEquals(2, http.statsCalls)
        assertEquals(listOf("1"), http.musicianPages)
    }

    // --- tabs ---

    @Test
    fun `a tab taking focus highlights at once but waits out the debounce before requesting`() = runTest {
        val http = routedHttp()
        val model = loaded(http)

        model.selectTab(MusicTab.Albums)
        assertEquals(MusicTab.Albums, model.uiState.value.tab)
        advanceTimeBy(TAB_SWITCH_DEBOUNCE_MS - 1)
        assertTrue(http.albumPages.isEmpty())

        advanceTimeBy(2)
        assertEquals(listOf("1"), http.albumPages)
        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.albums.items().map { it.id })
    }

    @Test
    fun `a tab crossed on the way to another requests nothing`() = runTest {
        val http = routedHttp()
        val model = loaded(http)

        model.selectTab(MusicTab.Albums)
        advanceTimeBy(TAB_SWITCH_DEBOUNCE_MS / 2)
        landOn(model, MusicTab.Tracks)

        assertTrue(http.albumPages.isEmpty())
        assertEquals(listOf("0"), http.trackOffsets)
    }

    @Test
    fun `a press loads at once and a loaded tab is never re-requested`() = runTest {
        val http = routedHttp()
        val model = loaded(http)

        model.pressTab(MusicTab.Tracks)
        assertEquals(listOf("0"), http.trackOffsets)

        landOn(model, MusicTab.Musicians)
        landOn(model, MusicTab.Tracks)
        model.pressTab(MusicTab.Musicians)

        assertEquals(listOf("1"), http.musicianPages)
        assertEquals(listOf("0"), http.trackOffsets)
        assertEquals(MusicTab.Musicians, model.uiState.value.tab)
    }

    @Test
    fun `a failed first page is an error card on that tab and Retry re-requests it`() = runTest {
        var fail = true
        val http = routedHttp(albums = {
            if (fail) jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
            else jsonResponse(albumsPage(1, totalPages = 1, ids = 5L..6L))
        })
        val model = loaded(http)

        landOn(model, MusicTab.Albums)
        assertTrue(model.uiState.value.albums.content is IglooRailState.Error)
        // The other tab is untouched: a switch cannot fail, only a tab's own page can.
        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.musicians.items().map { it.id })
        assertNull(model.uiState.value.notice)

        fail = false
        model.retryFirstPage()

        assertEquals(listOf("1", "1"), http.albumPages)
        assertEquals(listOf(5L, 6L), model.uiState.value.albums.items().map { it.id })
        assertEquals(1, model.uiState.value.albums.contentGeneration)
    }

    // --- appending ---

    @Test
    fun `load more pages the selected grid by page and dedupes and ends at the last page`() = runTest {
        val http = routedHttp(musicians = {
            when (it.url.parameters["page"]) {
                "1" -> jsonResponse(musiciansPage(1, totalPages = 2, ids = 1L..3L))
                else -> jsonResponse(musiciansPage(2, totalPages = 2, ids = 3L..5L))
            }
        })
        val model = loaded(http)
        assertEquals(AppendState.Idle, model.uiState.value.musicians.append)

        model.loadMore()
        model.loadMore()

        assertEquals(listOf("1", "2"), http.musicianPages)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), model.uiState.value.musicians.items().map { it.id })
        assertEquals(AppendState.End, model.uiState.value.musicians.append)
        assertEquals(1, model.uiState.value.musicians.appendGeneration)
    }

    @Test
    fun `the track list pages by offset and rebuilds its letter headers across the boundary`() = runTest {
        val http = routedHttp(tracks = {
            when (it.url.parameters["offset"]) {
                "0" -> jsonResponse(tracksJson(listOf(trackListItemJson(1, "Abbey Road"), trackListItemJson(2, "Across")), 4, 0, 50, true))
                else -> jsonResponse(tracksJson(listOf(trackListItemJson(3, "Act"), trackListItemJson(4, "Blackbird")), 4, 2, 50, false))
            }
        })
        val model = loaded(http)
        model.pressTab(MusicTab.Tracks)

        model.loadMore()

        assertEquals(listOf("0", "2"), http.trackOffsets)
        val entries = model.uiState.value.tracks.items()
        assertEquals(
            listOf("A", "Abbey Road", "Across", "Act", "B", "Blackbird"),
            entries.map { if (it is TracksEntry.Letter) it.letter else (it as TracksEntry.Track).track.title },
        )
        assertEquals(AppendState.End, model.uiState.value.tracks.append)
        assertEquals(4L, model.uiState.value.tracks.total)
    }

    @Test
    fun `a failed append keeps the pages and becomes a Retry tail`() = runTest {
        val http = routedHttp(musicians = {
            when (it.url.parameters["page"]) {
                "1" -> jsonResponse(musiciansPage(1, totalPages = 2, ids = 1L..3L))
                else -> jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
            }
        })
        val model = loaded(http)

        model.loadMore()

        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.musicians.items().map { it.id })
        assertTrue(model.uiState.value.musicians.append is AppendState.Error)

        model.retryAppend()
        assertEquals(listOf("1", "2", "2"), http.musicianPages)
    }

    // --- refresh ---

    @Test
    fun `refresh keeps the content on screen and reports a failure as a notice`() = runTest {
        var calls = 0
        val http = routedHttp(musicians = {
            calls += 1
            if (calls == 1) jsonResponse(musiciansPage(1, totalPages = 1, ids = 1L..3L))
            else jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
        })
        val model = loaded(http)

        model.reload()

        assertEquals(listOf(1L, 2L, 3L), model.uiState.value.musicians.items().map { it.id })
        assertTrue(model.uiState.value.notice != null)
        assertFalse(model.uiState.value.refreshing)
        assertEquals(2, http.statsCalls)
    }

    // --- playback launches ---

    @Test
    fun `a row's play queues the loaded list from that row`() = runTest {
        val http = routedHttp()
        val model = loaded(http)
        model.pressTab(MusicTab.Tracks)
        val (requests, job) = collectPlayRequests(model)

        model.playTrack(2)

        val request = requests.single()
        assertEquals(MusicQueueSource.TrackList, request.source)
        assertEquals(1, request.startIndex)
        assertEquals(listOf(1L, 2L, 3L), request.tracks.map { it.id })
        job.cancel()
    }

    @Test
    fun `play all queues the loaded rows and continues from their count`() = runTest {
        val http = routedHttp(tracks = { jsonResponse(tracksPage(0, ids = 1L..3L, total = 120, hasMore = true)) })
        val model = loaded(http)
        model.pressTab(MusicTab.Tracks)
        val (requests, job) = collectPlayRequests(model)

        model.playAll()

        assertEquals(MusicQueueSource.LibraryInOrder(nextOffset = 3, total = 120), requests.single().source)
        job.cancel()
    }

    @Test
    fun `shuffle all fetches a batch, shows the wait, and launches the shuffle queue`() = runTest {
        val http = routedHttp()
        val model = loaded(http)
        val (requests, job) = collectPlayRequests(model)

        model.shuffleAll()

        assertEquals(1, http.shuffleCalls)
        assertFalse(model.uiState.value.shufflePending)
        assertEquals(MusicQueueSource.LibraryShuffle, requests.single().source)
        assertEquals(listOf(7L), requests.single().tracks.map { it.id })
        job.cancel()
    }

    @Test
    fun `an empty shuffle batch or a failure is a notice, never a player`() = runTest {
        var empty = true
        val http = routedHttp(shuffle = {
            if (empty) jsonResponse(shuffleTracksJson())
            else jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
        })
        val model = loaded(http)
        val (requests, job) = collectPlayRequests(model)

        model.shuffleAll()
        assertEquals("No tracks to shuffle.", model.uiState.value.notice)

        empty = false
        model.shuffleAll()
        assertTrue(model.uiState.value.notice!!.startsWith("Couldn't start shuffle"))
        assertTrue(requests.isEmpty())
        job.cancel()
    }

    private companion object {
        const val ERROR_BODY = """{"error":true,"message":"nope"}"""
    }
}
