package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.error.AppError
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.withServerAddress
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.CreateWatchRoomRequest
import com.igloo.blindpenguincoder.data.repository.WatchRoomRepository
import com.igloo.blindpenguincoder.feature.auth.toFailureNotice
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import com.igloo.blindpenguincoder.feature.movies.WatchRoomPresets
import com.igloo.blindpenguincoder.playback.hls.capacityRetryDelayMs
import com.igloo.blindpenguincoder.playback.model.HLS_WAITING_FOR_CAPACITY_MESSAGE
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WatchTogetherUiState(
    /** The movie the dialog makes a room for; null while it has nothing to make. */
    val movieId: Long? = null,
    /** The dialog is on screen; a creation can outlive it (section 9.3). */
    val shown: Boolean = false,
    val title: String = "",
    val presets: WatchRoomPresets? = null,
    /** Everyone the room can invite; an empty load is nobody else on the server. */
    val people: IglooRailState<RoomMemberUi> = IglooRailState.Loading,
    val selected: Set<Long> = emptySet(),
    /** The create request is out. Dismissal does not clear it — only its answer does. */
    val creating: Boolean = false,
    /** What the pending line says: creating, or waiting for the server to make room. */
    val pendingText: String = CREATING_ROOM,
    /** Create pressed with nobody chosen. */
    val hint: String? = null,
)

data class WatchTogetherActions(
    val onToggle: (userId: Long) -> Unit,
    val onCreate: () -> Unit,
    val onRetry: () -> Unit,
    val onDismiss: () -> Unit,
)

/** A room made from a movie's page, for the shell to open over that page. */
data class CreatedRoom(val movieId: Long, val roomId: Long)

/**
 * Watch Together on a movie's page (docs/design-system.md section 11.10.3): who to invite, then a
 * room with the page's current Playback Settings. The server can take half a minute to start a
 * room's stream, and refuses with 503 and a Retry-After while its transcode pool is full, which
 * is waited out on the HLS manifest's own budget. A creation that fails closes the dialog and
 * hands its reason to [onCreateFailed], for the page underneath: a dialog never carries an inline
 * error (section 9.3).
 */
class WatchTogetherViewModel(
    private val rooms: WatchRoomRepository,
    private val serverUrl: ServerUrlProvider,
    private val onCreateFailed: (notice: String) -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WatchTogetherUiState())
    val uiState: StateFlow<WatchTogetherUiState> = _uiState.asStateFlow()

    private val createdChannel = Channel<CreatedRoom>(Channel.CONFLATED)
    val createdRooms: Flow<CreatedRoom> = createdChannel.receiveAsFlow()

    private var peopleJob: Job? = null

    /** Opens the dialog for [movieId]; one already creating comes back as it was. */
    fun open(movieId: Long, title: String, presets: WatchRoomPresets) {
        if (_uiState.value.creating) {
            _uiState.update { it.copy(shown = true) }
            return
        }
        _uiState.value = WatchTogetherUiState(movieId = movieId, shown = true, title = title, presets = presets)
        loadPeople()
    }

    /** Cancel and Back. A creation under way carries on; its answer still lands. */
    fun dismiss() {
        if (_uiState.value.creating) {
            _uiState.update { it.copy(shown = false) }
            return
        }
        peopleJob?.cancel()
        _uiState.value = WatchTogetherUiState()
    }

    fun toggle(userId: Long) {
        _uiState.update {
            it.copy(
                selected = if (userId in it.selected) it.selected - userId else it.selected + userId,
                hint = null,
            )
        }
    }

    fun retry() = loadPeople()

    fun create() {
        val state = _uiState.value
        val movieId = state.movieId ?: return
        val presets = state.presets ?: return
        if (state.creating) return
        if (state.selected.isEmpty()) {
            _uiState.update { it.copy(hint = NOBODY_INVITED_HINT) }
            return
        }
        // In the order the list shows them, so the request reads the way the screen does.
        val invited = (state.people as? IglooRailState.Loaded)?.items.orEmpty()
            .map { it.id }
            .filter { it in state.selected }
        _uiState.update { it.copy(creating = true, pendingText = CREATING_ROOM) }
        viewModelScope.launch {
            val request = CreateWatchRoomRequest(
                movieId = movieId,
                mode = presets.mode.wireId,
                audioTrack = presets.audioTrack,
                subtitleTrack = presets.subtitleTrack,
                invitedUserIds = invited,
            )
            var capacityWaits = 0
            while (true) {
                when (val result = rooms.create(request)) {
                    is ApiResult.Success -> {
                        _uiState.value = WatchTogetherUiState()
                        createdChannel.trySend(CreatedRoom(movieId, result.value))
                        return@launch
                    }
                    is ApiResult.Failure -> {
                        val busy = (result.error as? AppError.Api)?.takeIf { it.status == SERVER_FULL_STATUS }
                        val wait = busy?.let { capacityRetryDelayMs(++capacityWaits, it.retryAfterSec) }
                        if (wait == null) {
                            _uiState.value = WatchTogetherUiState()
                            onCreateFailed(result.error.toFailureNotice("create the watch room"))
                            return@launch
                        }
                        _uiState.update { it.copy(pendingText = HLS_WAITING_FOR_CAPACITY_MESSAGE) }
                        delay(wait)
                        _uiState.update { it.copy(pendingText = CREATING_ROOM) }
                    }
                }
            }
        }
    }

    private fun loadPeople() {
        peopleJob?.cancel()
        _uiState.update { it.copy(people = IglooRailState.Loading) }
        peopleJob = viewModelScope.launch {
            val result = rooms.inviteUsers().withServerAddress(serverUrl)
            _uiState.update { state ->
                state.copy(
                    people = when (result) {
                        is ApiResult.Success -> {
                            val (users, server) = result.value
                            IglooRailState.Loaded(users.map { it.toRoomMemberUi(server) })
                        }
                        is ApiResult.Failure -> IglooRailState.Error(result.error.toLibraryDisplayMessage())
                    },
                )
            }
        }
    }
}

fun WatchTogetherViewModel.actions() = WatchTogetherActions(
    onToggle = ::toggle,
    onCreate = ::create,
    onRetry = ::retry,
    onDismiss = ::dismiss,
)

/** Watch Together pressed before the page knows what the movie's file holds. */
internal const val WATCH_TOGETHER_UNAVAILABLE_NOTICE =
    "Watch Together needs this movie's track details, which haven't loaded. Try again in a moment."

/** The web's words, on the same press: Create with nobody chosen. */
internal const val NOBODY_INVITED_HINT = "Select at least one person to invite."

private const val CREATING_ROOM = "Creating room…"
private const val SERVER_FULL_STATUS = 503
