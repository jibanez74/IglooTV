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
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.testContinueMovies
import com.igloo.blindpenguincoder.testHomeMovies
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The home rails' focus contract: every state of the first rail anchors the content pane,
 * focus survives state swaps, each rail keeps its own focus memory, and the last-focused card
 * is restored on re-entry (design-system.md sections 6.3 and 11.3).
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

    private val continueMovies = testContinueMovies
    private val movies = testHomeMovies

    private var continueState by
        mutableStateOf<IglooRailState<HomeContinueMovie>>(IglooRailState.Loading)
    private var latestState by mutableStateOf<IglooRailState<HomeMovie>>(IglooRailState.Loading)
    private var continueRetries = 0
    private var latestRetries = 0
    private val opened = mutableListOf<HomeMovie>()
    private var expandedWidth: Dp = Dp.Unspecified
    private var hostActivity: Activity? = null

    private fun setShellContent(
        initialContinue: IglooRailState<HomeContinueMovie>,
        initialLatest: IglooRailState<HomeMovie> = IglooRailState.Loaded(movies),
        onMovieSelected: ((HomeMovie) -> Unit)? = { opened += it },
    ) {
        continueState = initialContinue
        latestState = initialLatest
        continueRetries = 0
        latestRetries = 0
        opened.clear()
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                expandedWidth = IglooTheme.layout.navRailExpandedWidth
                IglooApp(
                    user = user,
                    signOut = SignOutUiState(),
                    home = HomeUiState(
                        continueWatching = continueState,
                        latestMovies = latestState,
                    ),
                    onRetryRail = { rail ->
                        when (rail) {
                            HomeRail.ContinueWatching -> continueRetries += 1
                            HomeRail.LatestMovies -> latestRetries += 1
                        }
                    },
                    onMovieSelected = onMovieSelected,
                    onSwitchProfile = {},
                    onSignOut = {},
                    onSignOutConfirm = {},
                    onSignOutDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun continueCard(id: Long) = composeRule.onNodeWithTag("continue_card_$id")

    private fun latestCard(id: Long) = composeRule.onNodeWithTag("poster_card_$id")

    private fun pressBack() {
        composeRule.runOnUiThread {
            (checkNotNull(hostActivity) as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun initialFocusLandsOnTheLoadingSkeleton() {
        setShellContent(IglooRailState.Loading)

        val skeleton = composeRule.onNodeWithContentDescription("Loading continue watching")
        skeleton.assertIsFocused()

        // The skeleton anchor still opens the spine, so a slow network never traps the d-pad.
        skeleton.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
        composeRule.onNodeWithTag("navigation_rail").assertWidthIsEqualTo(expandedWidth)
    }

    @Test
    fun skeletonHandsFocusToTheFirstCardWhenContentArrives() {
        setShellContent(IglooRailState.Loading)
        composeRule.onNodeWithContentDescription("Loading continue watching").assertIsFocused()

        continueState = IglooRailState.Loaded(continueMovies)
        composeRule.waitForIdle()

        continueCard(1).assertIsFocused()
    }

    @Test
    fun focusNotInTheRailIsNotStolenWhenContentArrives() {
        setShellContent(IglooRailState.Loading)
        composeRule.onNodeWithContentDescription("Loading continue watching")
            .performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()

        continueState = IglooRailState.Loaded(continueMovies)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
    }

    @Test
    fun spineReentryRestoresTheLastFocusedCard() {
        setShellContent(IglooRailState.Loaded(continueMovies))
        continueCard(1).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(2).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(3).assertIsFocused()

        // Back opens the spine without walking focus through the earlier cards.
        pressBack()
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()

        composeRule.onNodeWithContentDescription("Home")
            .performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(3).assertIsFocused()
    }

    @Test
    fun focusMemorySurvivesADestinationSwitch() {
        setShellContent(IglooRailState.Loaded(continueMovies))
        continueCard(1).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(2).assertIsFocused()

        composeRule.onNodeWithContentDescription("Movies").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Home").performClick()
        composeRule.waitForIdle()

        continueCard(2).assertIsFocused()
    }

    @Test
    fun lastCardPinsTheRightEdge() {
        setShellContent(IglooRailState.Loaded(continueMovies))
        continueCard(1).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(2).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(3).assertIsFocused()

        continueCard(3).performKeyInput { pressKey(Key.DirectionRight) }

        continueCard(3).assertIsFocused()
    }

    @Test
    fun dpadDownReachesTheLatestRailAndContinueMemorySurvivesTheTrip() {
        setShellContent(IglooRailState.Loaded(continueMovies))
        continueCard(1).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(2).assertIsFocused()

        continueCard(2).performKeyInput { pressKey(Key.DirectionDown) }
        latestCard(2).assertIsFocused()
        latestCard(2).performKeyInput { pressKey(Key.DirectionRight) }
        latestCard(3).assertIsFocused()

        // Spine re-entry lands on the Continue Watching rail's remembered card, untouched by
        // the excursion through the latest rail — the two rails keep separate memories.
        pressBack()
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
        composeRule.onNodeWithContentDescription("Home")
            .performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(2).assertIsFocused()
    }

    @Test
    fun errorStateAnchorsFocusOnRetry() {
        setShellContent(IglooRailState.Error("scan in progress"))

        val retry = composeRule.onNodeWithContentDescription("Retry loading Continue Watching")
        retry.assertIsFocused()

        retry.performClick()
        assertEquals(1, continueRetries)
        assertEquals(0, latestRetries)

        retry.performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
    }

    @Test
    fun latestRailRetryLeavesTheContinueRailAlone() {
        setShellContent(
            initialContinue = IglooRailState.Loaded(continueMovies),
            initialLatest = IglooRailState.Error("scan in progress"),
        )

        // The second rail can start below the fold, so bring its Retry into view before tapping.
        composeRule.onNodeWithContentDescription("Retry loading Recently Added Movies")
            .performScrollTo()
            .performClick()

        assertEquals(1, latestRetries)
        assertEquals(0, continueRetries)
    }

    @Test
    fun emptyStateIsFocusableAndAnnounced() {
        setShellContent(IglooRailState.Loaded(emptyList()))

        composeRule.onNodeWithContentDescription(
            "Nothing in progress yet. Movies you start watching appear here.",
        ).assertIsFocused()
    }

    @Test
    fun cardsAnnounceTitleAndYearAsOneButton() {
        setShellContent(IglooRailState.Loaded(continueMovies))

        // One cleared node per card: TalkBack hears the title and year once, and neither the
        // poster image nor the caption texts exist as separate announceable nodes — in either
        // rail, even though the same movie appears in both.
        latestCard(1)
            .assertContentDescriptionEquals("Heat, 1995")
            .assert(hasClickAction())
        composeRule.onAllNodesWithText("Heat").assertCountEquals(0)
    }

    @Test
    fun continueCardsAnnounceTitleYearAndProgressAsOneButton() {
        setShellContent(IglooRailState.Loaded(continueMovies))

        // Design-system section 12: a continue-watching card announces title, year, and
        // progress in its one cleared node.
        continueCard(1)
            .assertContentDescriptionEquals("Heat, 1995, 127 min left")
            .assert(hasClickAction())
    }

    @Test
    fun okOnACardOpensIt() {
        setShellContent(IglooRailState.Loaded(continueMovies))

        continueCard(1).assertIsFocused()
        continueCard(1).performKeyInput { pressKey(Key.DirectionCenter) }

        assertEquals(listOf(movies[0]), opened)
    }

    @Test
    fun cardsWithNothingToOpenAnnounceNoAction() {
        setShellContent(IglooRailState.Loaded(continueMovies), onMovieSelected = null)

        // Until the details screen lands there is nothing to open, and a card that announced
        // "double tap to Open Heat" would promise a screen reader an action nobody implements.
        // It stays focusable, because the rails' focus model needs every card to be a landing site.
        continueCard(1)
            .assertContentDescriptionEquals("Heat, 1995, 127 min left")
            .assertHasNoClickAction()
        continueCard(1).assertIsFocused()
        continueCard(1).performKeyInput { pressKey(Key.DirectionRight) }
        continueCard(2).assertIsFocused()
    }
}
