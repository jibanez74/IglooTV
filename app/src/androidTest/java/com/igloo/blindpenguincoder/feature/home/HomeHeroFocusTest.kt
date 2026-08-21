package com.igloo.blindpenguincoder.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.fakeMoviePlayerEngineFactory
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsUiState
import com.igloo.blindpenguincoder.inertDetailsActions
import com.igloo.blindpenguincoder.rememberInertMoviePlayerViewModel
import com.igloo.blindpenguincoder.testContinueMovies
import com.igloo.blindpenguincoder.testHero
import com.igloo.blindpenguincoder.testHomeMovies
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The hero's focus contract (design-system.md section 11.3.1): it owns the pane's entry anchor
 * whenever it is visible, hands focus over cleanly on every state swap, and announces the
 * featured movie as one node with no action until the details screen lands.
 */
@RunWith(AndroidJUnit4::class)
class HomeHeroFocusTest {

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

    private var heroState by mutableStateOf<HomeHeroState>(HomeHeroState.Loading)

    private fun setShellContent(initialHero: HomeHeroState) {
        heroState = initialHero
        composeRule.setContent {
            IglooTheme {
                IglooApp(
                    // Pinned: the Shield test device runs TalkBack, and this suite
                    // asserts the focus chain without the reading stops.
                    spokenAccessibilityEnabled = false,
                    user = user,
                    serverOrigin = "http://igloo.test:8080",
                    signOut = SignOutUiState(),
                    home = HomeUiState(
                        hero = heroState,
                        continueWatching = IglooRailState.Loaded(testContinueMovies),
                        latestMovies = IglooRailState.Loaded(testHomeMovies),
                    ),
                    onRequestPlayback = { null },
                    moviePlayerViewModel = rememberInertMoviePlayerViewModel(),
                    moviePlayerEngineFactory = fakeMoviePlayerEngineFactory,
                    onRetryRail = {},
                    onMovieSelected = null,
                    onTheaterMovieSelected = null,
                    onCloseDetails = {},
                    details = MovieDetailsUiState(),
                    detailsActions = inertDetailsActions,
                    onSwitchProfile = {},
                    onSignOut = {},
                    onSignOutConfirm = {},
                    onSignOutDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun hero() = composeRule.onNodeWithTag("home_hero")

    @Test
    fun initialFocusLandsOnTheHeroSkeleton() {
        setShellContent(HomeHeroState.Loading)

        hero().assertIsFocused()
        composeRule.onNodeWithContentDescription("Loading featured movie").assertIsFocused()
    }

    @Test
    fun skeletonHandsFocusToTheLoadedHero() {
        setShellContent(HomeHeroState.Loading)
        hero().assertIsFocused()

        heroState = HomeHeroState.Loaded(testHero)
        composeRule.waitForIdle()

        hero().assertIsFocused()
    }

    @Test
    fun aHeroThatHidesHandsFocusToTheFirstRail() {
        setShellContent(HomeHeroState.Loading)
        hero().assertIsFocused()

        heroState = HomeHeroState.Hidden
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("continue_card_1").assertIsFocused()
    }

    @Test
    fun focusNotOnTheHeroIsNotStolenWhenItLoads() {
        setShellContent(HomeHeroState.Loading)
        hero().performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()

        heroState = HomeHeroState.Loaded(testHero)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
    }

    @Test
    fun aHeroThatComesBackDoesNotStealFocusFromTheRails() {
        // The hero owned focus before it hid, so whatever remembers that must not still be armed
        // when it returns: by then the user has moved on and the rail owns focus for real.
        setShellContent(HomeHeroState.Loading)
        hero().assertIsFocused()

        heroState = HomeHeroState.Hidden
        composeRule.waitForIdle()
        val firstCard = composeRule.onNodeWithTag("continue_card_1")
        firstCard.assertIsFocused()
        firstCard.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("continue_card_2").assertIsFocused()

        heroState = HomeHeroState.Loaded(testHero)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("continue_card_2").assertIsFocused()
    }

    @Test
    fun dpadLeftFromTheHeroOpensTheSpine() {
        setShellContent(HomeHeroState.Loaded(testHero))

        hero().performKeyInput { pressKey(Key.DirectionLeft) }

        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
    }

    @Test
    fun dpadRightFromTheHeroStaysPut() {
        setShellContent(HomeHeroState.Loaded(testHero))

        hero().performKeyInput { pressKey(Key.DirectionRight) }

        hero().assertIsFocused()
    }

    @Test
    fun dpadDownFromTheHeroLandsInTheContinueRail() {
        setShellContent(HomeHeroState.Loaded(testHero))

        hero().performKeyInput { pressKey(Key.DirectionDown) }

        composeRule.onNodeWithTag("continue_card_1").assertIsFocused()
    }

    @Test
    fun heroAnnouncesTheFeaturedMovieAsOneNodeWithNoAction() {
        setShellContent(HomeHeroState.Loaded(testHero))

        // One cleared node: TalkBack hears "Featured", title, metadata, and the full overview
        // (the visual three-line clamp is not an accessibility clamp) — and no action, because
        // announcing an action nothing implements is worse than announcing none.
        hero()
            .assertContentDescriptionEquals(
                "Featured. Heat. 1995 · R · 2h 50m · 8.2. " +
                    "Obsessive master thief Neil McCauley leads a top-notch crew.",
            )
            .assertHasNoClickAction()
        composeRule.onAllNodesWithText("Heat").assertCountEquals(0)
    }
}
