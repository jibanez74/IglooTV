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
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.ANNOUNCED_FOCUS_WAIT_MS
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.testContinueMovies
import com.igloo.blindpenguincoder.testHero
import com.igloo.blindpenguincoder.testHomeMovies
import org.junit.Assert.assertEquals
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

    // Captured inside the theme so a density-corrected emulator still yields the widths the
    // shell actually laid out with, rather than hardcoded Standard values.
    private var collapsedWidth: Dp = Dp.Unspecified
    private var expandedWidth: Dp = Dp.Unspecified
    private var hostActivity: Activity? = null

    private fun setShellContent(
        initialSignOut: SignOutUiState = SignOutUiState(),
        spokenAccessibilityEnabled: Boolean = false,
    ) {
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            var signOut by remember { mutableStateOf(initialSignOut) }
            IglooTheme {
                collapsedWidth = IglooTheme.layout.navRailCollapsedWidth
                expandedWidth = IglooTheme.layout.navRailExpandedWidth
                TestIglooApp(
                    signOut = signOut,
                    // Loaded with poster-less movies and a backdrop-less hero: the placeholder
                    // paths render with no network or image loading, so the shell tests stay
                    // hermetic while exercising the shipped entry anchor — the hero.
                    home = HomeUiState(
                        hero = HomeHeroState.Loaded(testHero),
                        continueWatching = IglooRailState.Loaded(testContinueMovies),
                        latestMovies = IglooRailState.Loaded(testHomeMovies),
                    ),
                    onSignOut = { signOut = SignOutUiState(confirming = true) },
                    onSignOutConfirm = { signOut = SignOutUiState() },
                    onSignOutDismiss = { signOut = SignOutUiState() },
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                )
            }
        }
        composeRule.waitForIdle()
    }

    /** The content anchor on Home: the hero, whenever it is visible (section 11.3.1). */
    private fun contentStartCard() = composeRule.onNodeWithTag("home_hero")

    /**
     * The content anchor on a destination that has no screen yet. The placeholder is one cleared
     * node — the same contract as the hero and an inert poster card — so it is addressed by the
     * description it announces, not by the text inside it. Photos rather than Movies or TV
     * Shows: both render a real grid now, and this suite's subject is the shell, not a library
     * screen. Its rail row sits past the fold of the rail's scrolling column at 540dp, so
     * [openPhotos] brings it into view the way d-pad focus would before pressing it.
     */
    private fun placeholderStartCard() = composeRule.onNodeWithContentDescription(
        "Photos. Photo support is reserved for a later Igloo backend feature.",
    )

    private fun openPhotos() {
        composeRule.onNodeWithContentDescription("Photos").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

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

        openPhotos()
        placeholderStartCard().assertIsFocused()

        pressBack()

        composeRule.onNodeWithContentDescription("Photos").assertIsFocused()
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

    /**
     * The incoming pane's anchor is brand new, and TalkBack for TV drops a focus event that lands
     * on it too soon (see requestFocusAnnounced). So under a screen reader the handoff waits, and
     * the pressed rail row keeps focus until then; TalkBack would otherwise stay on the row and
     * never read the card.
     */
    @Test
    fun underAScreenReaderACrossBranchPressHandsFocusOverAfterTheWait() {
        setShellContent(spokenAccessibilityEnabled = true)
        contentStartCard().performKeyInput { pressKey(Key.DirectionLeft) }
        rail().performKeyInput { pressKey(Key.DirectionDown) }
        val moviesRow = composeRule.onNodeWithContentDescription("Movies")
        moviesRow.assertIsFocused()

        composeRule.mainClock.autoAdvance = false
        moviesRow.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithTag("poster_card_1").assertExists()
        moviesRow.assertIsFocused()

        composeRule.mainClock.advanceTimeBy(ANNOUNCE_WAIT_MS)
        composeRule.mainClock.autoAdvance = true
        composeRule.onNodeWithTag("poster_card_1").assertIsFocused()
        rail().assertWidthIsEqualTo(collapsedWidth)
    }

    /**
     * A user who moves on during that wait keeps the focus they chose. Right from a rail row
     * lands on the anchor itself, so the case that matters is going further before the wait ends
     * — on Home, whose anchor is always the hero rather than the last card focused.
     */
    @Test
    fun aMoveIntoThePaneDuringTheWaitIsNotOverridden() {
        setShellContent(spokenAccessibilityEnabled = true)
        contentStartCard().performKeyInput { pressKey(Key.DirectionLeft) }
        rail().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithContentDescription("Movies")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        // waitForIdle does not fast-forward the deferred request's delay; the clock has to.
        composeRule.mainClock.advanceTimeBy(ANNOUNCE_WAIT_MS)
        composeRule.onNodeWithTag("poster_card_1").assertIsFocused()
        composeRule.onNodeWithTag("poster_card_1").performKeyInput { pressKey(Key.DirectionLeft) }
        rail().performKeyInput { pressKey(Key.DirectionUp) }
        val homeRow = composeRule.onNodeWithContentDescription("Home")
        homeRow.assertIsFocused()

        composeRule.mainClock.autoAdvance = false
        homeRow.performKeyInput { pressKey(Key.DirectionCenter) }
        repeat(4) { composeRule.mainClock.advanceTimeByFrame() }
        contentStartCard().assertExists()
        homeRow.performKeyInput { pressKey(Key.DirectionRight) }
        contentStartCard().assertIsFocused()
        contentStartCard().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.mainClock.advanceTimeByFrame()
        val chosen = composeRule.onNode(isFocused()).fetchSemanticsNode().id
        contentStartCard().assertIsNotFocused()

        composeRule.mainClock.advanceTimeBy(ANNOUNCE_WAIT_MS)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(chosen, composeRule.onNode(isFocused()).fetchSemanticsNode().id)
    }

    /** A dialog opened during that wait keeps its focus; the handoff must not land behind it. */
    @Test
    fun signOutOpenedDuringTheWaitKeepsFocusInTheDialog() {
        setShellContent(spokenAccessibilityEnabled = true)
        contentStartCard().performKeyInput { pressKey(Key.DirectionLeft) }
        rail().performKeyInput { pressKey(Key.DirectionDown) }

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithContentDescription("Movies")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        // Movies -> TV Shows -> Music -> Photos -> Settings -> Switch profile -> Sign out
        repeat(6) { rail().performKeyInput { pressKey(Key.DirectionDown) } }
        composeRule.onNodeWithContentDescription("Sign out")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
        val cancel = composeRule.onNodeWithContentDescription("Cancel")
        cancel.assertIsFocused()

        composeRule.mainClock.advanceTimeBy(ANNOUNCE_WAIT_MS)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        cancel.assertIsFocused()
        composeRule.onNodeWithTag("poster_card_1").assertIsNotFocused()
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

/** Longer than the helper's wait, so the deferred request has certainly run. */
private const val ANNOUNCE_WAIT_MS = ANNOUNCED_FOCUS_WAIT_MS + 100L

internal tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("The compose test host context is not an Activity")
}
