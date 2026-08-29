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
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.fakeMoviePlayerEngineFactory
import com.igloo.blindpenguincoder.feature.home.HomeUiState
import com.igloo.blindpenguincoder.feature.home.IglooApp
import com.igloo.blindpenguincoder.feature.home.SignOutUiState
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.inertDetailsActions
import com.igloo.blindpenguincoder.rememberInertMoviePlayerViewModel
import com.igloo.blindpenguincoder.testMovieDetails
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
    private var sortToggles = 0
    private val selectedFilters = mutableListOf<MoviesFilter>()
    private val opened = mutableListOf<Long>()
    private var hostActivity: Activity? = null
    private var columns = 0

    /** When true, selecting a card opens a real details overlay, as the app does. */
    private var openDetailsOnSelect = false
    private var detailsState by mutableStateOf(MovieDetailsUiState())

    private fun setContent(initial: MoviesUiState = testMoviesState()) {
        moviesState = initial
        loadMoreCalls = 0
        refreshCalls = 0
        appendRetries = 0
        sortToggles = 0
        selectedFilters.clear()
        opened.clear()
        detailsState = MovieDetailsUiState()
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
                        onSelectFilter = { selectedFilters += it },
                        onToggleSort = { sortToggles += 1 },
                    ),
                    serverOrigin = "http://igloo.test:8080",
                    signOut = SignOutUiState(),
                    home = HomeUiState(),
                    onRequestPlayback = { null },
                    moviePlayerViewModel = rememberInertMoviePlayerViewModel(),
                    moviePlayerEngineFactory = fakeMoviePlayerEngineFactory,
                    onRetryRail = {},
                    onMovieSelected = { movieId ->
                        opened += movieId
                        if (openDetailsOnSelect) {
                            detailsState = MovieDetailsUiState(
                                openMovieId = movieId,
                                details = MovieDetailsState.Loaded(testMovieDetails(id = movieId)),
                            )
                        }
                    },
                    onTheaterMovieSelected = null,
                    onCloseDetails = {
                        detailsState = detailsState.copy(
                            openMovieId = null,
                            details = MovieDetailsState.Loading,
                        )
                    },
                    details = detailsState,
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
        appendGeneration: Int = 0,
        refreshing: Boolean = false,
        notice: String? = null,
    ) {
        moviesState = testMoviesState(
            grid = IglooRailState.Loaded(testMovieGridItems.take(columns * rows)),
            append = append,
            appendGeneration = appendGeneration,
            refreshing = refreshing,
            notice = notice,
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

    @Test
    fun aFocusedSkeletonHandsFocusToTheFirstPageErrorRetry() {
        setContent(testMoviesState(grid = IglooRailState.Loading, totalMovies = null))
        composeRule.onNodeWithContentDescription("Loading movies").assertIsFocused()

        moviesState = testMoviesState(grid = IglooRailState.Error("Something went wrong"))
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Retry loading the movie library")
            .assertIsFocused()
    }

    @Test
    fun aFocusedSkeletonHandsFocusToAnEmptyResult() {
        setContent(testMoviesState(grid = IglooRailState.Loading, totalMovies = null))
        composeRule.onNodeWithContentDescription("Loading movies").assertIsFocused()

        moviesState = testMoviesState(
            grid = IglooRailState.Loaded(emptyList()),
            totalMovies = 0,
            contentGeneration = 1,
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("No movies found in your library.")
            .assertIsFocused()
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
    fun upFromTheFirstRowLandsOnTheSelectedFilterChip() {
        setContent()

        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("movies_filter_all").assertIsFocused()
    }

    // --- the filter row and header ------------------------------------------------------------

    @Test
    fun upFromTheFilterRowLandsOnRefresh() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("movies_filter_all")
            .performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithContentDescription("Refresh the movie library").assertIsFocused()
    }

    @Test
    fun downFromTheFilterRowLandsOnTheGridsEntryCell() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_filter_all").assertIsFocused()

        composeRule.onNodeWithTag("movies_filter_all")
            .performKeyInput { pressKey(Key.DirectionDown) }

        // The entry cell is the remembered card, not blindly the first.
        card(2).assertIsFocused()
    }

    @Test
    fun leftFromTheFirstChipExitsToTheNavigationRail() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("movies_filter_all")
            .performKeyInput { pressKey(Key.DirectionLeft) }

        composeRule.onNodeWithContentDescription("Movies").assertIsFocused()
    }

    @Test
    fun rightFromTheLastChipStaysPut() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_filter_all")
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("movies_filter_liked")
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("movies_filter_genre_7")
            .performKeyInput { pressKey(Key.DirectionRight) }
        val lastChip = composeRule.onNodeWithTag("movies_filter_genre_9")
        lastChip.assertIsFocused()

        lastChip.performKeyInput { pressKey(Key.DirectionRight) }

        lastChip.assertIsFocused()
    }

    @Test
    fun selectingAGenreChipReportsTheFilter() {
        setContent()

        composeRule.onNodeWithTag("movies_filter_genre_7").performClick()

        assertEquals(listOf<MoviesFilter>(MoviesFilter.Genre(id = 7, tag = "Action")), selectedFilters)
    }

    /** Focusable while the request is out, same contract as Refresh below. */
    @Test
    fun sortStaysAFocusTargetWhileTheOrderFlips() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_filter_all")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithContentDescription("Refresh the movie library")
            .performKeyInput { pressKey(Key.DirectionLeft) }
        val sort = composeRule.onNodeWithContentDescription("Sort order")
        sort.assertIsFocused()
        sort.performClick()
        assertEquals(1, sortToggles)

        moviesState = testMoviesState(sort = SortOrder.Descending)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Sort order").assertIsFocused()
    }

    @Test
    fun leftFromSortExitsToTheNavigationRail() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_filter_all")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithContentDescription("Refresh the movie library")
            .performKeyInput { pressKey(Key.DirectionLeft) }

        composeRule.onNodeWithContentDescription("Sort order")
            .performKeyInput { pressKey(Key.DirectionLeft) }

        composeRule.onNodeWithContentDescription("Movies").assertIsFocused()
    }

    @Test
    fun anEmptyLikedViewStillReachesTheFilterRow() {
        setContent(
            testMoviesState(
                filter = MoviesFilter.Liked,
                grid = IglooRailState.Loaded(emptyList()),
            ),
        )
        val empty = composeRule.onNodeWithContentDescription(
            "No liked movies yet. Like a movie from its details page and it will appear here.",
        )
        empty.assertIsFocused()

        empty.performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("movies_filter_liked").assertIsFocused()
    }

    /** The genres fetch resolves after first composition; the row growing must not move focus. */
    @Test
    fun genresArrivingLaterDoNotMoveFocus() {
        setContent(testMoviesState(genres = emptyList()))
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_filter_all").assertIsFocused()

        moviesState = testMoviesState()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("movies_filter_genre_7").assertIsDisplayed()
        composeRule.onNodeWithTag("movies_filter_all").assertIsFocused()
    }

    /** Pins the deliberate yank: a successful switch re-anchors on content, like Refresh. */
    @Test
    fun aFilterReplacementReanchorsFocusOnTheFirstCard() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).performKeyInput { pressKey(Key.DirectionRight) }
        card(3).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_filter_all").assertIsFocused()

        moviesState = testMoviesState(
            filter = MoviesFilter.Genre(id = 7, tag = "Action"),
            contentGeneration = 1,
        )
        composeRule.waitForIdle()

        card(1).assertIsFocused()
    }

    @Test
    fun aRefreshReplacementUsesTheFirstCardEvenWhenTheRememberedCardSurvives() {
        setContent()
        card(3).performClick()
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).performKeyInput { pressKey(Key.DirectionRight) }
        card(3).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_filter_all")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithContentDescription("Refresh the movie library").assertIsFocused()

        moviesState = testMoviesState(contentGeneration = 1)
        composeRule.waitForIdle()

        card(1).assertIsFocused()
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
        composeRule.onNodeWithTag("movies_filter_all")
            .performKeyInput { pressKey(Key.DirectionUp) }
        val refresh = composeRule.onNodeWithContentDescription("Refresh the movie library")
        refresh.assertIsFocused()
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
    fun retryFocusReturnsToThePreviousPosterAndStaysThereWhenTheAppendFinishes() {
        setContent()
        showRows(rows = 1, append = MoviesAppendState.Error("Something went wrong"))

        card(1).performKeyInput { pressKey(Key.DirectionDown) }
        val retry = composeRule.onNodeWithContentDescription("Retry loading more movies")
        retry.assertIsFocused()
        retry.performClick()

        showRows(rows = 1, append = MoviesAppendState.Loading)
        card(1).assertIsFocused()

        showRows(
            rows = 2,
            append = MoviesAppendState.Idle,
            appendGeneration = 1,
        )
        card(1).assertIsFocused()

        showRows(rows = 2, append = MoviesAppendState.Error("Still unavailable"))
        card(1).assertIsFocused()
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
    fun advancingAppendGenerationRearmsPrefetchWhenTheItemsDidNotChange() {
        setContent()
        loadMoreCalls = 0
        showRows(rows = 2)
        val callsAfterFirstPage = loadMoreCalls
        assertTrue("expected the initial prefetch", callsAfterFirstPage >= 1)

        showRows(rows = 2, appendGeneration = 1)

        assertTrue(
            "expected append generation to rearm prefetch",
            loadMoreCalls > callsAfterFirstPage,
        )
    }

    @Test
    fun prefetchPausesDuringRefreshAndRearmsWhenAFailedRefreshCompletes() {
        setContent()
        showRows(rows = 2, refreshing = true)
        loadMoreCalls = 0

        showRows(rows = 2, refreshing = true)
        assertEquals(0, loadMoreCalls)

        showRows(rows = 2, refreshing = false, notice = "Refresh failed")

        assertTrue("expected prefetch after refresh completion", loadMoreCalls >= 1)
    }

    @Test
    fun aGridWithEveryPageLoadedNeverAsksForAnother() {
        setContent()
        loadMoreCalls = 0
        showRows(rows = 2, append = MoviesAppendState.End)

        assertEquals(0, loadMoreCalls)
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

    @Test
    fun aReplacementThatLandsOutsideMoviesDoesNotReplayOnReentry() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).performKeyInput { pressKey(Key.DirectionRight) }
        card(3).assertIsFocused()

        composeRule.onNodeWithContentDescription("Home").performClick()
        composeRule.waitForIdle()
        moviesState = testMoviesState(contentGeneration = 1)
        composeRule.waitForIdle()
        openMovies()

        card(3).assertIsFocused()
    }

    @Test
    fun reconcileUnderDetailsDoesNotStealOverlayFocus() {
        openDetailsOnSelect = true
        setContent(
            testMoviesState(
                filter = MoviesFilter.Liked,
                grid = IglooRailState.Loaded(testMovieGridItems.take(3)),
                totalMovies = 3,
            ),
        )
        card(1).performClick()
        composeRule.onNodeWithTag("details_play").assertIsFocused()

        moviesState = testMoviesState(
            filter = MoviesFilter.Liked,
            grid = IglooRailState.Loaded(testMovieGridItems.drop(1).take(2)),
            totalMovies = 2,
            silentReconcileGeneration = 1,
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("details_play").assertIsFocused()
    }

    @Test
    fun reconcileAfterBackMovesFocusWhenTheRestoredCardDisappears() {
        openDetailsOnSelect = true
        setContent(
            testMoviesState(
                filter = MoviesFilter.Liked,
                grid = IglooRailState.Loaded(testMovieGridItems.take(3)),
                totalMovies = 3,
            ),
        )
        card(1).performClick()
        composeRule.onNodeWithTag("details_play").assertIsFocused()
        pressBack()
        card(1).assertIsFocused()

        moviesState = testMoviesState(
            filter = MoviesFilter.Liked,
            grid = IglooRailState.Loaded(testMovieGridItems.drop(1).take(2)),
            totalMovies = 2,
            silentReconcileGeneration = 1,
        )
        composeRule.waitForIdle()

        card(2).assertIsFocused()
    }

    @Test
    fun reconcileToAnEmptyGridHandsFocusToTheEmptyAnchor() {
        setContent(
            testMoviesState(
                filter = MoviesFilter.Liked,
                grid = IglooRailState.Loaded(testMovieGridItems.take(1)),
                totalMovies = 1,
            ),
        )
        card(1).assertIsFocused()

        moviesState = testMoviesState(
            filter = MoviesFilter.Liked,
            grid = IglooRailState.Loaded(emptyList()),
            totalMovies = 0,
            silentReconcileGeneration = 1,
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(
            "No liked movies yet. Like a movie from its details page and it will appear here.",
        ).assertIsFocused()
    }

    /**
     * The silent Liked reconcile can empty the grid under the open overlay — the card Back
     * would restore to is gone. Back must still land inside the pane, on the empty state,
     * never on the platform's fallback (the first rail row).
     */
    @Test
    fun backAfterTheGridEmptiedUnderTheOverlayLandsOnTheEmptyState() {
        openDetailsOnSelect = true
        setContent(
            testMoviesState(
                filter = MoviesFilter.Liked,
                grid = IglooRailState.Loaded(testMovieGridItems.take(1)),
                totalMovies = 1,
            ),
        )
        card(1).performClick()
        composeRule.onNodeWithTag("details_play").assertIsFocused()

        moviesState = testMoviesState(
            filter = MoviesFilter.Liked,
            grid = IglooRailState.Loaded(emptyList()),
            totalMovies = 0,
            silentReconcileGeneration = 1,
        )
        composeRule.waitForIdle()
        pressBack()

        composeRule.onNodeWithContentDescription(
            "No liked movies yet. Like a movie from its details page and it will appear here.",
        ).assertIsFocused()
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
