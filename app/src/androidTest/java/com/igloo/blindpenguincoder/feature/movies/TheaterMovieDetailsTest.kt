package com.igloo.blindpenguincoder.feature.movies

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.inertMoviesActions
import com.igloo.blindpenguincoder.testMoviesState
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.fakeMoviePlayerEngineFactory
import com.igloo.blindpenguincoder.feature.home.HomeHeroState
import com.igloo.blindpenguincoder.feature.home.HomeUiState
import com.igloo.blindpenguincoder.feature.home.IglooApp
import com.igloo.blindpenguincoder.feature.home.SignOutUiState
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.playback.youtube.FakeTrailerPlayerEngine
import com.igloo.blindpenguincoder.rememberInertMoviePlayerViewModel
import com.igloo.blindpenguincoder.testContinueMovies
import com.igloo.blindpenguincoder.testTheaterMovieDetails
import com.igloo.blindpenguincoder.testTheaterMovies
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The in-theaters detail screen (design-system.md section 11.4.2): opened from the theaters rail,
 * its hero carries Play Trailer alone — no Play, Watched or Like for a movie the library does not
 * hold — the trailer player opens from it and returns focus to it, and a movie TMDB lists no
 * trailer for hands the entry anchor to the first section instead of leaving the page focus-dead.
 */
@RunWith(AndroidJUnit4::class)
class TheaterMovieDetailsTest {

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

    /** The shell with the theaters rail loaded; opening a card publishes [movie] as the page. */
    private fun setShellContent(
        movie: MovieDetailsUi = testTheaterMovieDetails(),
        // Explicit, never the ambient default: the Shield test device runs TalkBack.
        spokenAccessibilityEnabled: Boolean = false,
    ) {
        detailsState = MovieDetailsUiState()
        engines.clear()
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                IglooApp(
                    spokenAccessibilityEnabled = spokenAccessibilityEnabled,
                    user = user,
                    movies = testMoviesState(),
                    moviesActions = inertMoviesActions,
                    serverOrigin = "http://igloo.test:8080",
                    signOut = SignOutUiState(),
                    home = HomeUiState(
                        hero = HomeHeroState.Hidden,
                        continueWatching = IglooRailState.Loaded(testContinueMovies),
                        inTheaters = IglooRailState.Loaded(testTheaterMovies),
                    ),
                    details = detailsState,
                    // The in-theaters page's only hero action is the trailer, which the screen
                    // opens through the host's own player callback, so this bag holds Retry alone.
                    detailsActions = MovieDetailsActions.Theater(onRetry = {}),
                    onRequestPlayback = { null },
                    moviePlayerViewModel = rememberInertMoviePlayerViewModel(),
                    moviePlayerEngineFactory = fakeMoviePlayerEngineFactory,
                    onRetryRail = {},
                    onMovieSelected = null,
                    onTheaterMovieSelected = {
                        detailsState = MovieDetailsUiState(
                            openMovieId = it,
                            details = MovieDetailsState.Loaded(movie),
                        )
                    },
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

    /** The second card, so a restore that regressed to the rail's anchor would not pass. */
    private fun openFromTheatersRail() {
        composeRule.onNodeWithTag("theater_card_22").requestFocus()
        composeRule.onNodeWithTag("theater_card_22").assertIsFocused()
        composeRule.onNodeWithTag("theater_card_22")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()
    }

    @Test
    fun aTheaterCardOpensThePageWithPlayTrailerFocused() {
        setShellContent()

        openFromTheatersRail()

        composeRule.onNodeWithTag("movie_details").assertExists()
        composeRule.onNodeWithTag("details_play_trailer").assertIsFocused()
        // None of the library hero's actions exist: there is nothing here to play, mark, or like.
        composeRule.onNodeWithTag("details_play").assertDoesNotExist()
        composeRule.onNodeWithTag("details_watched").assertDoesNotExist()
        composeRule.onNodeWithTag("details_like").assertDoesNotExist()
        composeRule.onNodeWithTag("details_resume_track").assertDoesNotExist()
    }

    @Test
    fun backRestoresTheTheaterCardThatOpenedThePage() {
        setShellContent()
        openFromTheatersRail()

        pressBack()

        composeRule.onNodeWithTag("movie_details").assertDoesNotExist()
        composeRule.onNodeWithTag("theater_card_22").assertIsFocused()
    }

    @Test
    fun playTrailerOpensThePlayerAndClosingItComesBackToTheButton() {
        setShellContent()
        openFromTheatersRail()

        composeRule.onNodeWithTag("details_play_trailer")
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("trailer_player").assertExists()
        assertEquals(1, engines.size)

        pressBack()

        // Focus comes back to the hero's button, not to the extras rail's card: the same video
        // is on both, and the restore has to name the control that actually led away.
        composeRule.onNodeWithTag("trailer_player").assertDoesNotExist()
        composeRule.onNodeWithTag("movie_details").assertExists()
        composeRule.onNodeWithTag("details_play_trailer").assertIsFocused()
    }

    @Test
    fun theVerticalChainRunsFromPlayTrailerIntoTheSectionsAndBack() {
        setShellContent()
        openFromTheatersRail()

        composeRule.onNodeWithTag("details_play_trailer")
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("cast_card_101").assertIsFocused()

        composeRule.onNodeWithTag("cast_card_101")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("details_play_trailer").assertIsFocused()

        // Up out of the hero is pinned: the shell is still composed underneath this overlay.
        composeRule.onNodeWithTag("details_play_trailer")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("details_play_trailer").assertIsFocused()
        composeRule.onNodeWithTag("details_play_trailer")
            .performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithTag("details_play_trailer").assertIsFocused()
    }

    @Test
    fun withNoTrailerTheFirstSectionTakesTheEntryAnchor() {
        setShellContent(testTheaterMovieDetails(heroTrailer = null))

        openFromTheatersRail()

        // No action row at all, so entry focus lands on the first focusable below it — and up
        // from there stays put, because there is nothing above to return to.
        composeRule.onNodeWithTag("details_play_trailer").assertDoesNotExist()
        composeRule.onNodeWithTag("cast_card_101").assertIsFocused()
        composeRule.onNodeWithTag("cast_card_101")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("cast_card_101").assertIsFocused()
    }

    @Test
    fun theAboutBlockAnnouncesTheTmdbStatusRow() {
        setShellContent()
        openFromTheatersRail()

        // One node for the whole block, with Status between production and language — the row
        // only a TMDB record carries (web parity) — and the heading folded in, because TV
        // TalkBack never reaches the heading's own text node.
        composeRule.onNodeWithTag("details_about")
            .performScrollTo()
            .assertContentDescriptionEquals(
                "About Heat 2. Production: Regency Enterprises. Status: Released. " +
                    "Original language: English. Budget: $60,000,000. Revenue: $187,436,818",
            )
    }

    /**
     * The gated variant of the no-trailer anchor: with a screen reader running the Overview
     * reading stop is the page's first section, so entry lands there and up climbs to the hero
     * prose instead of pinning — there is still content above to hear.
     */
    @Test
    fun withNoTrailerAndAScreenReaderTheOverviewStopTakesTheEntryAnchor() {
        setShellContent(
            testTheaterMovieDetails(heroTrailer = null),
            spokenAccessibilityEnabled = true,
        )

        openFromTheatersRail()

        composeRule.onNodeWithTag("details_play_trailer").assertDoesNotExist()
        val overview = composeRule.onNodeWithTag("details_overview_stop")
        overview.assertIsFocused()

        overview.performKeyInput { pressKey(Key.DirectionUp) }
        val heroInfo = composeRule.onNodeWithTag("details_hero_info")
        heroInfo.assertIsFocused()
        heroInfo.performKeyInput { pressKey(Key.DirectionUp) }
        heroInfo.assertIsFocused()

        heroInfo.performKeyInput { pressKey(Key.DirectionDown) }
        overview.assertIsFocused()
    }
}
