package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEqualTo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.navigation.IglooDestination
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.inertWatchRoomsActions
import com.igloo.blindpenguincoder.openDestination
import com.igloo.blindpenguincoder.testWatchRoom
import com.igloo.blindpenguincoder.testWatchRooms
import com.igloo.blindpenguincoder.testWatchRoomsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Watch Rooms pane's focus contract (design-system.md sections 6.3 and 11.10): every state
 * anchors the content pane — on the Refresh button when there are no rooms — the edges are
 * wired, and a list changing underneath moves focus only when it has to.
 */
@RunWith(AndroidJUnit4::class)
class WatchRoomsBehaviorTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var roomsState by mutableStateOf(testWatchRoomsState())
    private var refreshCalls = 0
    private var columns = 0

    private fun setContent(initial: WatchRoomsUiState = testWatchRoomsState()) {
        roomsState = initial
        refreshCalls = 0
        composeRule.setContent {
            IglooTheme {
                columns = IglooTheme.layout.roomGridColumns
                TestIglooApp(
                    watchRooms = roomsState,
                    watchRoomsActions = WatchRoomsActions(
                        onShown = {},
                        onRefresh = { refreshCalls += 1 },
                    ),
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.openDestination(IglooDestination.WatchRooms)
    }

    private fun show(state: WatchRoomsUiState) {
        roomsState = state
        composeRule.waitForIdle()
    }

    private fun card(id: Long) = composeRule.onNodeWithTag("watch_room_card_$id")
    private fun refresh() = composeRule.onNodeWithContentDescription(REFRESH_ROOMS_SPOKEN)
    private fun skeleton() = composeRule.onNodeWithContentDescription("Loading watch rooms")
    private fun retry() = composeRule.onNodeWithContentDescription("Retry loading watch rooms")
    private fun railRow() = composeRule.onNodeWithContentDescription(IglooDestination.WatchRooms.label)

    private val empty = testWatchRoomsState(rooms = IglooRailState.Loaded(emptyList()))
    private val loading = testWatchRoomsState(rooms = IglooRailState.Loading)
    private val failed = testWatchRoomsState(rooms = IglooRailState.Error("Something went wrong"))

    // --- the anchor in every state ------------------------------------------------------------

    @Test
    fun theFirstRoomHoldsThePanesEntryAnchor() {
        setContent()

        composeRule.onNodeWithTag("watch_rooms_grid").assertIsDisplayed()
        card(1).assertIsFocused()
    }

    /** The empty scene offers nothing to do, so the anchor is the one control that does. */
    @Test
    fun withNoRoomsTheRefreshButtonHoldsTheAnchor() {
        setContent(empty)

        composeRule.onNodeWithTag("watch_rooms_empty").assertIsDisplayed()
        refresh().assertIsFocused()
    }

    @Test
    fun aFailedFirstLoadPutsTheAnchorOnItsRetry() {
        setContent(failed)

        retry().assertIsFocused()
        retry().performClick()
        assertEquals(1, refreshCalls)
    }

    /** A slow network must never trap the d-pad: the skeleton anchor still opens the spine. */
    @Test
    fun theLoadingSkeletonStillExitsToTheSpine() {
        setContent(loading)

        skeleton().assertIsFocused()
        skeleton().performKeyInput { pressKey(Key.DirectionLeft) }

        railRow().assertIsFocused()
    }

    @Test
    fun focusMovesToTheFirstRoomWhenTheListArrives() {
        setContent(loading)

        show(testWatchRoomsState(contentGeneration = 1))

        card(1).assertIsFocused()
    }

    @Test
    fun aFocusedSkeletonHandsFocusToRefreshWhenThereAreNoRooms() {
        setContent(loading)

        show(empty.copy(contentGeneration = 1))

        refresh().assertIsFocused()
    }

    @Test
    fun aFocusedSkeletonHandsFocusToTheErrorCardsRetry() {
        setContent(loading)

        show(failed)

        retry().assertIsFocused()
    }

    // --- d-pad geometry -----------------------------------------------------------------------

    @Test
    fun leftFromTheFirstColumnExitsToTheNavigationRail() {
        setContent()

        card(1).performKeyInput { pressKey(Key.DirectionLeft) }

        railRow().assertIsFocused()
    }

    /** The grid is the panel's right edge, at either column count. */
    @Test
    fun rightFromTheLastColumnStaysOnTheCard() {
        setContent()
        repeat(columns - 1) { index ->
            card(1L + index).performKeyInput { pressKey(Key.DirectionRight) }
        }
        val lastColumn = card(columns.toLong())
        lastColumn.assertIsFocused()

        lastColumn.performKeyInput { pressKey(Key.DirectionRight) }

        lastColumn.assertIsFocused()
    }

    @Test
    fun downWalksTheRowsAndStopsAtTheLast() {
        setContent()

        card(1).performKeyInput { pressKey(Key.DirectionDown) }
        card(1L + columns).assertIsFocused()

        // The fifth room is alone on the last row at two columns, and last at one.
        composeRule.onNodeWithTag("watch_rooms_grid").performKeyInput {
            repeat(testWatchRooms.size) { pressKey(Key.DirectionDown) }
        }
        card(testWatchRooms.last().id).assertIsFocused()
    }

    @Test
    fun refreshSitsOnePressAboveTheFirstRowAndReturnsToTheEntryCard() {
        setContent()

        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        refresh().assertIsFocused()

        refresh().performKeyInput { pressKey(Key.DirectionDown) }
        card(1).assertIsFocused()
    }

    @Test
    fun leftFromRefreshExitsToTheNavigationRailAndItsOtherEdgesArePinned() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        refresh().performKeyInput { pressKey(Key.DirectionUp) }
        refresh().assertIsFocused()
        refresh().performKeyInput { pressKey(Key.DirectionRight) }
        refresh().assertIsFocused()

        refresh().performKeyInput { pressKey(Key.DirectionLeft) }
        railRow().assertIsFocused()
    }

    /** Nothing below takes focus; unpinned, the search left the pane for the rail's footer. */
    @Test
    fun downFromRefreshStaysPutWhenThereAreNoRooms() {
        setContent(empty)

        refresh().performKeyInput { pressKey(Key.DirectionDown) }

        refresh().assertIsFocused()
    }

    // --- focus memory -------------------------------------------------------------------------

    @Test
    fun aRoundTripThroughHomeComesBackToTheRoomTheUserLeft() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionDown) }
        val remembered = card(1L + columns)
        remembered.assertIsFocused()

        composeRule.openDestination(IglooDestination.Home)
        composeRule.openDestination(IglooDestination.WatchRooms)

        remembered.assertIsFocused()
    }

    // --- Refresh ------------------------------------------------------------------------------

    /** Focusable while refreshing: disabling it would remove the focused node from the tree. */
    @Test
    fun refreshStaysAFocusTargetWhileItIsRefreshing() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        refresh().performClick()
        assertEquals(1, refreshCalls)

        show(testWatchRoomsState(refreshing = true))

        refresh().assertIsFocused()
    }

    /** The label swaps under the focus ring; the button must not resize with it. */
    @Test
    fun refreshKeepsItsWidthWhileTheLabelSaysRefreshing() {
        setContent()
        val resting = refresh().getUnclippedBoundsInRoot()

        show(testWatchRoomsState(refreshing = true))

        assertEquals(resting, refresh().getUnclippedBoundsInRoot())
    }

    @Test
    fun aRefreshThatLandsMovesFocusToTheNewestRoom() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        refresh().assertIsFocused()

        show(
            testWatchRoomsState(
                rooms = IglooRailState.Loaded(listOf(testWatchRoom(9)) + testWatchRooms),
                contentGeneration = 1,
            ),
        )

        card(9).assertIsFocused()
    }

    @Test
    fun aRefreshThatFindsTheFirstRoomMovesFocusOffTheButton() {
        setContent(empty)
        refresh().assertIsFocused()

        show(testWatchRoomsState(contentGeneration = 1))

        card(1).assertIsFocused()
    }

    @Test
    fun aRefreshThatFailsLeavesFocusOnTheButton() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        show(testWatchRoomsState(notice = "Something went wrong"))

        refresh().assertIsFocused()
        composeRule.onNodeWithTag("watch_rooms_notice").assertIsDisplayed()
    }

    // --- a re-read nobody pressed for ---------------------------------------------------------

    @Test
    fun aBackgroundReadLeavesFocusOnARoomThatIsStillThere() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionDown) }
        val focused = card(1L + columns)
        focused.assertIsFocused()

        show(
            testWatchRoomsState(
                rooms = IglooRailState.Loaded(listOf(testWatchRoom(9)) + testWatchRooms),
                reconcileGeneration = 1,
            ),
        )

        focused.assertIsFocused()
    }

    @Test
    fun aBackgroundReadThatClosesTheFocusedRoomMovesFocusToTheFirst() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionDown) }
        val closed = 1L + columns
        card(closed).assertIsFocused()

        show(
            testWatchRoomsState(
                rooms = IglooRailState.Loaded(testWatchRooms.filterNot { it.id == closed }),
                reconcileGeneration = 1,
            ),
        )

        card(1).assertIsFocused()
    }

    @Test
    fun aBackgroundReadThatClosesTheLastRoomMovesFocusToRefresh() {
        setContent(testWatchRoomsState(rooms = IglooRailState.Loaded(testWatchRooms.take(1))))
        card(1).assertIsFocused()

        show(empty.copy(reconcileGeneration = 1))

        refresh().assertIsFocused()
    }

    // --- the card's size ----------------------------------------------------------------------

    private var posterHeight = Dp.Unspecified

    /** Two cards in one grid cell's width: a title that fits one line, and one that wraps. */
    private fun setCards(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                IglooTheme {
                    posterHeight = IglooTheme.layout.roomPosterWidth / IglooTheme.layout.posterAspect
                    Column(modifier = Modifier.width(368.dp)) {
                        WatchRoomCard(testWatchRoom(1), Modifier.testTag("short"))
                        WatchRoomCard(
                            testWatchRoom(1).copy(title = "Three Billboards Outside Ebbing, Missouri"),
                            Modifier.testTag("long"),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun cardHeight(tag: String) =
        composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot().height

    /** The poster's height holds a wrapped title, so the grid's rows stay even. */
    @Test
    fun aWrappedTitleLeavesTheCardAtItsPostersHeightAtTheDefaultFontScale() {
        setCards(fontScale = 1f)

        cardHeight("short").assertIsEqualTo(posterHeight, "one-line card")
        cardHeight("long").assertIsEqualTo(posterHeight, "two-line card")
    }

    /** That height is a minimum (section 2.6): larger text makes the card taller, never clipped. */
    @Test
    fun atTheLargestFontScaleAWrappedTitleGrowsTheCard() {
        setCards(fontScale = 1.3f)

        cardHeight("short").assertIsEqualTo(posterHeight, "one-line card")
        val grown = cardHeight("long")
        assertTrue(
            "the two-line card is $grown tall; it must outgrow its poster's $posterHeight",
            grown > posterHeight + 2.dp,
        )
    }

    // --- when the list is read ----------------------------------------------------------------

    /**
     * The pane reads its list when it comes on screen and again on every start while it is up —
     * and at no other time, which is why the effect is the pane's and not the host's.
     */
    @Test
    fun thePaneAsksForItsListWhenItComesOnScreenAndOnEveryStart() {
        var shown = 0
        val owner = ManualLifecycleOwner()
        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                IglooTheme {
                    WatchRoomsScreen(
                        state = testWatchRoomsState(),
                        actions = remember {
                            inertWatchRoomsActions.copy(onShown = { shown += 1 })
                        },
                        contentInset = PaddingValues(),
                        gridState = rememberLazyGridState(),
                        contentStartRequester = remember { FocusRequester() },
                        navigationRequester = remember { FocusRequester() },
                        lastFocusedId = null,
                        onItemFocused = {},
                        spokenAccessibilityEnabled = false,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, shown)

        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        composeRule.waitForIdle()
        assertEquals(1, shown)

        composeRule.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.waitForIdle()
        assertEquals(2, shown)
    }

    private class ManualLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
