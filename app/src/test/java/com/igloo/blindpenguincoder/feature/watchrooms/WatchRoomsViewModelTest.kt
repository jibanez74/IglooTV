package com.igloo.blindpenguincoder.feature.watchrooms

import com.igloo.blindpenguincoder.MainDispatcherRule
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.watchRoomJson
import com.igloo.blindpenguincoder.data.repository.watchRoomsJson
import com.igloo.blindpenguincoder.feature.library.ERROR_BODY
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchRoomsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    /** Counts the list requests, so "one request, not two" can be asserted. */
    private class RoomsHttp {
        var requests = 0
        lateinit var test: TestHttp
    }

    /**
     * The mock engine is put on the test scheduler so a response and the view model share one
     * clock; any route but the list fails the test.
     */
    private fun TestScope.roomsHttp(rooms: MockRequestHandler): RoomsHttp {
        val http = RoomsHttp()
        http.test = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
            check(request.url.encodedPath == "/api/watch-rooms") {
                "unexpected request to ${request.url.encodedPath}"
            }
            http.requests += 1
            rooms(request)
        }
        return http
    }

    private fun model(http: RoomsHttp) =
        WatchRoomsViewModel(http.test.watchRoomRepository, http.test.serverUrl)

    private fun rooms(vararg ids: Long) =
        watchRoomsJson(*ids.map { watchRoomJson(id = it, movieTitle = "Movie $it") }.toTypedArray())

    private fun WatchRoomsUiState.roomIds(): List<Long>? =
        (rooms as? IglooRailState.Loaded)?.items?.map { it.id }

    private val failure: MockRequestHandler =
        { jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) }

    // --- first load -------------------------------------------------------------------------

    @Test
    fun `nothing is requested until the pane comes on screen`() = runTest {
        val http = roomsHttp { jsonResponse(rooms(1)) }

        val model = model(http)

        assertEquals(0, http.requests)
        assertEquals(IglooRailState.Loading, model.uiState.value.rooms)
    }

    @Test
    fun `the first load shows the rooms and counts as a replacement`() = runTest {
        val http = roomsHttp { jsonResponse(rooms(1, 2)) }
        val model = model(http)

        model.refresh()

        val state = model.uiState.value
        assertEquals(listOf(1L, 2L), state.roomIds())
        assertEquals("http://igloo.test:8080/api/tmdb/images/w500/heat.jpg", loadedRooms(model).first().posterUrl)
        // The skeleton's anchor hands focus to the first card off this.
        assertEquals(1, state.contentGeneration)
        assertEquals(0, state.reconcileGeneration)
        assertFalse(state.refreshing)
    }

    @Test
    fun `no rooms is a loaded empty list, not an error`() = runTest {
        val http = roomsHttp { jsonResponse(rooms()) }
        val model = model(http)

        model.refresh()

        assertEquals(emptyList<Long>(), model.uiState.value.roomIds())
    }

    @Test
    fun `a failed first load is the error card, with no notice beside it`() = runTest {
        val http = roomsHttp(failure)
        val model = model(http)

        model.refresh()

        assertEquals(IglooRailState.Error("nope"), model.uiState.value.rooms)
        assertNull(model.uiState.value.notice)
    }

    @Test
    fun `retrying a failed first load keeps the error card until the answer lands`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var fail = true
        val http = roomsHttp {
            if (fail) return@roomsHttp jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError)
            gate.await()
            jsonResponse(rooms(1))
        }
        val model = model(http)
        model.refresh()

        fail = false
        model.reload()

        // Retry is the focused node; a skeleton here would dispose it mid-request.
        assertEquals(IglooRailState.Error("nope"), model.uiState.value.rooms)
        assertTrue(model.uiState.value.refreshing)
        gate.complete(Unit)
        assertEquals(listOf(1L), model.uiState.value.roomIds())
        assertFalse(model.uiState.value.refreshing)
    }

    // --- Refresh ----------------------------------------------------------------------------

    @Test
    fun `the list stays on screen while a Refresh is out, then is replaced`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var second = false
        val http = roomsHttp {
            if (!second) return@roomsHttp jsonResponse(rooms(1))
            gate.await()
            jsonResponse(rooms(2, 1))
        }
        val model = model(http)
        model.refresh()

        second = true
        model.reload()

        assertEquals(listOf(1L), model.uiState.value.roomIds())
        assertTrue(model.uiState.value.refreshing)
        gate.complete(Unit)
        val state = model.uiState.value
        assertEquals(listOf(2L, 1L), state.roomIds())
        assertFalse(state.refreshing)
        // The screen returns to the top and focus goes to the newest room off this.
        assertEquals(2, state.contentGeneration)
        assertEquals(0, state.reconcileGeneration)
    }

    @Test
    fun `a second Refresh while one is out is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var gated = false
        val http = roomsHttp {
            if (gated) gate.await()
            jsonResponse(rooms(1))
        }
        val model = model(http)
        model.refresh()

        gated = true
        model.reload()
        model.reload()
        gate.complete(Unit)

        assertEquals(2, http.requests)
    }

    @Test
    fun `a failed Refresh keeps the rooms and reports through a notice the next one clears`() = runTest {
        var fail = false
        val http = roomsHttp {
            if (fail) jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) else jsonResponse(rooms(1))
        }
        val model = model(http)
        model.refresh()

        fail = true
        model.reload()

        val failed = model.uiState.value
        assertEquals(listOf(1L), failed.roomIds())
        assertEquals("nope", failed.notice)
        assertFalse(failed.refreshing)
        // Nothing was replaced, so focus has no reason to move.
        assertEquals(1, failed.contentGeneration)

        fail = false
        model.reload()

        assertNull(model.uiState.value.notice)
    }

    // --- the pane coming back on screen -----------------------------------------------------

    @Test
    fun `coming back on screen reconciles a loaded list without replacing it`() = runTest {
        var second = false
        val http = roomsHttp { jsonResponse(if (second) rooms(1, 2) else rooms(1)) }
        val model = model(http)
        model.refresh()

        second = true
        model.refresh()

        val state = model.uiState.value
        assertEquals(listOf(1L, 2L), state.roomIds())
        // The user is left where they are: focus moves only if its room is gone.
        assertEquals(1, state.contentGeneration)
        assertEquals(1, state.reconcileGeneration)
    }

    @Test
    fun `a failed background read leaves the rooms alone and says nothing`() = runTest {
        var fail = false
        val http = roomsHttp {
            if (fail) jsonResponse(ERROR_BODY, HttpStatusCode.InternalServerError) else jsonResponse(rooms(1))
        }
        val model = model(http)
        model.refresh()

        fail = true
        model.refresh()

        assertEquals(listOf(1L), model.uiState.value.roomIds())
        assertNull(model.uiState.value.notice)
    }

    @Test
    fun `coming on screen while a request is out sends no second one`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val http = roomsHttp {
            gate.await()
            jsonResponse(rooms(1))
        }
        val model = model(http)

        model.refresh()
        model.refresh()
        gate.complete(Unit)

        assertEquals(1, http.requests)
        assertEquals(listOf(1L), model.uiState.value.roomIds())
    }

    @Test
    fun `a Refresh supersedes a background read still in flight`() = runTest {
        val background = CompletableDeferred<Unit>()
        var first = true
        val http = roomsHttp {
            if (first) {
                first = false
                background.await()
                jsonResponse(rooms(9))
            } else {
                jsonResponse(rooms(1))
            }
        }
        val model = model(http)
        model.refresh()

        model.reload()
        background.complete(Unit)

        // The stale answer never lands, and the press gets the replacement it is owed.
        assertEquals(listOf(1L), model.uiState.value.roomIds())
        assertEquals(1, model.uiState.value.contentGeneration)
        assertFalse(model.uiState.value.refreshing)
    }

    @Test
    fun `a sign-out clearing the address mid-load reads as a failed load`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val http = roomsHttp {
            gate.await()
            jsonResponse(rooms(1))
        }
        val model = model(http)
        model.refresh()

        http.test.serverUrl.set(null)
        gate.complete(Unit)

        assertTrue(model.uiState.value.rooms is IglooRailState.Error)
    }

    private fun loadedRooms(model: WatchRoomsViewModel): List<WatchRoomUi> =
        (model.uiState.value.rooms as IglooRailState.Loaded).items
}
