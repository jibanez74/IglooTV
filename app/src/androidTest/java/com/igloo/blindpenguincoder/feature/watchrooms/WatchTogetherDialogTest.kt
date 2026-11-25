package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.clickActionLabelled
import com.igloo.blindpenguincoder.closedWatchRoomState
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsUiState
import com.igloo.blindpenguincoder.feature.shared.DetailsState
import com.igloo.blindpenguincoder.pressBack
import com.igloo.blindpenguincoder.testMovieDetails
import com.igloo.blindpenguincoder.testWatchRoomState
import com.igloo.blindpenguincoder.testWatchTogetherState
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Watch Together through the real host wiring (design-system.md section 11.10.3): the movie
 * page's More menu asks for it, the dialog is a checklist with Cancel and Create, Back and Cancel
 * hand focus back to More, and the room it makes opens over the page, Back from it returning to
 * More.
 */
@RunWith(AndroidJUnit4::class)
class WatchTogetherDialogTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var together by mutableStateOf(WatchTogetherUiState())
    private var room by mutableStateOf(closedWatchRoomState)
    private var opens = 0
    private val toggled = mutableListOf<Long>()
    private var creates = 0
    private var retries = 0
    private val selectedRooms = mutableListOf<Long>()
    private var leaves = 0
    private var detailsCloses = 0
    private val createdRooms = MutableSharedFlow<CreatedRoom>(extraBufferCapacity = 1)

    /** Movie 1's page, with Watch Together opening as [opened] when the menu asks. */
    private fun setContent(opened: WatchTogetherUiState = testWatchTogetherState()) {
        composeRule.setContent {
            IglooTheme {
                TestIglooApp(
                    details = MovieDetailsUiState(openMovieId = 1, details = DetailsState.Loaded(testMovieDetails())),
                    watchTogether = together,
                    watchTogetherActions = WatchTogetherActions(
                        onToggle = { toggled += it },
                        onCreate = { creates += 1 },
                        onRetry = { retries += 1 },
                        onDismiss = { together = WatchTogetherUiState() },
                    ),
                    onOpenWatchTogether = {
                        opens += 1
                        together = opened
                    },
                    createdRooms = createdRooms,
                    watchRoom = room,
                    onRoomSelected = { id ->
                        selectedRooms += id
                        room = testWatchRoomState()
                    },
                    onLeaveRoom = {
                        leaves += 1
                        room = closedWatchRoomState
                    },
                    onCloseDetails = { detailsCloses += 1 },
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** More, then down to its second item, Watch Together. */
    private fun openDialog(opened: WatchTogetherUiState = testWatchTogetherState()) {
        setContent(opened)
        composeRule.onNodeWithTag("details_more").requestFocus()
        composeRule.onNodeWithTag("details_more").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("more_menu_item_0").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("more_menu_item_1").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
    }

    @Test
    fun theMenuItemOpensTheDialogOnTheFirstPerson() {
        openDialog()

        assertEquals(1, opens)
        composeRule.onNodeWithTag("watch_together_dialog").assertExists()
        composeRule.onNodeWithText(testWatchTogetherState().presets!!.settingsLine).assertExists()
        composeRule.onNodeWithTag("watch_together_invitee_2").assertIsFocused()
    }

    @Test
    fun eachPersonIsACheckboxWhosePressInvitesThem() {
        openDialog(testWatchTogetherState(selected = setOf(3)))

        val maria = composeRule.onNodeWithTag("watch_together_invitee_2")
        maria.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        maria.assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
        maria.assert(clickActionLabelled("Invite"))
        composeRule.onNodeWithTag("watch_together_invitee_3")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .assert(clickActionLabelled("Don't invite"))

        maria.performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(listOf(2L), toggled)
    }

    @Test
    fun downFromTheListReachesCancelThenCreate() {
        openDialog()

        composeRule.onNodeWithTag("watch_together_invitee_2").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("watch_together_invitee_3").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("watch_together_cancel").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        val create = composeRule.onNodeWithTag("watch_together_create").assertIsFocused()
        // The settings line is text TalkBack never reaches; Create carries it.
        create.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                testWatchTogetherState().presets!!.settingsLine,
            ),
        )
        create.performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, creates)
    }

    @Test
    fun theHintAndThePendingLineAreSaid() {
        openDialog(testWatchTogetherState(hint = NOBODY_INVITED_HINT))
        composeRule.onNodeWithTag("watch_together_hint").assertExists()

        together = testWatchTogetherState(selected = setOf(2), creating = true)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Creating room…").assertIsFocused()
        composeRule.onNodeWithTag("watch_together_create").assertDoesNotExist()
    }

    @Test
    fun peopleThatFailedToLoadOfferRetry() {
        openDialog(testWatchTogetherState(people = IglooRailState.Error("The server could not be reached.")))

        composeRule.onNodeWithText("Couldn't load people: The server could not be reached.").assertExists()
        composeRule.onNodeWithTag("watch_together_cancel").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("watch_together_create").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals(1, retries)
    }

    @Test
    fun backDismissesAndLandsOnMore() {
        openDialog()

        composeRule.pressBack()

        composeRule.onNodeWithTag("watch_together_dialog").assertDoesNotExist()
        composeRule.onNodeWithTag("details_more").assertIsFocused()
        composeRule.onNodeWithTag("movie_details").assertExists()
    }

    @Test
    fun theNewRoomOpensOverThePageAndBackReturnsToMore() {
        openDialog()
        together = WatchTogetherUiState()
        createdRooms.tryEmit(CreatedRoom(movieId = 1, roomId = 1))
        composeRule.waitForIdle()

        assertEquals(listOf(1L), selectedRooms)
        composeRule.onNodeWithTag("watch_room_layer").assertExists()
        composeRule.onNodeWithTag("watch_room_watch").assertIsFocused()

        composeRule.pressBack()

        assertEquals(1, leaves)
        composeRule.onNodeWithTag("watch_room_layer").assertDoesNotExist()
        composeRule.onNodeWithTag("details_more").assertIsFocused()
    }

    @Test
    fun aRoomForAPageThatIsGoneStaysInTheList() {
        setContent()

        createdRooms.tryEmit(CreatedRoom(movieId = 99, roomId = 1))
        composeRule.waitForIdle()

        assertEquals(emptyList<Long>(), selectedRooms)
    }

    // A room that finishes creating while the page's More menu is up would open over it, and the
    // menu, still composed underneath, would take the room's first Back. It stays in the list.
    @Test
    fun aRoomFinishedWhileMoreIsOpenDoesNotOpenOverIt() {
        setContent()
        composeRule.onNodeWithTag("details_more").requestFocus()
        composeRule.onNodeWithTag("details_more").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("more_menu_item_0").assertIsFocused()

        createdRooms.tryEmit(CreatedRoom(movieId = 1, roomId = 1))
        composeRule.waitForIdle()

        assertEquals(emptyList<Long>(), selectedRooms)
        composeRule.onNodeWithTag("watch_room_layer").assertDoesNotExist()
        composeRule.onNodeWithTag("more_menu_item_0").assertIsFocused()
    }

    // A creation still running for another movie keeps its dialog `shown`, but it is drawn only
    // over its own movie: this page's Back must still close the page, not fall through.
    @Test
    fun aCreationForAnotherMovieLeavesThisPagesBackAlone() {
        together = testWatchTogetherState().copy(movieId = 2, shown = true, creating = true)
        setContent()
        composeRule.onNodeWithTag("watch_together_dialog").assertDoesNotExist()

        composeRule.pressBack()

        assertEquals(1, detailsCloses)
    }
}

