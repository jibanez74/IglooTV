package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.withServerAddress
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.WatchRoomClientEvent
import com.igloo.blindpenguincoder.data.model.WatchRoomCommandType
import com.igloo.blindpenguincoder.data.model.WatchRoomEventType
import com.igloo.blindpenguincoder.data.repository.MovieRepository
import com.igloo.blindpenguincoder.data.repository.RoomSocket
import com.igloo.blindpenguincoder.data.repository.WatchRoomLink
import com.igloo.blindpenguincoder.feature.auth.toFailureNotice
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.feature.player.PlayerRoom
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.feature.shared.ReadOnlyDetailsLoader
import com.igloo.blindpenguincoder.playback.model.TransportCommand
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WatchRoomUiState(
    /** The room on screen; null while no room is open. */
    val openRoomId: Long? = null,
    val details: DetailsState<WatchRoomPageUi> = DetailsState.Loading,
    /** The room as this TV knows it: the link, where the room is, and who is connected. */
    val sync: WatchRoomSync,
    /** The player's opening intent, decided at the Watch press. */
    val startPlaying: Boolean = false,
    /**
     * This TV is starting a paused room it has to itself: the room's clock starts at this
     * player's first frame, not at the press, so until then the player has no room to follow.
     */
    val startingAlone: Boolean = false,
    /** The owner's Close room confirmation is up. */
    val confirmingClose: Boolean = false,
    /** The close request is out. */
    val closing: Boolean = false,
    /** An invitee's Leave room confirmation is up. */
    val confirmingLeave: Boolean = false,
    /** The leave request is out. */
    val leaving: Boolean = false,
    /** The owner's Edit invitees dialog, while it is up. */
    val editing: EditInviteesUi? = null,
    /** A close, leave or edit that failed, said on the page. */
    val notice: String? = null,
) {
    /** A dialog covers the room: its own Back answers first. */
    val dialogOpen: Boolean get() = confirmingClose || confirmingLeave || editing != null
}

/** The owner's Edit invitees dialog: who can be invited, and who is checked. */
data class EditInviteesUi(
    val people: IglooRailState<RoomMemberUi> = IglooRailState.Loading,
    val selected: Set<Long>,
    /** The invitees the room has now: Save is inert until the checks differ. */
    val initial: Set<Long>,
    /** The save request is out. */
    val saving: Boolean = false,
) {
    val changed: Boolean get() = selected != initial
}

/** What the room screen and the player in it need from the room's view model. */
data class WatchRoomActions(
    val onWatch: () -> Unit,
    val onRetry: () -> Unit,
    val onRequestClose: () -> Unit,
    val onDismissClose: () -> Unit,
    val onConfirmClose: () -> Unit,
    val onRequestLeave: () -> Unit = {},
    val onDismissLeave: () -> Unit = {},
    val onConfirmLeave: () -> Unit = {},
    val onEditInvitees: () -> Unit = {},
    val onToggleInvitee: (userId: Long) -> Unit = {},
    val onSaveInvitees: () -> Unit = {},
    val onRetryInvitees: () -> Unit = {},
    val onDismissEdit: () -> Unit = {},
    /** The screen came on screen with the app, or went off it with the app. */
    val onForeground: () -> Unit,
    val onBackground: () -> Unit,
    val onTransport: (command: TransportCommand, positionSec: Double) -> Unit,
    val onPlaybackStarted: (positionSec: Double) -> Unit,
    /** The clock the room's playhead is read on. */
    val clock: () -> Long,
)

/**
 * One watch room (docs/design-system.md section 11.10.1), from opening its screen to leaving it.
 * Single-slot like the details pages: [open] replaces whatever room was up, [leave] empties it.
 *
 * The room's screen counts as being in the room: [open] confirms membership and connects to its
 * socket at once, so the others see this viewer arrive and the screen can say who is there and
 * whether it is playing. The link reconnects on the web's schedule for as long as the room is
 * open and the app is in the foreground; going to the background disconnects, as the web's page
 * hide does. A room that turns out to be gone — closed by its owner, its movie deleted, or no
 * longer this user's — is handed to [onRoomGone] with what to tell the user, and left.
 */
class WatchRoomViewModel(
    private val link: WatchRoomLink,
    private val movies: MovieRepository,
    private val serverUrl: ServerUrlProvider,
    selfId: Long,
    private val onRoomGone: (roomId: Long, notice: String?) -> Unit,
    /** A monotonic clock; the room's playhead is read on it. */
    val clock: () -> Long,
) : ViewModel() {

    private val loader = ReadOnlyDetailsLoader(
        scope = viewModelScope,
        createState = { id: Long? -> PageState(id) },
        openId = PageState::id,
        details = PageState::details,
        withDetails = { state, details -> state.copy(details = details) },
        fetch = ::fetchPage,
        toUi = { it },
    )
    private val room = MutableStateFlow(WatchRoomUiState(sync = WatchRoomSync(selfId)))

    val uiState: StateFlow<WatchRoomUiState> = combine(room, loader.uiState) { room, page ->
        room.copy(openRoomId = page.id, details = page.details)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, room.value)

    private val playRequestChannel = Channel<VideoPlayRequest>(Channel.CONFLATED)

    /** The Watch press's player request, for the shell to open. */
    val playRequests: Flow<VideoPlayRequest> = playRequestChannel.receiveAsFlow()

    private var connectJob: Job? = null
    private var inviteesJob: Job? = null
    private var socket: RoomSocket? = null
    private val sendLock = Mutex()
    private var heldSeekJob: Job? = null
    private var heldSeekSec: Double? = null
    private var foreground = true

    fun open(roomId: Long) {
        leave()
        loader.open(roomId)
        if (foreground) connect(roomId)
    }

    /** Back from the room: the socket closes, which is how the others learn this viewer left. */
    fun leave() {
        disconnect()
        loader.close()
        room.value = WatchRoomUiState(sync = WatchRoomSync(room.value.sync.selfId))
    }

    fun retry() = loader.retry()

    fun onForeground() {
        foreground = true
        val id = loader.uiState.value.id ?: return
        if (connectJob?.isActive != true) connect(id)
    }

    fun onBackground() {
        foreground = false
        disconnect()
    }

    /**
     * Watch: the player opens where the room is. A playing room is joined playing; a paused room
     * with others in it is joined paused, so this press does not start it for everyone; a paused
     * room this viewer has to themselves starts, since there is nobody to wait for.
     */
    fun watch() {
        val page = (loader.uiState.value.details as? DetailsState.Loaded)?.value ?: return
        val state = room.value
        val playhead = state.sync.playhead ?: return
        val startingAlone = playhead.paused && state.sync.alone
        room.update { it.copy(startPlaying = !playhead.paused || startingAlone, startingAlone = startingAlone) }
        playRequestChannel.trySend(
            page.playRequest.copy(resumeAtSec = playhead.positionAt(clock()).takeIf { it > 0.0 }),
        )
    }

    /**
     * A play, pause or seek this viewer made in the player. A run of seeks — a held Left or Right
     * — goes out as one once it settles, so the others are not dragged through every step; a
     * play or pause sends the pending seek first.
     */
    fun onTransport(command: TransportCommand, positionSec: Double) {
        if (command is TransportCommand.SeekTo) {
            heldSeekSec = positionSec
            heldSeekJob?.cancel()
            heldSeekJob = viewModelScope.launch {
                delay(ROOM_SEEK_SETTLE_MS)
                sendHeldSeek()
            }
            return
        }
        sendHeldSeek()
        send(if (command == TransportCommand.Play) WatchRoomCommandType.Play else WatchRoomCommandType.Pause, positionSec)
    }

    /** The player's first frame: a viewer starting a room alone starts its clock now. */
    fun onPlaybackStarted(positionSec: Double) {
        if (!room.value.startingAlone) return
        room.update { it.copy(startingAlone = false) }
        send(WatchRoomCommandType.Play, positionSec)
    }

    fun requestClose() {
        if (room.value.closing) return
        room.update { it.copy(confirmingClose = true, notice = null) }
    }

    fun dismissClose() {
        if (room.value.closing) return
        room.update { it.copy(confirmingClose = false) }
    }

    fun confirmClose() {
        val id = loader.uiState.value.id ?: return
        if (room.value.closing) return
        room.update { it.copy(closing = true) }
        viewModelScope.launch {
            when (val result = link.delete(id)) {
                is ApiResult.Success -> roomGone(id, closedNotice())
                // Already gone — its movie deleted, or closed on another device: the same end.
                is ApiResult.Failure if result.error.isNotFound() -> roomGone(id, closedNotice())
                is ApiResult.Failure -> room.update {
                    it.copy(
                        closing = false,
                        confirmingClose = false,
                        notice = result.error.toFailureNotice("close the room"),
                    )
                }
            }
        }
    }

    fun requestLeave() {
        if (room.value.leaving) return
        room.update { it.copy(confirmingLeave = true, notice = null) }
    }

    fun dismissLeave() {
        if (room.value.leaving) return
        room.update { it.copy(confirmingLeave = false) }
    }

    /** An invitee leaves for good: the room drops off their list on every device. */
    fun confirmLeave() {
        val id = loader.uiState.value.id ?: return
        if (room.value.leaving) return
        room.update { it.copy(leaving = true) }
        viewModelScope.launch {
            when (val result = link.leave(id)) {
                is ApiResult.Success -> roomGone(id, leftNotice())
                // No longer a member, or the room is gone: either way there is nothing to leave.
                is ApiResult.Failure if result.error.isRoomGone() -> roomGone(id, leftNotice())
                is ApiResult.Failure -> room.update {
                    it.copy(
                        leaving = false,
                        confirmingLeave = false,
                        notice = result.error.toFailureNotice("leave the room"),
                    )
                }
            }
        }
    }

    /** The owner's Edit invitees: the room's invitees checked, everyone else on the server listed. */
    fun editInvitees() {
        val page = (loader.uiState.value.details as? DetailsState.Loaded)?.value ?: return
        if (!page.isOwner || room.value.editing != null) return
        val invitees = page.members.map { it.id }.filter { it != page.ownerId }.toSet()
        room.update { it.copy(editing = EditInviteesUi(selected = invitees, initial = invitees), notice = null) }
        loadInvitees()
    }

    fun retryInvitees() = loadInvitees()

    fun toggleInvitee(userId: Long) {
        room.update { state ->
            val editing = state.editing?.takeUnless { it.saving } ?: return@update state
            val selected = if (userId in editing.selected) editing.selected - userId else editing.selected + userId
            state.copy(editing = editing.copy(selected = selected))
        }
    }

    fun dismissEdit() {
        if (room.value.editing?.saving == true) return
        inviteesJob?.cancel()
        room.update { it.copy(editing = null) }
    }

    /**
     * Saves the checked people as the room's invitees. A failure closes the dialog and says why on
     * the page: a dialog never carries an inline error (section 9.3).
     */
    fun saveInvitees() {
        val id = loader.uiState.value.id ?: return
        val editing = room.value.editing ?: return
        if (editing.saving || !editing.changed) return
        // In the order the list shows them, so the request reads the way the screen does.
        val listed = (editing.people as? IglooRailState.Loaded)?.items.orEmpty().map { it.id }
        val invited = listed.filter { it in editing.selected } + (editing.selected - listed.toSet())
        room.update { it.copy(editing = editing.copy(saving = true)) }
        viewModelScope.launch {
            val result = link.updateMembers(id, invited)
            if (loader.uiState.value.id != id) return@launch
            when (result) {
                is ApiResult.Success -> {
                    room.update { it.copy(editing = null) }
                    loader.refresh()
                }
                is ApiResult.Failure if result.error.isNotFound() -> roomGone(id, closedNotice())
                is ApiResult.Failure -> room.update {
                    it.copy(editing = null, notice = result.error.toFailureNotice("update the invitees"))
                }
            }
        }
    }

    private fun loadInvitees() {
        inviteesJob?.cancel()
        room.update { state -> state.copy(editing = state.editing?.copy(people = IglooRailState.Loading)) }
        inviteesJob = viewModelScope.launch {
            val result = link.inviteUsers().withServerAddress(serverUrl)
            val people = when (result) {
                is ApiResult.Success -> {
                    val (users, server) = result.value
                    IglooRailState.Loaded(users.map { it.toRoomMemberUi(server) })
                }
                is ApiResult.Failure -> IglooRailState.Error(result.error.toLibraryDisplayMessage())
            }
            room.update { state -> state.copy(editing = state.editing?.copy(people = people)) }
        }
    }

    private fun connect(roomId: Long) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            var attempt = 0
            // Whether a socket of this run has seen the room yet: the next snapshot is a reconnect.
            var connectedOnce = false
            while (true) {
                when (val joined = link.join(roomId)) {
                    is ApiResult.Failure -> when {
                        // The session itself is gone; the sign-in gate takes it from here.
                        joined.error == AppError.Unauthorized -> return@launch
                        joined.error.isRoomGone() -> {
                            roomGone(roomId, ROOM_UNAVAILABLE_NOTICE)
                            return@launch
                        }
                        else -> Unit
                    }
                    is ApiResult.Success -> try {
                        link.connect(roomId) { connected ->
                            socket = connected
                            connected.events.collect { event ->
                                when (event.type) {
                                    WatchRoomEventType.RoomDeleted -> {
                                        // Before the hang-up that follows reads as a dropped link.
                                        roomGone(roomId, closedNotice())
                                        return@collect
                                    }
                                    WatchRoomEventType.RoomAccessRevoked -> {
                                        // The server tells the leaver's own sockets before it
                                        // answers the leave: this viewer's leave, not a removal.
                                        roomGone(roomId, if (room.value.leaving) leftNotice() else removedNotice())
                                        return@collect
                                    }
                                    // The page re-reads itself: who is in the room, and the host's
                                    // line, come from the room's details.
                                    WatchRoomEventType.MembersChanged -> loader.refresh()
                                    else -> Unit
                                }
                                if (event.type == WatchRoomEventType.RoomSnapshot) {
                                    attempt = 0
                                    // Back after a drop: a member change made meanwhile never
                                    // reached this TV, so the page re-reads the room.
                                    if (connectedOnce) loader.refresh()
                                    connectedOnce = true
                                }
                                val step = room.value.sync.onEvent(event, clock())
                                room.update { state ->
                                    state.copy(
                                        sync = step.sync,
                                        // Someone else moved the room first: it is theirs to follow.
                                        startingAlone = state.startingAlone &&
                                            event.type != WatchRoomEventType.PlaybackChanged,
                                    )
                                }
                                step.send?.let { deliver(connected, it) }
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // A dropped link, a refused upgrade, or the server's own hang-up: all are
                        // retried, and a deletion has already left the room above.
                    } finally {
                        socket = null
                    }
                }
                room.update { it.copy(sync = it.sync.onDisconnected()) }
                delay(roomReconnectDelayMs(attempt++))
            }
        }
    }

    private fun disconnect() {
        inviteesJob?.cancel()
        heldSeekJob?.cancel()
        heldSeekJob = null
        heldSeekSec = null
        connectJob?.cancel()
        connectJob = null
        socket = null
        room.update { it.copy(sync = it.sync.onDisconnected()) }
    }

    private fun sendHeldSeek() {
        heldSeekJob?.cancel()
        heldSeekJob = null
        val target = heldSeekSec ?: return
        heldSeekSec = null
        send(WatchRoomCommandType.Seek, target)
    }

    private fun send(type: WatchRoomCommandType, positionSec: Double) {
        val step = room.value.sync.onLocalCommand(type, positionSec, clock())
        room.update { it.copy(sync = step.sync) }
        val command = step.send ?: return
        socket?.let { deliver(it, command) }
    }

    /** In the order sent: a press's send is under way before the next press's begins. */
    private fun deliver(target: RoomSocket, command: WatchRoomClientEvent) {
        viewModelScope.launch {
            sendLock.withLock {
                try {
                    target.send(command)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // The link is going down; the reconnect's snapshot settles the room.
                }
            }
        }
    }

    private fun roomGone(roomId: Long, notice: String?) {
        if (loader.uiState.value.id != roomId) return
        leave()
        onRoomGone(roomId, notice)
    }

    private fun closedNotice(): String = roomTitle()
        ?.let { "The watch room for $it was closed." } ?: "The watch room was closed."

    private fun leftNotice(): String = roomTitle()
        ?.let { "You left the watch room for $it." } ?: "You left the watch room."

    /** The owner removed this user, or they left on another device: the server does not say which. */
    private fun removedNotice(): String = roomTitle()
        ?.let { "You're no longer in the watch room for $it." } ?: "You're no longer in this watch room."

    private fun roomTitle(): String? = (loader.uiState.value.details as? DetailsState.Loaded)?.value?.title

    private suspend fun fetchPage(id: Long): ApiResult<WatchRoomPageUi> = coroutineScope {
        when (val detail = link.watchRoom(id)) {
            is ApiResult.Failure -> detail
            is ApiResult.Success -> {
                val room = detail.value
                val movie = async { movies.movieDetails(room.movieId) }
                val technical = async { movies.movieTechnicalDetails(room.movieId) }
                // The movie's own page only lends the backdrop and the runtime; without it the
                // room still plays. Its technical details say what the stream is, and it does not.
                val movieValue = (movie.await() as? ApiResult.Success)?.value?.movie
                when (val tech = technical.await().withServerAddress(serverUrl)) {
                    is ApiResult.Failure -> tech
                    is ApiResult.Success -> ApiResult.Success(
                        toWatchRoomPageUi(room, movieValue, tech.value.first, tech.value.second),
                    )
                }
            }
        }
    }

    private data class PageState(
        val id: Long?,
        val details: DetailsState<WatchRoomPageUi> = DetailsState.Loading,
    )
}

fun WatchRoomViewModel.actions() = WatchRoomActions(
    onWatch = ::watch,
    onRetry = ::retry,
    onRequestClose = ::requestClose,
    onDismissClose = ::dismissClose,
    onConfirmClose = ::confirmClose,
    onRequestLeave = ::requestLeave,
    onDismissLeave = ::dismissLeave,
    onConfirmLeave = ::confirmLeave,
    onEditInvitees = ::editInvitees,
    onToggleInvitee = ::toggleInvitee,
    onSaveInvitees = ::saveInvitees,
    onRetryInvitees = ::retryInvitees,
    onDismissEdit = ::dismissEdit,
    onForeground = ::onForeground,
    onBackground = ::onBackground,
    onTransport = ::onTransport,
    onPlaybackStarted = ::onPlaybackStarted,
    clock = clock,
)

/** 403 is the server's word for both a room that does not exist and one this user is not in. */
private fun AppError.isRoomGone() = this is AppError.Api && (status == 403 || status == 404)

/** The owner's own routes answer 404 for a room that is no longer there. */
private fun AppError.isNotFound() = this is AppError.Api && status == 404

/** The web's words for a room that is gone or was never this user's. */
internal const val ROOM_UNAVAILABLE_NOTICE = "This room no longer exists or you were not invited."

/** How long a run of seeks must stay quiet before it goes to the room as one. */
internal const val ROOM_SEEK_SETTLE_MS = 400L

/**
 * The room as the player in it sees it (section 11.10.2). While this viewer is starting a room
 * alone the player has no room to follow yet: its first frame starts the room's clock.
 */
fun WatchRoomUiState.toPlayerRoom(actions: WatchRoomActions) = PlayerRoom(
    playhead = sync.playhead.takeUnless { startingAlone },
    followGeneration = sync.followGeneration,
    notice = sync.notice,
    startPlaying = startPlaying,
    clock = actions.clock,
    onTransport = actions.onTransport,
    onPlaybackStarted = actions.onPlaybackStarted,
)
