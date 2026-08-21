package com.igloo.blindpenguincoder.feature.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Dp
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rail's expand/collapse contract and the three-state Back model. Animations are off via
 * [AnimationScaleRule], so `iglooTween` snaps and the two authored widths are frame-stable.
 */
@RunWith(AndroidJUnit4::class)
class NavigationRailBehaviorTest {

    // Explicit order: lower is outermost, so animations are off before the compose rule sets up.
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

    // Captured inside the theme so a density-corrected emulator still yields the widths the
    // shell actually laid out with, rather than hardcoded Standard values.
    private var collapsedWidth: Dp = Dp.Unspecified
    private var expandedWidth: Dp = Dp.Unspecified
    private var hostActivity: Activity? = null

    private fun setShellContent(initialSignOut: SignOutUiState = SignOutUiState()) {
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            var signOut by remember { mutableStateOf(initialSignOut) }
            IglooTheme {
                collapsedWidth = IglooTheme.layout.navRailCollapsedWidth
                expandedWidth = IglooTheme.layout.navRailExpandedWidth
                IglooApp(
                    // Pinned: the Shield test device runs TalkBack, and this suite
                    // asserts the focus chain without the reading stops.
                    spokenAccessibilityEnabled = false,
                    user = user,
                    serverOrigin = "http://igloo.test:8080",
                    signOut = signOut,
                    // Loaded with poster-less movies and a backdrop-less hero: the placeholder
                    // paths render with no network or image loading, so the shell tests stay
                    // hermetic while exercising the shipped entry anchor — the hero.
                    home = HomeUiState(
                        hero = HomeHeroState.Loaded(testHero),
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
                    onSignOut = { signOut = SignOutUiState(confirming = true) },
                    onSignOutConfirm = { signOut = SignOutUiState() },
                    onSignOutDismiss = { signOut = SignOutUiState() },
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** The content anchor on Home: the hero, whenever it is visible (section 11.3.1). */
    private fun contentStartCard() = composeRule.onNodeWithTag("home_hero")

    /**
     * The content anchor on every other destination. The placeholder is one cleared node — the
     * same contract as the hero and an inert poster card — so it is addressed by the description
     * it announces, not by the text inside it.
     */
    private fun placeholderStartCard() = composeRule.onNodeWithContentDescription(
        "Movies. Movie library scaffolding is ready for API-backed content.",
    )

    private fun rail() = composeRule.onNodeWithTag("navigation_rail")

    // Back goes through the dispatcher BackHandler listens to; its fallback, when no handler
    // is enabled, is what finishes the activity.
    private fun pressBack() {
        composeRule.runOnUiThread {
            (checkNotNull(hostActivity) as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun railRestsCollapsedAndExpandsWhileDpadFocusIsInside() {
        setShellContent()

        contentStartCard().assertIsFocused()
        rail().assertWidthIsEqualTo(collapsedWidth)

        contentStartCard().performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()
        rail().assertWidthIsEqualTo(expandedWidth)

        composeRule.onNodeWithContentDescription("Home")
            .performKeyInput { pressKey(Key.DirectionRight) }
        contentStartCard().assertIsFocused()
        rail().assertWidthIsEqualTo(collapsedWidth)
    }

    @Test
    fun backFromContentOpensRailOnCurrentDestination() {
        setShellContent()

        composeRule.onNodeWithContentDescription("Movies").performClick()
        placeholderStartCard().assertIsFocused()

        pressBack()

        composeRule.onNodeWithContentDescription("Movies").assertIsFocused()
        rail().assertWidthIsEqualTo(expandedWidth)
    }

    @Test
    fun backFromRailEnteredByDpadReturnsToContent() {
        setShellContent()

        contentStartCard().performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()

        pressBack()

        contentStartCard().assertIsFocused()
        rail().assertWidthIsEqualTo(collapsedWidth)
    }

    @Test
    fun backFromRailEnteredByBackExitsTheApp() {
        setShellContent()

        pressBack()
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()

        // No BackHandler is enabled in this state, so Back falls through to the activity.
        pressBack()

        val activity = checkNotNull(hostActivity)
        assertTrue(
            "Back on a rail opened by Back must exit the app",
            activity.isFinishing || activity.isDestroyed,
        )
    }

    @Test
    fun searchIsReachableFromTheRail() {
        setShellContent()

        composeRule.onNodeWithContentDescription("Search").performClick()

        composeRule.onNodeWithContentDescription(
            "Search. Find movies, shows, music, and photos across your library.",
        ).assertIsDisplayed()
    }

    @Test
    fun accountRowsHandDpadRightToTheContent() {
        setShellContent()

        contentStartCard().performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Home").assertIsFocused()

        // Home -> Movies -> TV Shows -> Music -> Photos -> Settings -> Switch profile -> Sign out
        repeat(7) { rail().performKeyInput { pressKey(Key.DirectionDown) } }
        composeRule.onNodeWithContentDescription("Sign out").assertIsFocused()

        composeRule.onNodeWithContentDescription("Sign out")
            .performKeyInput { pressKey(Key.DirectionRight) }
        contentStartCard().assertIsFocused()
        rail().assertWidthIsEqualTo(collapsedWidth)
    }

    @Test
    fun backInsideTheSignOutDialogCancelsItAndRestoresFocus() {
        setShellContent()
        composeRule.onNodeWithContentDescription("Sign out").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sign out of Igloo?").assertIsDisplayed()

        pressBack()

        // Back reaches the dialog's own handler rather than falling through to the rail's
        // three-state model — and the shell's handlers are gated while it is open.
        composeRule.onAllNodesWithText("Sign out of Igloo?").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Sign out").assertIsFocused()
        assertTrue(
            "Back inside the dialog must not finish the activity",
            checkNotNull(hostActivity).let { !it.isFinishing && !it.isDestroyed },
        )
        // The rail stays open behind the dialog, so cancelling lands the user back in it.
        rail().assertWidthIsEqualTo(expandedWidth)
    }

    @Test
    fun backWhileSignOutIsPendingClosesTheModalAndRestoresFocus() {
        setShellContent(
            initialSignOut = SignOutUiState(confirming = true, pending = true),
        )
        composeRule.onNodeWithContentDescription("Signing out…").assertIsFocused()

        pressBack()

        composeRule.onAllNodesWithText("Sign out of Igloo?").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Sign out").assertIsFocused()
        rail().assertWidthIsEqualTo(expandedWidth)
    }
}

internal tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("The compose test host context is not an Activity")
}
