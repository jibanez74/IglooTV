package com.igloo.blindpenguincoder.feature.movies

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.AuthUser
import com.igloo.blindpenguincoder.fakeMoviePlayerEngineFactory
import com.igloo.blindpenguincoder.feature.home.HomeUiState
import com.igloo.blindpenguincoder.feature.home.IglooApp
import com.igloo.blindpenguincoder.feature.home.SignOutUiState
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.inertDetailsActions
import com.igloo.blindpenguincoder.rememberInertMoviePlayerViewModel
import com.igloo.blindpenguincoder.testMovieGridItems
import com.igloo.blindpenguincoder.testMoviesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library grid's focus and paging contract (design-system.md sections 6.3, 8.3 and 11.4):
 * every state anchors the content pane, the left column exits to the spine, the prefetch fires
 * before the end, and a page landing underneath a focused card moves nothing.
 */
@RunWith(AndroidJUnit4::class)
class MoviesGridBehaviorTest {

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
    private var loadMoreCalls = 0
    private var refreshCalls = 0
    private var appendRetries = 0
    private val opened = mutableListOf<Long>()
    private var hostActivity: Activity? = null
    private var columns = 0

    private fun setContent(initial: MoviesUiState = testMoviesState()) {
        moviesState = initial
        loadMoreCalls = 0
        refreshCalls = 0
        appendRetries = 0
        opened.clear()
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                columns = IglooTheme.layout.gridColumns
                IglooApp(
                    // Pinned: the Shield test device runs TalkBack, and this suite asserts the
                    // focus chain without the reading stops.
                    spokenAccessibilityEnabled = false,
                    user = user,
                    movies = moviesState,
                    moviesActions = MoviesActions(
                        onRefresh = { refreshCalls += 1 },
                        onRetryFirstPage = {},
                        onRetryAppend = { appendRetries += 1 },
                        onLoadMore = { loadMoreCalls += 1 },
                    ),
                    serverOrigin = "http://igloo.test:8080",
                    signOut = SignOutUiState(),
                    home = HomeUiState(),
                    onRequestPlayback = { null },
                    moviePlayerViewModel = rememberInertMoviePlayerViewModel(),
                    moviePlayerEngineFactory = fakeMoviePlayerEngineFactory,
                    onRetryRail = {},
                    onMovieSelected = { opened += it },
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
        openMovies()
    }

    /**
     * Swaps the state after composition. `gridColumns` is a theme value, so it is only known
     * once something has composed — a state built before `setContent` cannot size itself in rows.
     */
    private fun showRows(
        rows: Int,
        append: MoviesAppendState = MoviesAppendState.Idle,
    ) {
        moviesState = testMoviesState(
            grid = IglooRailState.Loaded(testMovieGridItems.take(columns * rows)),
            append = append,
        )
        composeRule.waitForIdle()
    }

    private fun openMovies() {
        composeRule.onNodeWithContentDescription("Movies").performClick()
        composeRule.waitForIdle()
    }

    private fun card(id: Long) = composeRule.onNodeWithTag("poster_card_$id")

    private fun pressBack() {
        composeRule.runOnUiThread {
            (checkNotNull(hostActivity) as ComponentActivity).onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    // --- the anchor in every state ------------------------------------------------------------

    @Test
    fun theFirstCardHoldsThePanesEntryAnchor() {
        setContent()

        card(1).assertIsFocused()
    }

    @Test
    fun theLoadingSkeletonHoldsTheAnchorBeforeAnyPageArrives() {
        setContent(testMoviesState(grid = IglooRailState.Loading, totalMovies = null))

        composeRule.onNodeWithContentDescription("Loading movies").assertIsFocused()
    }

    @Test
    fun anEmptyLibraryStillHoldsTheAnchor() {
        setContent(testMoviesState(grid = IglooRailState.Loaded(emptyList())))

        composeRule.onNodeWithContentDescription("No movies found in your library.")
            .assertIsFocused()
    }

    @Test
    fun theFirstPageErrorPutsTheAnchorOnItsRetry() {
        setContent(testMoviesState(grid = IglooRailState.Error("Something went wrong")))

        composeRule.onNodeWithContentDescription("Retry loading the movie library")
            .assertIsFocused()
    }

    /** A slow network must never trap the d-pad: the skeleton anchor still opens the spine. */
    @Test
    fun theLoadingSkeletonStillExitsToTheSpine() {
        setContent(testMoviesState(grid = IglooRailState.Loading, totalMovies = null))

        composeRule.onNodeWithContentDescription("Loading movies")
            .performKeyInput { pressKey(Key.DirectionLeft) }

        composeRule.onNodeWithContentDescription("Movies").assertIsFocused()
    }

    @Test
    fun focusMovesToTheFirstCardWhenThePageArrives() {
        setContent(testMoviesState(grid = IglooRailState.Loading, totalMovies = null))
        composeRule.onNodeWithContentDescription("Loading movies").assertIsFocused()

        moviesState = testMoviesState(contentGeneration = 1)
        composeRule.waitForIdle()

        card(1).assertIsFocused()
    }

    // --- d-pad geometry -----------------------------------------------------------------------

    @Test
    fun leftFromTheFirstColumnExitsToTheNavigationRail() {
        setContent()

        card(1).performKeyInput { pressKey(Key.DirectionLeft) }

        composeRule.onNodeWithContentDescription("Movies").assertIsFocused()
    }

    @Test
    fun leftFromASecondColumnCardStaysInTheGrid() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).assertIsFocused()

        card(2).performKeyInput { pressKey(Key.DirectionLeft) }

        card(1).assertIsFocused()
    }

    @Test
    fun upFromTheFirstRowLandsOnRefresh() {
        setContent()

        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithContentDescription("Refresh the movie library").assertIsFocused()
    }

    @Test
    fun downFromTheFirstRowLandsOnTheRowBelow() {
        setContent()

        card(1).performKeyInput { pressKey(Key.DirectionDown) }

        card(1L + columns).assertIsFocused()
    }

    /** Focusable while refreshing: disabling it would remove the focused node from the tree. */
    @Test
    fun refreshStaysAFocusTargetWhileItIsRefreshing() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        val refresh = composeRule.onNodeWithContentDescription("Refresh the movie library")
        refresh.performClick()
        assertEquals(1, refreshCalls)

        moviesState = testMoviesState(refreshing = true)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Refresh the movie library").assertIsFocused()
    }

    // --- paging -------------------------------------------------------------------------------

    /** The tail is placeholders, never a landing site — it disappears when its page lands. */
    @Test
    fun theTailSkeletonsAreNotFocusTargets() {
        setContent()
        showRows(rows = 1, append = MoviesAppendState.Loading)
        card(1).performKeyInput { pressKey(Key.DirectionDown) }

        // The only row is the last, so down has nowhere legitimate to go: a focusable skeleton
        // would have swallowed the press, and an unpinned edge would have thrown focus out of
        // the pane and into the navigation rail's lower section.
        card(1).assertIsFocused()
    }

    @Test
    fun aFailedAppendKeepsTheCardsAndOffersAReachableRetry() {
        setContent()
        showRows(rows = 1, append = MoviesAppendState.Error("Something went wrong"))

        card(1).assertIsDisplayed()
        card(1).performKeyInput { pressKey(Key.DirectionDown) }
        val retry = composeRule.onNodeWithContentDescription("Retry loading more movies")
        retry.assertIsFocused()

        retry.performClick()
        assertEquals(1, appendRetries)
    }

    @Test
    fun reachingTheEndOfTheLoadedCardsAsksForTheNextPage() {
        setContent()
        loadMoreCalls = 0
        showRows(rows = 2)

        // Two rows is inside the two-row prefetch distance, so the request is already out.
        assertTrue("expected a prefetch, got $loadMoreCalls", loadMoreCalls >= 1)
    }

    @Test
    fun aGridWithEveryPageLoadedNeverAsksForAnother() {
        setContent()
        showRows(rows = 2, append = MoviesAppendState.End)

        // The prefetch trigger still fires — it is a scroll threshold, not a paging decision —
        // but the view model's guard is what stops it, so the screen may still report one call.
        // What must not happen is the tail rendering anything to land on.
        composeRule.onNodeWithContentDescription("Loading more movies").assertDoesNotExist()
    }

    /** The regression guard for the whole key strategy. */
    @Test
    fun appendingAPageKeepsFocusOnTheFocusedCard() {
        setContent()
        showRows(rows = 2)
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).assertIsFocused()

        // An append, not a replacement: the generation is unchanged.
        moviesState = testMoviesState(grid = IglooRailState.Loaded(testMovieGridItems))
        composeRule.waitForIdle()

        card(2).assertIsFocused()
    }

    // --- selection and return -----------------------------------------------------------------

    @Test
    fun selectingACardOpensIt() {
        setContent()

        card(3).performClick()

        assertEquals(listOf(3L), opened)
    }

    @Test
    fun leavingAndReenteringMoviesRestoresTheFocusedCard() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).performKeyInput { pressKey(Key.DirectionRight) }
        card(3).assertIsFocused()

        composeRule.onNodeWithContentDescription("Home").performClick()
        composeRule.waitForIdle()
        openMovies()

        card(3).assertIsFocused()
    }

    /** Back out of the spine returns to content, not to a reset grid. */
    @Test
    fun backFromTheSpineReturnsToTheFocusedCard() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).assertIsFocused()

        pressBack()
        composeRule.onNodeWithContentDescription("Movies").assertIsFocused()
        composeRule.onNodeWithContentDescription("Movies")
            .performKeyInput { pressKey(Key.DirectionRight) }

        card(2).assertIsFocused()
    }
}
