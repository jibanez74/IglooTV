package com.igloo.blindpenguincoder.feature.home

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.testHomeMovies
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The home rail's focus contract: every rail state anchors the content pane, focus survives
 * state swaps, and the last-focused card is restored on re-entry (design-system.md sections
 * 6.3 and 11.3).
 */
@RunWith(AndroidJUnit4::class)
class HomeRailBehaviorTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val user = AuthUser(
        id = 1,
        name = "Jose",
        email = "jose@example.com",
        isAdmin = false,
        avatar = null,
        hasPin = false,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    private val movies = testHomeMovies

    private var railState by mutableStateOf<IglooRailState<HomeMovie>>(IglooRailState.Loading)
    private var retries = 0
    private var expandedWidth: Dp = Dp.Unspecified
    private var hostActivity: Activity? = null

    private fun setShellContent(initial: IglooRailState<HomeMovie>) {
        railState = initial
        retries = 0
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                expandedWidth = IglooTheme.layout.navRailExpandedWidth
                IglooApp(
                    user = user,
                    signOut = SignOutUiState(),
                    latestMovies = railState,
                    onRetryLatestMovies = { retries += 1 },
                    onMovieSelected = {},
                    onSwitchProfile = {},
                    onSignOut = {},
                    onSignOutConfirm = {},
                    onSignOutDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun card(id: Long) = composeRule.onNodeWithTag("poster_card_$id")

    private fun pressBack() {
        composeRule.runOnUiThread {
            (checkNotNull(hostActivity) as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun initialFocusLandsOnTheLoadingSkeleton() {
        setShellContent(IglooRailState.Loading)

        val skeleton = composeRule.onNodeWithContentDescription("Loading recently added movies")
        skeleton.assertIsFocused()

        // The skeleton anchor still opens the spine, so a slow network never traps the d-pad.
        skeleton.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
        composeRule.onNodeWithTag("navigation_rail").assertWidthIsEqualTo(expandedWidth)
    }

    @Test
    fun skeletonHandsFocusToTheFirstCardWhenContentArrives() {
        setShellContent(IglooRailState.Loading)
        composeRule.onNodeWithContentDescription("Loading recently added movies").assertIsFocused()

        railState = IglooRailState.Loaded(movies)
        composeRule.waitForIdle()

        card(1).assertIsFocused()
    }

    @Test
    fun focusNotInTheRailIsNotStolenWhenContentArrives() {
        setShellContent(IglooRailState.Loading)
        composeRule.onNodeWithContentDescription("Loading recently added movies")
            .performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()

        railState = IglooRailState.Loaded(movies)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
    }

    @Test
    fun spineReentryRestoresTheLastFocusedCard() {
        setShellContent(IglooRailState.Loaded(movies))
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).performKeyInput { pressKey(Key.DirectionRight) }
        card(3).assertIsFocused()

        // Back opens the spine without walking focus through the earlier cards.
        pressBack()
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()

        composeRule.onNodeWithContentDescription("Home")
            .performKeyInput { pressKey(Key.DirectionRight) }
        card(3).assertIsFocused()
    }

    @Test
    fun focusMemorySurvivesADestinationSwitch() {
        setShellContent(IglooRailState.Loaded(movies))
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).assertIsFocused()

        composeRule.onNodeWithContentDescription("Movies").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Home").performClick()
        composeRule.waitForIdle()

        card(2).assertIsFocused()
    }

    @Test
    fun lastCardPinsTheRightEdge() {
        setShellContent(IglooRailState.Loaded(movies))
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).performKeyInput { pressKey(Key.DirectionRight) }
        card(3).assertIsFocused()

        card(3).performKeyInput { pressKey(Key.DirectionRight) }

        card(3).assertIsFocused()
    }

    @Test
    fun errorStateAnchorsFocusOnRetry() {
        setShellContent(IglooRailState.Error("scan in progress"))

        val retry = composeRule.onNodeWithContentDescription("Retry loading Recently Added Movies")
        retry.assertIsFocused()

        retry.performClick()
        assertEquals(1, retries)

        retry.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
    }

    @Test
    fun emptyStateIsFocusableAndAnnounced() {
        setShellContent(IglooRailState.Loaded(emptyList()))

        composeRule.onNodeWithContentDescription(
            "No movies in your library yet. Add a movies folder on the server and run a scan.",
        ).assertIsFocused()
    }

    @Test
    fun cardsAnnounceTitleAndYearAsOneButton() {
        setShellContent(IglooRailState.Loaded(movies))

        // One cleared node per card: TalkBack hears the title and year once, and neither the
        // poster image nor the caption texts exist as separate announceable nodes.
        card(1)
            .assertContentDescriptionEquals("Heat, 1995")
            .assert(hasClickAction())
        composeRule.onAllNodesWithText("Heat").assertCountEquals(0)
    }
}
