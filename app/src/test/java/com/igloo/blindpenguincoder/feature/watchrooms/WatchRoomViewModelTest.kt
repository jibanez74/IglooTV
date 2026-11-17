package com.igloo.blindpenguincoder.feature.watchrooms

import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.ui.IglooRailState
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
import kotlinx.coroutines.CompletableDeferred
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
        /** Answers the joins in order; [join] once it runs out. */
        val joinAnswers = ArrayDeque<ApiResult<Unit>>()
        var delete: ApiResult<Unit> = ApiResult.Success(Unit)
        var leave: ApiResult<Unit> = ApiResult.Success(Unit)
        /** Holds the leave's answer until completed, as a server that tells the sockets first. */
        var leaveGate: CompletableDeferred<Unit>? = null
        var update: ApiResult<Unit> = ApiResult.Success(Unit)
        var invite: ApiResult<List<UserSummary>> = ApiResult.Success(emptyList())
        /** Holds the invite list's answer until completed. */
        var inviteGate: CompletableDeferred<Unit>? = null
        var failSends = false
        var joins = 0
        var deletes = 0
        var leaves = 0
        var roomReads = 0
        val updates = mutableListOf<List<Long>>()
        val sent = mutableListOf<WatchRoomClientEvent>()
        var server: Channel<WatchRoomServerEvent>? = null

        override suspend fun watchRoom(id: Long): ApiResult<WatchRoomDetail> {
            roomReads += 1
            return ApiResult.Success(room)
        }

        override suspend fun join(id: Long): ApiResult<Unit> {
            joins += 1
            return joinAnswers.removeFirstOrNull() ?: join
        }

        override suspend fun inviteUsers(): ApiResult<List<UserSummary>> {
            inviteGate?.await()
            return invite
        }

        override suspend fun updateMembers(id: Long, invitedUserIds: List<Long>): ApiResult<Unit> {
            updates += invitedUserIds
            return update
        }

        override suspend fun leave(id: Long): ApiResult<Unit> {
            leaves += 1
            leaveGate?.await()
            return leave
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
                        if (failSends) throw EOFException()
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

    private val ana = UserSummary(id = 3, name = "Ana", avatar = null)

    @Test
    fun `a 401 on join stops the reconnect loop`() = test(
        FakeRoomLink(detail()).apply { join = ApiResult.Failure(AppError.Unauthorized) },
    ) { viewModel, link ->
        advanceTimeBy(60_000)

        assertEquals("the sign-in gate takes over", 1, link.joins)
        assertTrue(gone.isEmpty())
        assertEquals(7L, viewModel.uiState.value.openRoomId)
    }

    @Test
    fun `a join that fails for a moment is tried again, with the delay growing until a snapshot`() = test(
        FakeRoomLink(detail()).apply {
            joinAnswers += ApiResult.Failure(AppError.Network)
            joinAnswers += ApiResult.Failure(AppError.Api("bad gateway", 502))
        },
    ) { viewModel, link ->
        assertEquals(1, link.joins)
        advanceTimeBy(1_001)
        assertEquals(2, link.joins)
        // The second wait is twice the first.
        advanceTimeBy(1_500)
        assertEquals(2, link.joins)
        advanceTimeBy(501)
        assertEquals(3, link.joins)

        link.emit(snapshot(paused = true, position = 0.0, connected = listOf(1)))
        runCurrent()
        assertEquals(RoomConnection.Live, viewModel.uiState.value.sync.connection)
        // A snapshot resets the schedule: the next drop waits one second again.
        link.hangUp()
        runCurrent()
        advanceTimeBy(1_001)
        assertEquals(4, link.joins)
    }

    @Test
    fun `another member's play takes over from a viewer starting the room alone`() = test { viewModel, link ->
        link.emit(snapshot(paused = true, position = 0.0, connected = listOf(1)))
        runCurrent()
        watched(viewModel)
        assertTrue(viewModel.uiState.value.startingAlone)

        link.emit(
            WatchRoomServerEvent(
                type = WatchRoomEventType.PlaybackChanged,
                playback = WatchRoomPlaybackState(paused = false, positionSec = 30.0),
            ),
        )
        runCurrent()

        assertFalse(viewModel.uiState.value.startingAlone)
        viewModel.onPlaybackStarted(0.4)
        runCurrent()
        assertTrue("the first frame no longer starts the room", link.sent.isEmpty())
    }

    @Test
    fun `a send that fails on a dropping link is swallowed`() = test { viewModel, link ->
        link.emit(snapshot(paused = false, position = 100.0, connected = listOf(1, 2)))
        runCurrent()
        link.failSends = true

        viewModel.onTransport(TransportCommand.Pause, 100.0)
        runCurrent()

        assertTrue(link.sent.isEmpty())
        assertEquals(7L, viewModel.uiState.value.openRoomId)
    }

    @Test
    fun `a close that finds the room already gone leaves it`() = test(
        FakeRoomLink(detail()).apply { delete = ApiResult.Failure(AppError.Api("room not found", 404)) },
    ) { viewModel, _ ->
        viewModel.requestClose()
        viewModel.confirmClose()
        runCurrent()

        assertEquals(listOf(7L to "The watch room for Heat was closed."), gone)
        assertNull(viewModel.uiState.value.openRoomId)
    }

    @Test
    fun `a member change re-reads the page`() = test { viewModel, link ->
        link.emit(snapshot(paused = true, position = 0.0, connected = listOf(1, 2)))
        runCurrent()
        val reads = link.roomReads
        link.room = detail().copy(members = listOf(me, ana))

        link.emit(WatchRoomServerEvent(type = WatchRoomEventType.MembersChanged, members = listOf(me, ana)))
        runCurrent()

        assertEquals(reads + 1, link.roomReads)
        val page = (viewModel.uiState.value.details as DetailsState.Loaded).value
        assertEquals(listOf(1L, 3L), page.members.map { it.id })
    }

    @Test
    fun `a reconnect re-reads the page, the first connection does not`() = test { viewModel, link ->
        link.emit(snapshot(paused = true, position = 0.0, connected = listOf(1, 2)))
        runCurrent()
        val reads = link.roomReads

        link.room = detail().copy(members = listOf(me, ana))
        link.hangUp()
        runCurrent()
        advanceTimeBy(1_001)
        link.emit(snapshot(paused = true, position = 0.0, connected = listOf(1)))
        runCurrent()

        assertEquals(reads + 1, link.roomReads)
        val page = (viewModel.uiState.value.details as DetailsState.Loaded).value
        assertEquals(listOf(1L, 3L), page.members.map { it.id })
    }

    @Test
    fun `a revoked membership leaves the room without reconnecting`() = test(
        FakeRoomLink(detail(isOwner = false)),
    ) { viewModel, link ->
        link.emit(WatchRoomServerEvent(type = WatchRoomEventType.RoomAccessRevoked))
        runCurrent()

        assertEquals(listOf(7L to "You're no longer in the watch room for Heat."), gone)
        assertNull(viewModel.uiState.value.openRoomId)
        advanceTimeBy(20_000)
        assertEquals(1, link.joins)
    }

    @Test
    fun `an invitee leaves after confirming`() = test(FakeRoomLink(detail(isOwner = false))) { viewModel, link ->
        viewModel.requestLeave()
        assertTrue(viewModel.uiState.value.confirmingLeave)
        assertTrue(viewModel.uiState.value.dialogOpen)

        viewModel.confirmLeave()
        runCurrent()

        assertEquals(1, link.leaves)
        assertEquals(listOf(7L to "You left the watch room for Heat."), gone)
    }

    @Test
    fun `the leaver's own revoked event reads as leaving, not as a removal`() = test(
        FakeRoomLink(detail(isOwner = false)).apply { leaveGate = CompletableDeferred() },
    ) { viewModel, link ->
        viewModel.requestLeave()
        viewModel.confirmLeave()
        runCurrent()

        // The server revokes the leaver's sockets before it answers the leave.
        link.emit(WatchRoomServerEvent(type = WatchRoomEventType.RoomAccessRevoked))
        runCurrent()
        link.leaveGate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf(7L to "You left the watch room for Heat."), gone)
    }

    @Test
    fun `a leave that fails stays in the room and says why`() = test(
        FakeRoomLink(detail(isOwner = false)).apply { leave = ApiResult.Failure(AppError.Network) },
    ) { viewModel, _ ->
        viewModel.requestLeave()
        viewModel.confirmLeave()
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue(gone.isEmpty())
        assertFalse(state.confirmingLeave)
        assertFalse(state.leaving)
        assertTrue(state.notice!!.startsWith("Couldn't leave the room"))
    }

    @Test
    fun `a leave answered 403 means the room was already not this user's`() = test(
        FakeRoomLink(detail(isOwner = false)).apply { leave = ApiResult.Failure(AppError.Api("access denied", 403)) },
    ) { viewModel, link ->
        viewModel.requestLeave()
        viewModel.confirmLeave()
        runCurrent()

        assertEquals(1, link.leaves)
        assertEquals(listOf(7L to "You left the watch room for Heat."), gone)
        assertNull(viewModel.uiState.value.openRoomId)
    }

    @Test
    fun `the owner edits invitees from the room's current ones`() = test(
        FakeRoomLink(detail()).apply { invite = ApiResult.Success(listOf(maria, ana)) },
    ) { viewModel, link ->
        viewModel.editInvitees()
        runCurrent()
        val editing = viewModel.uiState.value.editing!!
        assertEquals(setOf(2L), editing.selected)
        assertEquals(listOf(2L, 3L), (editing.people as IglooRailState.Loaded).items.map { it.id })
        assertFalse(editing.changed)

        // Unchanged, Save sends nothing.
        viewModel.saveInvitees()
        runCurrent()
        assertTrue(link.updates.isEmpty())

        viewModel.toggleInvitee(2)
        viewModel.toggleInvitee(3)
        link.room = detail().copy(members = listOf(me, ana))
        val reads = link.roomReads
        viewModel.saveInvitees()
        runCurrent()

        assertEquals(listOf(listOf(3L)), link.updates)
        assertNull(viewModel.uiState.value.editing)
        assertEquals(reads + 1, link.roomReads)
        val page = (viewModel.uiState.value.details as DetailsState.Loaded).value
        assertEquals(listOf(1L, 3L), page.members.map { it.id })
    }

    @Test
    fun `an edit that fails closes the dialog and says why on the page`() = test(
        FakeRoomLink(detail()).apply {
            invite = ApiResult.Success(listOf(maria, ana))
            update = ApiResult.Failure(AppError.Network)
        },
    ) { viewModel, _ ->
        viewModel.editInvitees()
        runCurrent()
        viewModel.toggleInvitee(3)
        viewModel.saveInvitees()
        runCurrent()

        val state = viewModel.uiState.value
        assertNull(state.editing)
        assertTrue(state.notice!!.startsWith("Couldn't update the invitees"))
    }

    @Test
    fun `people that fail to load can be retried, and only the owner can edit`() = test(
        FakeRoomLink(detail()).apply { invite = ApiResult.Failure(AppError.Network) },
    ) { viewModel, link ->
        viewModel.editInvitees()
        runCurrent()
        assertTrue(viewModel.uiState.value.editing!!.people is IglooRailState.Error)

        link.invite = ApiResult.Success(listOf(maria))
        viewModel.retryInvitees()
        runCurrent()
        assertTrue(viewModel.uiState.value.editing!!.people is IglooRailState.Loaded)

        viewModel.dismissEdit()
        assertNull(viewModel.uiState.value.editing)
    }

    @Test
    fun `an invitee cannot open Edit invitees`() = test(FakeRoomLink(detail(isOwner = false))) { viewModel, _ ->
        viewModel.editInvitees()
        assertNull(viewModel.uiState.value.editing)
    }

    @Test
    fun `back from the background the room is re-read`() = test { viewModel, link ->
        link.emit(snapshot(paused = true, position = 0.0, connected = listOf(1, 2)))
        runCurrent()
        val reads = link.roomReads

        viewModel.onBackground()
        runCurrent()
        link.room = detail().copy(members = listOf(me, ana))
        viewModel.onForeground()
        runCurrent()
        link.emit(snapshot(paused = true, position = 0.0, connected = listOf(1)))
        runCurrent()

        assertEquals(reads + 1, link.roomReads)
        val page = (viewModel.uiState.value.details as DetailsState.Loaded).value
        assertEquals(listOf(1L, 3L), page.members.map { it.id })
    }

    @Test
    fun `the people still load when the app goes to the background meanwhile`() = test(
        FakeRoomLink(detail()).apply {
            invite = ApiResult.Success(listOf(maria, ana))
            inviteGate = CompletableDeferred()
        },
    ) { viewModel, link ->
        viewModel.editInvitees()
        runCurrent()
        viewModel.onBackground()
        runCurrent()
        link.inviteGate!!.complete(Unit)
        runCurrent()
        viewModel.onForeground()
        runCurrent()

        val people = viewModel.uiState.value.editing!!.people
        assertEquals(listOf(2L, 3L), (people as IglooRailState.Loaded).items.map { it.id })
    }

    @Test
    fun `an invitee who leaves while Edit invitees is open is not saved back`() = test(
        FakeRoomLink(detail().copy(members = listOf(me, maria, ana))).apply {
            invite = ApiResult.Success(listOf(maria, ana))
        },
    ) { viewModel, link ->
        link.emit(snapshot(paused = true, position = 0.0, connected = listOf(1)))
        runCurrent()
        viewModel.editInvitees()
        runCurrent()
        assertEquals(setOf(2L, 3L), viewModel.uiState.value.editing!!.selected)

        link.emit(WatchRoomServerEvent(type = WatchRoomEventType.MembersChanged, members = listOf(me, ana)))
        runCurrent()

        val editing = viewModel.uiState.value.editing!!
        assertEquals(setOf(3L), editing.initial)
        assertEquals(setOf(3L), editing.selected)
        assertFalse(editing.changed)
    }

    @Test
    fun `a reconnect refused while this viewer's leave is out reads as leaving`() = test(
        FakeRoomLink(detail(isOwner = false)).apply { leaveGate = CompletableDeferred() },
    ) { viewModel, link ->
        link.emit(snapshot(paused = true, position = 0.0, connected = listOf(1, 2)))
        runCurrent()
        viewModel.requestLeave()
        viewModel.confirmLeave()
        runCurrent()

        link.join = ApiResult.Failure(AppError.Api("access denied", 403))
        link.hangUp()
        runCurrent()
        advanceTimeBy(1_001)

        assertEquals(listOf(7L to "You left the watch room for Heat."), gone)
        link.leaveGate!!.complete(Unit)
        runCurrent()
    }

    @Test
    fun `a leave answered 404 says the room was closed`() = test(
        FakeRoomLink(detail(isOwner = false)).apply { leave = ApiResult.Failure(AppError.Api("room not found", 404)) },
    ) { viewModel, _ ->
        viewModel.requestLeave()
        viewModel.confirmLeave()
        runCurrent()

        assertEquals(listOf(7L to "The watch room for Heat was closed."), gone)
    }

    @Test
    fun `someone invited elsewhere while Edit invitees is open is kept by a save`() = test(
        FakeRoomLink(detail()).apply { invite = ApiResult.Success(listOf(maria, ana)) },
    ) { viewModel, link ->
        link.emit(snapshot(paused = true, position = 0.0, connected = listOf(1)))
        runCurrent()
        viewModel.editInvitees()
        runCurrent()
        assertEquals(setOf(2L), viewModel.uiState.value.editing!!.selected)

        link.room = detail().copy(members = listOf(me, maria, ana))
        link.emit(WatchRoomServerEvent(type = WatchRoomEventType.MembersChanged, members = listOf(me, maria, ana)))
        runCurrent()

        val editing = viewModel.uiState.value.editing!!
        assertEquals(setOf(2L, 3L), editing.initial)
        assertEquals(setOf(2L, 3L), editing.selected)
        assertFalse(editing.changed)
    }

    @Test
    fun `a guest who left while the TV slept drops out of an open Edit invitees`() = test(
        FakeRoomLink(detail().copy(members = listOf(me, maria, ana))).apply {
            invite = ApiResult.Success(listOf(maria, ana))
        },
    ) { viewModel, link ->
        link.emit(snapshot(paused = true, position = 0.0, connected = listOf(1)))
        runCurrent()
        viewModel.editInvitees()
        runCurrent()
        viewModel.toggleInvitee(3)
        assertEquals(setOf(2L), viewModel.uiState.value.editing!!.selected)

        viewModel.onBackground()
        runCurrent()
        // Maria left while the socket was down; no event reached this TV.
        link.room = detail().copy(members = listOf(me, ana))
        viewModel.onForeground()
        runCurrent()
        link.emit(snapshot(paused = true, position = 0.0, connected = listOf(1)))
        runCurrent()

        val editing = viewModel.uiState.value.editing!!
        assertEquals(setOf(3L), editing.initial)
        // The owner's own uncheck of Ana stands; Maria is no longer there to invite back.
        assertEquals(emptySet<Long>(), editing.selected)
    }
}

