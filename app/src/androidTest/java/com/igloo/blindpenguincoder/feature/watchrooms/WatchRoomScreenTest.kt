package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.closedWatchRoomState
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.navigation.IglooDestination
import com.igloo.blindpenguincoder.focusAndPress
import com.igloo.blindpenguincoder.inertWatchRoomActions
import com.igloo.blindpenguincoder.openDestination
import com.igloo.blindpenguincoder.playback.model.RoomPlayhead
import com.igloo.blindpenguincoder.playback.model.VideoPlayRequest
import com.igloo.blindpenguincoder.pressBack
import com.igloo.blindpenguincoder.testWatchRoomPage
import com.igloo.blindpenguincoder.testWatchRoomState
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A watch room's screen and the trips through it (design-system.md section 11.10.1): a card press
 * opens it over the pane, Watch opens the player over it, and each Back steps out one layer with
 * focus landing on the control that led in. A room that goes away on its own takes its player
 * with it.
 */
@RunWith(AndroidJUnit4::class)
class WatchRoomScreenTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var room by mutableStateOf(closedWatchRoomState)
    private val selected = mutableListOf<Long>()
    private var leaves = 0
    private var watches = 0
    private var closeRequests = 0
    private var closeDismissals = 0
    private val roomPlayRequests = MutableSharedFlow<VideoPlayRequest>(extraBufferCapacity = 1)

    /** The Watch Rooms pane, with room 1 opening over it the way the view model opens it. */
    private fun setContent(opened: WatchRoomUiState = testWatchRoomState()) {
        composeRule.setContent {
            IglooTheme {
                TestIglooApp(
                    watchRoom = room,
                    watchRoomActions = inertWatchRoomActions.copy(
                        onWatch = { watches += 1 },
                        onRequestClose = {
                            closeRequests += 1
                            room = room.copy(confirmingClose = true)
                        },
                        onDismissClose = {
                            closeDismissals += 1
                            room = room.copy(confirmingClose = false)
                        },
                    ),
                    onRoomSelected = { id ->
                        selected += id
                        room = opened
                    },
                    onLeaveRoom = {
                        leaves += 1
                        room = closedWatchRoomState
                    },
                    roomPlayRequests = roomPlayRequests,
                )
            }
        }
        composeRule.openDestination(IglooDestination.WatchRooms)
    }

    private fun openRoom(opened: WatchRoomUiState = testWatchRoomState()) {
        setContent(opened)
        composeRule.focusAndPress("watch_room_card_1")
    }

    @Test
    fun aCardPressOpensItsRoomWithWatchFocused() {
        openRoom()

        assertEquals(listOf(1L), selected)
        composeRule.onNodeWithTag("watch_room_layer").assertExists()
        composeRule.onNodeWithTag("watch_room_watch").assertIsFocused()
        composeRule.onNodeWithText("Paused at 0:00").assertExists()
    }

    @Test
    fun theOwnerHasAnInertEditAndAClose() {
        openRoom()

        composeRule.onNodeWithTag("watch_room_edit")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        composeRule.onNodeWithTag("watch_room_close").assertHasClickAction()
        composeRule.onNodeWithTag("watch_room_watch").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("watch_room_edit").assertIsFocused()
        composeRule.onNodeWithTag("watch_room_edit").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("watch_room_close").assertIsFocused()
    }

    @Test
    fun aGuestHasOnlyWatch() {
        openRoom(testWatchRoomState(page = testWatchRoomPage(isOwner = false)))

        composeRule.onNodeWithTag("watch_room_edit").assertDoesNotExist()
        composeRule.onNodeWithTag("watch_room_close").assertDoesNotExist()
    }

    @Test
    fun watchWaitsForTheRoomsState() {
        openRoom(testWatchRoomState(playhead = null))

        val watch = composeRule.onNodeWithTag("watch_room_watch")
        watch.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        watch.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Connecting to the room"))
        composeRule.onNodeWithText("Connecting…").assertExists()
    }

    @Test
    fun thePeopleAreOneStopBelowTheActions() {
        openRoom(testWatchRoomState(connected = listOf(1)))

        composeRule.onNodeWithTag("watch_room_watch").performKeyInput { pressKey(Key.DirectionDown) }

        composeRule.onNodeWithTag("watch_room_people")
            .assertIsFocused()
            .assertContentDescriptionEquals(
                "People in this room: Jose (you), host, watching. Maria, away",
            )
    }

    @Test
    fun backLeavesTheRoomAndLandsOnTheCardThatOpenedIt() {
        openRoom()

        composeRule.pressBack()

        assertEquals(1, leaves)
        composeRule.onNodeWithTag("watch_room_layer").assertDoesNotExist()
        composeRule.onNodeWithTag("watch_room_card_1").assertIsFocused()
    }

    @Test
    fun watchOpensThePlayerAndItsBackReturnsToWatch() {
        openRoom(testWatchRoomState(playhead = RoomPlayhead(paused = false, positionSec = 600.0, atMs = 0)))

        composeRule.focusAndPress("watch_room_watch")
        assertEquals(1, watches)
        roomPlayRequests.tryEmit(testWatchRoomPage().playRequest.copy(resumeAtSec = 600.0))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("movie_player").assertExists()
        composeRule.onNodeWithTag("movie_room_presence").assertExists()
        // Still loading, so Back closes rather than hiding the chrome.
        composeRule.pressBack()
        composeRule.onNodeWithTag("movie_player").assertDoesNotExist()
        composeRule.onNodeWithTag("watch_room_watch").assertIsFocused()
        assertEquals("the room stays open under its player", 0, leaves)
    }

    @Test
    fun aRoomThatGoesAwayTakesItsPlayerAndLandsOnThePane() {
        openRoom(testWatchRoomState(playhead = RoomPlayhead(paused = false, positionSec = 600.0, atMs = 0)))
        composeRule.focusAndPress("watch_room_watch")
        roomPlayRequests.tryEmit(testWatchRoomPage().playRequest)
        composeRule.waitForIdle()

        // The owner closed it: the view model leaves on its own.
        room = closedWatchRoomState
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("movie_player").assertDoesNotExist()
        composeRule.onNodeWithTag("watch_room_layer").assertDoesNotExist()
        composeRule.onNodeWithTag("watch_room_card_1").assertIsFocused()
    }

    @Test
    fun closeAsksFirstAndBackOnlyDismissesTheQuestion() {
        openRoom()

        composeRule.focusAndPress("watch_room_close")
        assertEquals(1, closeRequests)
        composeRule.onNodeWithText("Close this watch room?").assertExists()
        composeRule.pressBack()

        assertEquals(1, closeDismissals)
        assertEquals(0, leaves)
        composeRule.onNodeWithTag("watch_room_close").assertIsFocused()
    }
}
