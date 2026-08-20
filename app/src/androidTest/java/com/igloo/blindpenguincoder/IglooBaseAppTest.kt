package com.igloo.blindpenguincoder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.design.UiScale
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.feature.home.HomeHeroState
import com.igloo.blindpenguincoder.feature.home.HomeUiState
import com.igloo.blindpenguincoder.feature.home.IglooApp
import com.igloo.blindpenguincoder.feature.home.SignOutUiState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsUiState

@RunWith(AndroidJUnit4::class)
class IglooBaseAppTest {
    // Lower is outermost, so animations are off before the compose rule sets up: the dialog's
    // reveal then snaps and every assertion below is frame-stable.
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

    /**
     * Hosts the sign-out state the way `SignOutViewModel` does, so the rail → dialog → confirm
     * path is exercised for real rather than stubbed at the callback. [onConfirm] stands in for
     * the revoke; [initialSignOut] seeds a state the flow cannot be clicked into, i.e. pending.
     */
    private fun setShellContent(
        uiScale: UiScale = UiScale.Standard,
        initialSignOut: SignOutUiState = SignOutUiState(),
        onSwitchProfile: () -> Unit = {},
        onConfirm: () -> Unit = {},
    ) {
        composeRule.setContent {
            var signOut by remember { mutableStateOf(initialSignOut) }
            IglooTheme(uiScale = uiScale) {
                IglooApp(
                    // Pinned: the Shield test device runs TalkBack, and this suite
                    // asserts the focus chain without the reading stops.
                    spokenAccessibilityEnabled = false,
                    user = user,
                    serverOrigin = "http://igloo.test:8080",
                    signOut = signOut,
                    // Hero hidden — a legitimate 11.3.1 state — so both rail headings fit the
                    // viewport at once for the order assertion; the hero-visible shell is
                    // NavigationRailBehaviorTest's and HomeHeroFocusTest's subject.
                    home = HomeUiState(
                        hero = HomeHeroState.Hidden,
                        continueWatching = IglooRailState.Loaded(testContinueMovies),
                        latestMovies = IglooRailState.Loaded(testHomeMovies),
                    ),
                    onRetryRail = {},
                    onMovieSelected = null,
                    onTheaterMovieSelected = null,
                    onCloseDetails = {},
                    details = MovieDetailsUiState(),
                    detailsActions = inertDetailsActions,
                    onSwitchProfile = onSwitchProfile,
                    onSignOut = { signOut = SignOutUiState(confirming = true) },
                    onSignOutConfirm = {
                        onConfirm()
                        signOut = SignOutUiState()
                    },
                    onSignOutDismiss = { signOut = SignOutUiState() },
                )
            }
        }
    }

    private fun openSignOutDialog() {
        composeRule.onNodeWithContentDescription("Sign out").performClick()
        composeRule.waitForIdle()
    }

    private fun dialogTitle() = composeRule.onNodeWithText("Sign out of Igloo?")

    /** The confirm button, not the rail row — both answer to "Sign out". */
    private fun confirmButton() = composeRule.onAllNodesWithContentDescription("Sign out")
        .filterToOne(hasAnyAncestor(hasTestTag("shell_content")).not())

    @Test
    fun shellShowsNavigationAndSignedInUser() {
        setShellContent()

        composeRule.onNodeWithTag("navigation_rail").assertIsDisplayed()
        val continueWatching = composeRule.onNodeWithText("Continue Watching")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val recentlyAdded = composeRule.onNodeWithText("Recently Added Movies")
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        // Design-system section 11.3: continue watching is the first rail on Home.
        assertTrue(
            "Continue Watching must sit above Recently Added Movies",
            continueWatching.bottom <= recentlyAdded.top,
        )
        composeRule.onNodeWithContentDescription("Home").assertIsSelected()
        composeRule.onAllNodes(isSelected()).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Signed in as Jose").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Switch profile").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Sign out").assertIsDisplayed()
    }

    @Test
    fun railHidesDecorativeTextFromTalkBack() {
        setShellContent()

        // The brand lockup and the bare profile name fade to alpha 0 when the rail is
        // collapsed; they must not exist as announceable nodes in either state.
        composeRule.onAllNodesWithText("Igloo").assertCountEquals(0)
        composeRule.onAllNodesWithText("TV").assertCountEquals(0)
        composeRule.onAllNodesWithText("Jose").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Signed in as Jose").assertExists()
    }

    @Test
    fun navigationItemsChangeContentPane() {
        setShellContent()

        // The placeholder is one cleared node (section 10's inert-anchor contract), so the pane
        // is identified by what it announces rather than by a loose text node inside it.
        composeRule.onNodeWithContentDescription("Movies").performClick()
        composeRule.onNodeWithContentDescription(
            "Movies. Movie library scaffolding is ready for API-backed content.",
        ).assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Music").performClick()
        composeRule.onNodeWithContentDescription(
            "Music. Music playback dependencies are available for the next feature pass.",
        ).assertIsDisplayed()
    }

    @Test
    fun signOutAsksBeforeItSignsOut() {
        var signedOut = false
        setShellContent(onConfirm = { signedOut = true })

        openSignOutDialog()

        dialogTitle().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Jose will be removed from this TV. You'll need to sign in again to watch here.",
        ).assertIsDisplayed()
        // Cancel holds focus: the destructive action is never the default.
        composeRule.onNodeWithContentDescription("Cancel").assertIsFocused()
        composeRule.runOnIdle {
            check(!signedOut) { "Opening the confirmation must not sign the user out" }
        }

        confirmButton().performClick()

        composeRule.runOnIdle {
            check(signedOut) { "Confirming did not sign the user out" }
        }
    }

    @Test
    fun cancellingRestoresFocusToTheSignOutRow() {
        var signedOut = false
        setShellContent(onConfirm = { signedOut = true })
        openSignOutDialog()

        composeRule.onNodeWithContentDescription("Cancel").performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Sign out of Igloo?").assertCountEquals(0)
        // Focus goes back to the control that led away, per design-system section 9.3.
        composeRule.onNodeWithContentDescription("Sign out").assertIsFocused()
        composeRule.runOnIdle {
            check(!signedOut) { "Cancelling must not sign the user out" }
        }
    }

    @Test
    fun theDialogTrapsFocusWhileItIsOpen() {
        setShellContent()
        openSignOutDialog()

        val cancel = composeRule.onNodeWithContentDescription("Cancel")
        // Every direction that would leave the card is pinned, so focus cannot fall through to
        // the rail or a card behind the scrim.
        listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft).forEach { key ->
            cancel.performKeyInput { pressKey(key) }
            composeRule.waitForIdle()
            cancel.assertIsFocused()
        }

        cancel.performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.waitForIdle()
        confirmButton().assertIsFocused()

        listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionRight).forEach { key ->
            confirmButton().performKeyInput { pressKey(key) }
            composeRule.waitForIdle()
            confirmButton().assertIsFocused()
        }

        composeRule.onNodeWithContentDescription("Home").assertIsNotFocused()
    }

    @Test
    fun aPendingSignOutAnnouncesItselfAndOffersNoConfirm() {
        setShellContent(initialSignOut = SignOutUiState(confirming = true, pending = true))

        composeRule.onNodeWithContentDescription("Signing out…")
            .assertIsDisplayed()
            .assertIsFocused()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        // No confirm control exists while the revoke is in flight, so it cannot be pressed twice.
        composeRule.onAllNodesWithContentDescription("Cancel").assertCountEquals(0)
    }

    @Test
    fun theShellIsHiddenFromAccessibilityWhileTheDialogIsOpen() {
        setShellContent()
        val shell = composeRule.onNodeWithTag("shell_content")
        shell.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))

        openSignOutDialog()

        // Hidden from traversal, but still in the tree — so the assertion below is meaningful.
        shell.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
        composeRule.onNodeWithContentDescription("Home").assertIsNotFocused()
    }

    @Test
    fun switchProfileInvokesItsOwnCallbackAndSitsAboveSignOut() {
        var switched = false
        var signedOut = false
        setShellContent(onSwitchProfile = { switched = true }, onConfirm = { signedOut = true })

        val switch = composeRule.onNodeWithContentDescription("Switch profile")
        val signOut = composeRule.onNodeWithContentDescription("Sign out")
        assertTrue(
            "Switch profile must sit above Sign out",
            switch.getUnclippedBoundsInRoot().bottom <= signOut.getUnclippedBoundsInRoot().top,
        )

        switch.performClick()

        composeRule.runOnIdle {
            check(switched) { "Switch profile click did not invoke onSwitchProfile" }
            check(!signedOut) { "Switch profile must not sign the user out" }
        }
        // And it does not ask: switching loses nothing, so there is nothing to confirm.
        composeRule.onAllNodesWithText("Sign out of Igloo?").assertCountEquals(0)
    }

    @Test
    fun accountActionsStayOnscreenAtLargeScale() {
        setShellContent(uiScale = UiScale.Large)

        val rootBottom = composeRule.onRoot().getUnclippedBoundsInRoot().bottom
        listOf("Switch profile", "Sign out").forEach { label ->
            val bounds = composeRule.onNodeWithContentDescription(label)
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
            assertTrue("$label falls below the viewport: $bounds", bounds.bottom <= rootBottom)
        }
    }
}
