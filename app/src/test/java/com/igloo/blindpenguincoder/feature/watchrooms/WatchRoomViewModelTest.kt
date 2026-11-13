package com.igloo.blindpenguincoder.feature.watchrooms

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.data.model.UserSummary
import com.igloo.blindpenguincoder.data.model.WatchRoomClientEvent
import com.igloo.blindpenguincoder.data.model.WatchRoomCommandType
import com.igloo.blindpenguincoder.data.model.WatchRoomDetail
import com.igloo.blindpenguincoder.data.model.WatchRoomEventType
import com.igloo.blindpenguincoder.data.model.WatchRoomPlaybackState
import com.igloo.blindpenguincoder.data.model.WatchRoomServerEvent
import com.igloo.blindpenguincoder.data.repository.RoomSocket
import com.igloo.blindpenguincoder.data.repository.TestHttp
import com.igloo.blindpenguincoder.data.repository.WatchRoomLink
import com.igloo.blindpenguincoder.data.repository.jsonResponse
import com.igloo.blindpenguincoder.data.repository.movieDetailsJson
import com.igloo.blindpenguincoder.data.repository.technicalDetailsJson
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.playback.model.RoomPlayhead
import com.igloo.blindpenguincoder.playback.model.TransportCommand
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest
import java.io.EOFException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchRoomViewModelTest {

    private val maria = UserSummary(id = 2, name = "Maria", avatar = null)
    private val me = UserSummary(id = 1, name = "Jose", avatar = null)

    /**
     * The room's REST half answered from fields, and its socket as a channel per connection:
     * closing the channel is the server hanging up, which the real client sees as an EOF.
     */
    private class FakeRoomLink(var room: WatchRoomDetail) : WatchRoomLink {
        var join: ApiResult<Unit> = ApiResult.Success(Unit)
        var delete: ApiResult<Unit> = ApiResult.Success(Unit)
        var joins = 0
        var deletes = 0
        val sent = mutableListOf<WatchRoomClientEvent>()
        var server: Channel<WatchRoomServerEvent>? = null

        override suspend fun watchRoom(id: Long) = ApiResult.Success(room)

        override suspend fun join(id: Long): ApiResult<Unit> {
            joins += 1
            return join
        }

        override suspend fun delete(id: Long): ApiResult<Unit> {
            deletes += 1
            return delete
        }

        override suspend fun connect(id: Long, session: suspend (RoomSocket) -> Unit) {
            val channel = Channel<WatchRoomServerEvent>(Channel.UNLIMITED).also { server = it }
            session(
                object : RoomSocket {
                    override val events = channel.receiveAsFlow()

                    override suspend fun send(command: WatchRoomClientEvent) {
                        sent += command
                    }
                },
            )
            throw EOFException()
        }

        fun emit(event: WatchRoomServerEvent) = requireNotNull(server).trySend(event)

        fun hangUp() = requireNotNull(server).close()
    }

    private val gone = mutableListOf<Pair<Long, String?>>()

    private fun detail(isOwner: Boolean = true) = WatchRoomDetail(
        id = 7,
        movieId = 1,
        movieTitle = "Heat",
        moviePoster = "/heat.jpg",
        owner = if (isOwner) me else maria,
        members = listOf(me, maria),
        playbackMode = "remux",
        isOwner = isOwner,
        audioTrack = 0,
        subtitleTrack = null,
    )

    private fun snapshot(paused: Boolean, position: Double, connected: List<Long>) = WatchRoomServerEvent(
        type = WatchRoomEventType.RoomSnapshot,
        playback = WatchRoomPlaybackState(paused, position),
        connectedUserIds = connected,
    )

    /** The view model on runTest's scheduler, its clock that scheduler's, and its room open. */
    private fun test(
        link: FakeRoomLink = FakeRoomLink(detail()),
        block: suspend TestScope.(WatchRoomViewModel, FakeRoomLink) -> Unit,
    ) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val http = TestHttp(UnconfinedTestDispatcher(testScheduler)) { request ->
            when (request.url.encodedPath) {
                "/api/movies/details/1" -> jsonResponse(movieDetailsJson())
                "/api/movies/1/technical-details" -> jsonResponse(technicalDetailsJson())
                else -> error("unexpected request to ${request.url.encodedPath}")
            }
        }
        val viewModel = WatchRoomViewModel(
            link = link,
            movies = http.movieRepository,
            serverUrl = http.serverUrl,
            selfId = me.id,
            onRoomGone = { id, notice -> gone += id to notice },
            clock = { testScheduler.currentTime },
        )
        try {
            viewModel.open(7)
            runCurrent()
            block(viewModel, link)
        } finally {
            // The reconnect loop runs for as long as the room is open.
            viewModel.leave()
            Dispatchers.resetMain()
        }
    }

    private suspend fun TestScope.watched(viewModel: WatchRoomViewModel): List<VideoPlayRequest> {
        val requests = mutableListOf<VideoPlayRequest>()
        val collector = backgroundScope.launch { viewModel.playRequests.collect { requests += it } }
        viewModel.watch()
        runCurrent()
        collector.cancel()
        return requests
    }

    @Test
    fun `opening loads the page and joins before connecting`() = test { viewModel, link ->
        val page = (viewModel.uiState.value.details as DetailsState.Loaded).value

        assertEquals("Heat", page.title)
        assertEquals(7L, page.playRequest.watchRoomId)
        assertEquals("video/x-matroska", page.playRequest.mimeType)
        assertEquals(1, link.joins)
        assertEquals(RoomConnection.Connecting, viewModel.uiState.value.sync.connection)
    }

    @Test
    fun `watch joins a playing room where it is now`() = test { viewModel, link ->
        link.emit(snapshot(paused = false, position = 600.0, connected = listOf(1, 2)))
        runCurrent()
        advanceTimeBy(5_000)

        val requests = watched(viewModel)

        assertEquals(605.0, requests.single().resumeAtSec!!, 0.01)
        assertTrue(viewModel.uiState.value.startPlaying)
        assertFalse(viewModel.uiState.value.startingAlone)
    }

    @Test
    fun `watch joins a paused room with others in it paused`() = test { viewModel, link ->
        link.emit(snapshot(paused = true, position = 600.0, connected = listOf(1, 2)))
        runCurrent()

        watched(viewModel)

        assertFalse(viewModel.uiState.value.startPlaying)
        assertTrue(link.sent.isEmpty())
    }

    @Test
    fun `alone in a paused room the first frame starts the room's clock`() = test { viewModel, link ->
        link.emit(snapshot(paused = true, position = 0.0, connected = listOf(1)))
        runCurrent()

        watched(viewModel)
        val state = viewModel.uiState.value
        assertTrue(state.startPlaying)
        // Until that frame the player has no room to follow, so the paused room cannot pause it.
        assertNull(state.toPlayerRoom(viewModel.actions()).playhead)

        viewModel.onPlaybackStarted(0.4)
        runCurrent()

        assertEquals(listOf(WatchRoomClientEvent(WatchRoomCommandType.Play, 0.4)), link.sent)
        assertEquals(
            RoomPlayhead(paused = false, positionSec = 0.4, atMs = testScheduler.currentTime),
            viewModel.uiState.value.toPlayerRoom(viewModel.actions()).playhead,
        )
    }

    @Test
    fun `a run of seeks goes to the room as one, and a pause sends it first`() = test { viewModel, link ->
        link.emit(snapshot(paused = false, position = 100.0, connected = listOf(1, 2)))
        runCurrent()

        viewModel.onTransport(TransportCommand.SeekTo(110.0), 110.0)
        viewModel.onTransport(TransportCommand.SeekTo(120.0), 120.0)
        viewModel.onTransport(TransportCommand.SeekTo(130.0), 130.0)
        runCurrent()
        assertTrue(link.sent.isEmpty())
        advanceTimeBy(ROOM_SEEK_SETTLE_MS + 1)
        assertEquals(listOf(WatchRoomClientEvent(WatchRoomCommandType.Seek, 130.0)), link.sent)

        viewModel.onTransport(TransportCommand.SeekTo(200.0), 200.0)
        viewModel.onTransport(TransportCommand.Pause, 200.0)
        runCurrent()
        assertEquals(
            listOf(
                WatchRoomClientEvent(WatchRoomCommandType.Seek, 130.0),
                WatchRoomClientEvent(WatchRoomCommandType.Seek, 200.0),
                WatchRoomClientEvent(WatchRoomCommandType.Pause, 200.0),
            ),
            link.sent,
        )
    }

    @Test
    fun `a dropped link reconnects on the web's schedule`() = test { viewModel, link ->
        link.emit(snapshot(paused = false, position = 100.0, connected = listOf(1, 2)))
        runCurrent()

        link.hangUp()
        runCurrent()
        assertEquals(RoomConnection.Reconnecting, viewModel.uiState.value.sync.connection)
        advanceTimeBy(999)
        assertEquals(1, link.joins)
        advanceTimeBy(2)
        assertEquals(2, link.joins)
    }

    @Test
    fun `a room that is no longer the user's is left with the web's words`() = test(
        FakeRoomLink(detail()).apply { join = ApiResult.Failure(AppError.Api("access denied", 403)) },
    ) { viewModel, _ ->
        assertEquals(listOf(7L to ROOM_UNAVAILABLE_NOTICE), gone)
        assertNull(viewModel.uiState.value.openRoomId)
    }

    @Test
    fun `a deleted room is left before its hang-up reads as a dropped link`() = test { viewModel, link ->
        link.emit(WatchRoomServerEvent(type = WatchRoomEventType.RoomDeleted))
        runCurrent()

        assertEquals(listOf(7L to "The watch room for Heat was closed."), gone)
        assertNull(viewModel.uiState.value.openRoomId)
        advanceTimeBy(20_000)
        assertEquals("no reconnect to a room that is gone", 1, link.joins)
    }

    @Test
    fun `closing asks, then deletes and leaves`() = test { viewModel, link ->
        viewModel.requestClose()
        assertTrue(viewModel.uiState.value.confirmingClose)

        viewModel.confirmClose()
        runCurrent()

        assertEquals(1, link.deletes)
        assertEquals(listOf(7L to "The watch room for Heat was closed."), gone)
    }

    @Test
    fun `a close that fails stays in the room and says why`() = test(
        FakeRoomLink(detail()).apply { delete = ApiResult.Failure(AppError.Network) },
    ) { viewModel, _ ->
        viewModel.requestClose()
        viewModel.confirmClose()
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(gone.isEmpty())
        assertFalse(state.confirmingClose)
        assertFalse(state.closing)
        assertTrue(state.notice!!.startsWith("Couldn't close the room"))
    }

    @Test
    fun `the background takes the link down and the foreground brings it back`() = test { viewModel, link ->
        viewModel.onBackground()
        runCurrent()
        advanceTimeBy(60_000)
        assertEquals(1, link.joins)

        viewModel.onForeground()
        runCurrent()
        assertEquals(2, link.joins)
    }
}
