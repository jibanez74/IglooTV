package com.igloo.blindpenguincoder.feature.watchrooms

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.navigation.IglooDestination
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.isHeading
import com.igloo.blindpenguincoder.isPoliteLiveRegion
import com.igloo.blindpenguincoder.openDestination
import com.igloo.blindpenguincoder.testWatchRoomsState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What TalkBack is told on the Watch Rooms pane (design-system.md sections 11.10 and 12): a room
 * is one stop that names no action it cannot perform, the count reports scale politely, and the
 * empty scene speaks through the button that holds focus in its place.
 */
@RunWith(AndroidJUnit4::class)
class WatchRoomsAccessibilityTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private fun setContent(state: WatchRoomsUiState = testWatchRoomsState()) {
        composeRule.setContent {
            IglooTheme { TestIglooApp(watchRooms = state) }
        }
        composeRule.waitForIdle()
        composeRule.openDestination(IglooDestination.WatchRooms)
    }

    private fun refresh() = composeRule.onNodeWithContentDescription(REFRESH_ROOMS_SPOKEN)
    private val empty = testWatchRoomsState(rooms = IglooRailState.Loaded(emptyList()))

    @Test
    fun theDestinationAnnouncesItselfThroughThePaneTitle() {
        setContent()

        composeRule.onNodeWithTag("content_pane").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Watch Rooms"),
        )
        composeRule.onNode(hasText("Watch Rooms") and isHeading()).assertExists()
    }

    /** Joining is not built: the card says who is in the room and offers nothing to press. */
    @Test
    fun eachRoomIsOneStopThatNamesNoAction() {
        setContent()

        val own = composeRule.onNodeWithTag("watch_room_card_1")
        own.assertContentDescriptionEquals(
            "Movie 1, hosted by you, 6 members: Jose, Maria, Jorge, Ana and 2 more",
        )
        own.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        own.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
        composeRule.onNodeWithTag("watch_room_card_2")
            .assertContentDescriptionEquals("Movie 2, hosted by Maria, 2 members: Maria, Jose")
        // The lines the card draws are the sentence's, not stops of their own.
        composeRule.onAllNodesWithText("Hosted by Maria").assertCountEquals(0)
    }

    @Test
    fun theCountReportsScalePolitely() {
        setContent()

        composeRule.onNodeWithTag("watch_rooms_count")
            .assertContentDescriptionEquals("5 watch rooms")
            .assert(isPoliteLiveRegion())
    }

    @Test
    fun theCountSaysSoWhileTheListIsOnItsWay() {
        setContent(testWatchRoomsState(rooms = IglooRailState.Loading))

        composeRule.onNodeWithTag("watch_rooms_count")
            .assertContentDescriptionEquals("Loading your watch rooms")
    }

    @Test
    fun theCountSaysSoWhenTheListCouldNotBeRead() {
        setContent(testWatchRoomsState(rooms = IglooRailState.Error("Something went wrong")))

        composeRule.onNodeWithTag("watch_rooms_count")
            .assertContentDescriptionEquals("Watch rooms unavailable")
    }

    @Test
    fun refreshNamesItsListAndItsAction() {
        setContent()

        refresh()
            .assertHasClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun refreshAnnouncesItsPendingStateWithoutLosingItsLabel() {
        setContent(testWatchRoomsState(refreshing = true))

        refresh().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Refreshing"),
        )
    }

    /**
     * A d-pad user's screen reader follows focus and would never visit the scene's text, so the
     * button that holds focus says it; the text itself is kept away from TalkBack so a reader
     * that does wander by geometry does not hear it twice.
     */
    @Test
    fun withNoRoomsRefreshSpeaksTheEmptySceneAndTheSceneStaysSilent() {
        setContent(empty)

        refresh().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, NO_ROOMS_SPOKEN),
        )
        for (line in listOf(NO_ROOMS_HEADING, NO_ROOMS_EXPLANATION, NO_ROOMS_HINT)) {
            composeRule.onNode(hasText(line), useUnmergedTree = true).assert(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility),
            )
        }
        composeRule.onNodeWithTag("watch_rooms_count").assertContentDescriptionEquals("0 watch rooms")
    }

    /** The scene's words give way to the pending state, then come back if nothing was found. */
    @Test
    fun withNoRoomsARefreshInFlightSaysRefreshing() {
        setContent(empty.copy(refreshing = true))

        refresh().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Refreshing"),
        )
    }
}
