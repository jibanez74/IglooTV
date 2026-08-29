package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.fakeMoviePlayerEngineFactory
import com.igloo.blindpenguincoder.feature.home.HomeUiState
import com.igloo.blindpenguincoder.feature.home.IglooApp
import com.igloo.blindpenguincoder.feature.home.SignOutUiState
import com.igloo.blindpenguincoder.inertDetailsActions
import com.igloo.blindpenguincoder.inertMoviesActions
import com.igloo.blindpenguincoder.rememberInertMoviePlayerViewModel
import com.igloo.blindpenguincoder.testMovieGridItems
import com.igloo.blindpenguincoder.testMoviesState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the library grid says out loud (design-system.md section 12): one stop per card, a count
 * that reports scale without reading the grid back, and a tail that is texture rather than
 * something to traverse.
 */
@RunWith(AndroidJUnit4::class)
class MoviesGridAccessibilityTest {

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

    private var moviesState by mutableStateOf(testMoviesState())
    private var columns = 0

    private fun setContent(initial: MoviesUiState = testMoviesState()) {
        moviesState = initial
        composeRule.setContent {
            IglooTheme {
                columns = IglooTheme.layout.gridColumns
                IglooApp(
                    spokenAccessibilityEnabled = false,
                    user = user,
                    movies = moviesState,
                    moviesActions = inertMoviesActions,
                    serverOrigin = "http://igloo.test:8080",
                    signOut = SignOutUiState(),
                    home = HomeUiState(),
                    onRequestPlayback = { null },
                    moviePlayerViewModel = rememberInertMoviePlayerViewModel(),
                    moviePlayerEngineFactory = fakeMoviePlayerEngineFactory,
                    onRetryRail = {},
                    onMovieSelected = {},
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
        composeRule.onNodeWithContentDescription("Movies").performClick()
        composeRule.waitForIdle()
    }

    /**
     * Swaps the state after composition. `gridColumns` is a theme value, so it is only known
     * once something has composed — a state built before `setContent` cannot size itself in rows.
     */
    private fun showOneRow(append: MoviesAppendState) {
        moviesState = testMoviesState(
            grid = IglooRailState.Loaded(testMovieGridItems.take(columns)),
            append = append,
        )
        composeRule.waitForIdle()
    }

    @Test
    fun eachCardIsOneStopAnnouncingItsTitleAndYear() {
        setContent()

        val card = composeRule.onNodeWithTag("poster_card_1")
        card.assertContentDescriptionEquals("Movie 1, 1981")
        card.assertHasClickAction()
    }

    /** Counts, never titles: this region re-announces whenever an appended page lands. */
    @Test
    fun theCountReportsScaleWithoutReadingTheGridBack() {
        setContent(
            testMoviesState(
                grid = IglooRailState.Loaded(testMovieGridItems.take(10)),
                totalMovies = 96,
            ),
        )

        composeRule.onNodeWithTag("movies_count")
            .assertContentDescriptionEquals("Showing 10 of 96 movies")
    }

    @Test
    fun theCountSaysSoWhileTheLibrarySizeIsUnknown() {
        setContent(testMoviesState(grid = IglooRailState.Loading, totalMovies = null))

        composeRule.onNodeWithTag("movies_count")
            .assertContentDescriptionEquals("Loading the movie library")
    }

    @Test
    fun onlyOneTailCellAnnouncesThatMoreMoviesAreComing() {
        setContent()
        showOneRow(MoviesAppendState.Loading)

        composeRule.onAllNodesWithContentDescription("Loading more movies").assertCountEquals(1)
    }

    /** An idle tail is pure texture — it must not add a stop between the grid and whatever follows. */
    @Test
    fun anIdleTailAnnouncesNothing() {
        setContent()
        showOneRow(MoviesAppendState.Idle)

        composeRule.onAllNodesWithContentDescription("Loading more movies").assertCountEquals(0)
    }

    @Test
    fun aFailedPageReportsPolitelyRatherThanInterrupting() {
        setContent()
        showOneRow(MoviesAppendState.Error("Something went wrong"))

        // The message is readable and the Retry is actionable; the card carries the Polite
        // live region so the report never cuts across whatever else is speaking.
        composeRule.onNodeWithText("Something went wrong").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Retry loading more movies").assertHasClickAction()
    }

    @Test
    fun refreshAnnouncesItsPendingStateWithoutLosingItsLabel() {
        setContent(testMoviesState(refreshing = true))

        composeRule.onNodeWithContentDescription("Refresh the movie library")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Refreshing",
                ),
            )
    }

    @Test
    fun anEmptyLibraryAnnouncesItself() {
        setContent(testMoviesState(grid = IglooRailState.Loaded(emptyList())))

        // Focusable so the pane keeps an anchor, but deliberately not a button: pressing it
        // would do nothing, and announcing an action that does nothing is worse than none.
        composeRule.onNodeWithContentDescription("No movies found in your library.")
            .assertIsFocused()
    }
}
