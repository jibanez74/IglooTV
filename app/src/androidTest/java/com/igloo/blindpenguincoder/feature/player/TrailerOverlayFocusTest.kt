package com.igloo.blindpenguincoder.feature.player

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.feature.home.HomeHeroState
import com.igloo.blindpenguincoder.feature.home.HomeUiState
import com.igloo.blindpenguincoder.feature.home.IglooApp
import com.igloo.blindpenguincoder.feature.home.SignOutUiState
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsState
import com.igloo.blindpenguincoder.feature.movies.MovieDetailsUiState
import com.igloo.blindpenguincoder.inertDetailsActions
import com.igloo.blindpenguincoder.playback.youtube.FakeTrailerPlayerEngine
import com.igloo.blindpenguincoder.testContinueMovies
import com.igloo.blindpenguincoder.testMovieDetails
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The trailer player as the shell's third overlay layer (design-system.md sections 6.3 and
 * 11.8.1): an extra card opens it, the details screen underneath leaves TalkBack traversal for
 * its lifetime, and closing it restores focus to the exact card that launched playback — all
 * against the fake engine, so no test touches youtube.com.
 */
@RunWith(AndroidJUnit4::class)
class TrailerOverlayFocusTest {

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

    private var detailsState by mutableStateOf(MovieDetailsUiState())
    private val engines = mutableListOf<FakeTrailerPlayerEngine>()
    private var hostActivity: Activity? = null

    private fun setShellContent() {
        detailsState = MovieDetailsUiState(
            openMovieId = 1,
            details = MovieDetailsState.Loaded(testMovieDetails(id = 1)),
        )
        engines.clear()
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                IglooApp(
                    user = user,
                    serverOrigin = "http://igloo.test:8080",
                    signOut = SignOutUiState(),
                    home = HomeUiState(
                        hero = HomeHeroState.Hidden,
                        continueWatching = IglooRailState.Loaded(testContinueMovies),
                    ),
                    details = detailsState,
                    detailsActions = inertDetailsActions,
                    onRetryRail = {},
                    onMovieSelected = {},
                    onTheaterMovieSelected = {},
                    onCloseDetails = {
                        detailsState = detailsState.copy(
                            openMovieId = null,
                            details = MovieDetailsState.Loading,
                        )
                    },
                    onSwitchProfile = {},
                    onSignOut = {},
                    onSignOutConfirm = {},
                    onSignOutDismiss = {},
                    trailerEngineFactory = { _, _ ->
                        FakeTrailerPlayerEngine().also { engines += it }
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        composeRule.runOnUiThread {
            (checkNotNull(hostActivity) as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    private fun openPlayerFromSecondExtraCard() {
        // The second card, not the rail's first: a restore that regressed to the rail's entry
        // anchor would still pass a first-card assertion.
        composeRule.onNodeWithTag("extra_card_202").requestFocus()
        composeRule.onNodeWithTag("extra_card_202").assertIsFocused()
        composeRule.onNodeWithTag("extra_card_202")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
    }

    @Test
    fun clickingAnExtraCardOpensThePlayerOverlay() {
        setShellContent()

        openPlayerFromSecondExtraCard()

        composeRule.onNodeWithTag("trailer_player").assertExists()
        composeRule.onNodeWithTag("trailer_play_pause").assertIsFocused()
        assertEquals(1, engines.size)
    }

    @Test
    fun theDetailsOverlayLeavesTalkBackTraversalWhileThePlayerIsUp() {
        setShellContent()
        val detailsLayer = composeRule.onNodeWithTag("details_layer")
        detailsLayer.assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility),
        )

        openPlayerFromSecondExtraCard()

        // Hidden from traversal, but still in the tree — so the assertion is meaningful.
        detailsLayer.assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility),
        )
    }

    @Test
    fun backClosesThePlayerAndRestoresFocusToTheLaunchingExtraCard() {
        setShellContent()
        openPlayerFromSecondExtraCard()

        pressBack()

        // The player is gone and its engine torn down, the details overlay survived the Back,
        // and focus is back on the exact card that launched playback.
        composeRule.onNodeWithTag("trailer_player").assertDoesNotExist()
        assertEquals(true, engines.single().released)
        composeRule.onNodeWithTag("movie_details").assertExists()
        composeRule.onNodeWithTag("extra_card_202").assertIsFocused()
    }
}
