package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.blindpenguincoder.core.error.ApiResult
import com.igloo.blindpenguincoder.core.network.ServerUrlProvider
import com.igloo.blindpenguincoder.core.network.withServerAddress
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.core.ui.orKeepContent
import com.igloo.blindpenguincoder.data.repository.WatchRoomRepository
import com.igloo.blindpenguincoder.feature.auth.toLibraryDisplayMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WatchRoomsUiState(
    /** An empty [IglooRailState.Loaded] is the empty scene, not an error. */
    val rooms: IglooRailState<WatchRoomUi> = IglooRailState.Loading,
    /** The Refresh button's request is out; the list stays on screen meanwhile. */
    val refreshing: Boolean = false,
    /** A Refresh that failed with rooms still on screen. */
    val notice: String? = null,
    /**
     * Bumped when the list the user asked for lands — the first load, a Refresh, a Retry. The
     * screen returns to the top and focus goes to the first room.
     */
    val contentGeneration: Int = 0,
    /**
     * Bumped when a re-read nobody pressed for lands over a loaded list. The user is left where
     * they are; focus moves only if the room it was on is gone.
     */
    val reconcileGeneration: Int = 0,
)

/** What the Watch Rooms pane needs from its view model. */
data class WatchRoomsActions(
    /** The pane came on screen: it was opened, or the app returned to the foreground on it. */
    val onShown: () -> Unit,
    val onRefresh: () -> Unit,
)

/**
 * The caller's watch rooms (docs/design-system.md section 11.10). The list is short and arrives
 * whole, so there is no paging: one request replaces it.
 *
 * The server pushes nothing when a room is created, so an invitation appears only on a re-read.
 * The pane asks for one whenever it comes on screen ([refresh]) and on its Refresh button
 * ([reload]); the host's start effect, which re-reads every library on each foreground, leaves
 * this list alone because it is only worth reading while someone is looking at it.
 */
class WatchRoomsViewModel(
    private val rooms: WatchRoomRepository,
    private val serverUrl: ServerUrlProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WatchRoomsUiState())
    val uiState: StateFlow<WatchRoomsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /**
     * The pane came on screen. A list already loaded stays put and is reconciled in place, and
     * a failure leaves it alone without a word: nobody asked, and a moment of bad wifi as the TV
     * wakes is not news. A request already out, whoever started it, answers for this one too.
     */
    fun refresh() {
        if (loadJob?.isActive == true) return
        load(userInitiated = false)
    }

    /**
     * The header's Refresh, and the error card's Retry. Whatever is on screen stays there while
     * the request is out — blanking to skeletons would dispose the node the user is focused on —
     * and a second press meanwhile is ignored. It supersedes a background read in flight, whose
     * answer would otherwise land without the focus move the press is owed.
     */
    fun reload() {
        if (_uiState.value.refreshing) return
        load(userInitiated = true)
    }

    private fun load(userInitiated: Boolean) {
        loadJob?.cancel()
        if (userInitiated) _uiState.update { it.copy(refreshing = true, notice = null) }
        loadJob = viewModelScope.launch {
            val result = rooms.watchRooms().withServerAddress(serverUrl)
            _uiState.update { state ->
                val hadRooms = state.rooms is IglooRailState.Loaded
                when (result) {
                    is ApiResult.Success -> {
                        val (list, server) = result.value
                        val replaces = userInitiated || !hadRooms
                        state.copy(
                            rooms = IglooRailState.Loaded(list.map { it.toWatchRoomUi(server) }),
                            refreshing = false,
                            notice = null,
                            contentGeneration = state.contentGeneration + if (replaces) 1 else 0,
                            reconcileGeneration = state.reconcileGeneration + if (replaces) 0 else 1,
                        )
                    }

                    is ApiResult.Failure -> {
                        val message = result.error.toLibraryDisplayMessage()
                        state.copy(
                            rooms = IglooRailState.Error(message).orKeepContent(state.rooms),
                            refreshing = false,
                            // With rooms still on screen the failure is over and Refresh is one
                            // press away: a notice, not an error card with a second Retry.
                            notice = if (userInitiated && hadRooms) message else state.notice,
                        )
                    }
                }
            }
        }
    }
}

fun WatchRoomsViewModel.actions() = WatchRoomsActions(onShown = ::refresh, onRefresh = ::reload)
