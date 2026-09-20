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
import androidx.compose.ui.test.assertIsNotFocused

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.data.model.SortOrder
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.testMovieDetails
import com.igloo.blindpenguincoder.testMovieGridItems
import com.igloo.blindpenguincoder.testMoviesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val NO_GENRES_MESSAGE = "Genres aren't available right now. Refresh to try again."

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

    private var moviesState by mutableStateOf(testMoviesState())
    private var loadMoreCalls = 0
    private var refreshCalls = 0
    private var appendRetries = 0
    private var sortToggles = 0
    private val selectedTabs = mutableListOf<MoviesTab>()
    private val pressedTabs = mutableListOf<MoviesTab>()
    private val selectedGenres = mutableListOf<MoviesFilter.Genre>()
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
        selectedTabs.clear()
        pressedTabs.clear()
        selectedGenres.clear()
        opened.clear()
        detailsState = MovieDetailsUiState()
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                columns = IglooTheme.layout.gridColumns
                TestIglooApp(
                    movies = moviesState,
                    moviesActions = MoviesActions(
                        onRefresh = { refreshCalls += 1 },
                        onRetryFirstPage = {},
                        onRetryAppend = { appendRetries += 1 },
                        onLoadMore = { loadMoreCalls += 1 },
                        onSelectTab = { selectedTabs += it },
                        onPressTab = { pressedTabs += it },
                        onSelectGenre = { selectedGenres += it },
                        onToggleSort = { sortToggles += 1 },
                    ),
                    onMovieSelected = { movieId ->
                        opened += movieId
                        if (openDetailsOnSelect) {
                            detailsState = MovieDetailsUiState(
                                openMovieId = movieId,
                                details = MovieDetailsState.Loaded(testMovieDetails(id = movieId)),
                            )
                        }
                    },
                    onCloseDetails = {
                        detailsState = detailsState.copy(
                            openMovieId = null,
                            details = MovieDetailsState.Loading,
                        )
                    },
                    details = detailsState,
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

    /** The Genres tab with Drama chosen: the second chip, so left and up both have somewhere to go. */
    private fun genresTabState(
        genre: MoviesFilter.Genre = MoviesFilter.Genre(id = 9, tag = "Drama"),
        contentGeneration: Int = 0,
    ) = testMoviesState(
        tab = MoviesTab.Genres,
        genre = genre,
        contentGeneration = contentGeneration,
    )

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
    fun upFromTheFirstRowLandsOnTheSelectedTab() {
        setContent()

        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("movies_tab_all").assertIsFocused()
    }

    @Test
    fun upFromTheFirstRowLandsOnTheSelectedGenreChip() {
        setContent(genresTabState())

        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("movies_genre_9").assertIsFocused()
    }

    /**
     * The grid is the panel's right edge. Unpinned, the search left the grid entirely and
     * resolved against the pane's siblings, landing back on the rows above.
     */
    @Test
    fun rightFromTheLastColumnStaysOnTheCard() {
        setContent()
        repeat(columns - 1) { index ->
            card(1L + index).performKeyInput { pressKey(Key.DirectionRight) }
        }
        val lastColumn = card(columns.toLong())
        lastColumn.assertIsFocused()

        lastColumn.performKeyInput { pressKey(Key.DirectionRight) }

        lastColumn.assertIsFocused()
        composeRule.onNodeWithTag("movies_tab_all").assertIsNotFocused()
    }

    /** A partial last row ends short of the last column, with unfocusable skeletons beside it. */
    @Test
    fun rightFromTheLastCardOfAPartialRowStaysPut() {
        setContent()
        moviesState = testMoviesState(
            grid = IglooRailState.Loaded(testMovieGridItems.take(columns + 1)),
        )
        composeRule.waitForIdle()
        card(1).performKeyInput { pressKey(Key.DirectionDown) }
        val lastCard = card(columns + 1L)
        lastCard.assertIsFocused()

        lastCard.performKeyInput { pressKey(Key.DirectionRight) }

        lastCard.assertIsFocused()
        composeRule.onNodeWithTag("movies_tab_all").assertIsNotFocused()
    }

    /** The cardless states carry the same right edge as the cards they stand in for. */
    @Test
    fun rightFromTheEmptyStateStaysPut() {
        setContent(
            testMoviesState(
                tab = MoviesTab.Liked,
                grid = IglooRailState.Loaded(emptyList()),
            ),
        )
        val empty = composeRule.onNodeWithContentDescription(
            "No liked movies yet. Like a movie from its details page and it will appear here.",
        )
        empty.assertIsFocused()

        empty.performKeyInput { pressKey(Key.DirectionRight) }

        empty.assertIsFocused()
    }

    // --- the tab strip, the genre row, and the header ----------------------------------------

    @Test
    fun upFromTheTabRowLandsOnRefresh() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("movies_tab_all")
            .performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithContentDescription("Refresh the movie library").assertIsFocused()
    }

    /**
     * Tabs select on focus, so Refresh's way down is wired to the selected tab: a spatial
     * search would land on whichever tab sits beneath the button and switch to it.
     */
    @Test
    fun downFromRefreshLandsOnTheSelectedTabWithoutSwitching() {
        setContent(testMoviesState(tab = MoviesTab.Liked))
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_tab_liked")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithContentDescription("Refresh the movie library").assertIsFocused()

        composeRule.onNodeWithContentDescription("Refresh the movie library")
            .performKeyInput { pressKey(Key.DirectionDown) }

        composeRule.onNodeWithTag("movies_tab_liked").assertIsFocused()
        assertEquals(emptyList<MoviesTab>(), selectedTabs)
    }

    /** Sort sits over the strip too, so its `down` needs the same wiring Refresh's test pins. */
    @Test
    fun downFromSortLandsOnTheSelectedTabWithoutSwitching() {
        setContent(testMoviesState(tab = MoviesTab.Liked))
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_tab_liked")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithContentDescription("Refresh the movie library")
            .performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithContentDescription("Sort order").assertIsFocused()

        composeRule.onNodeWithContentDescription("Sort order")
            .performKeyInput { pressKey(Key.DirectionDown) }

        composeRule.onNodeWithTag("movies_tab_liked").assertIsFocused()
        assertEquals(emptyList<MoviesTab>(), selectedTabs)
    }

    @Test
    fun downFromTheTabRowLandsOnTheGridsEntryCell() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_tab_all").assertIsFocused()

        composeRule.onNodeWithTag("movies_tab_all")
            .performKeyInput { pressKey(Key.DirectionDown) }

        // The entry cell is the remembered card, not blindly the first.
        card(2).assertIsFocused()
    }

    @Test
    fun downFromTheTabRowLandsOnTheGenreRowOnTheGenresTab() {
        setContent(genresTabState())
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_genre_9")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_tab_genres").assertIsFocused()

        composeRule.onNodeWithTag("movies_tab_genres")
            .performKeyInput { pressKey(Key.DirectionDown) }

        composeRule.onNodeWithTag("movies_genre_9").assertIsFocused()
    }

    @Test
    fun downFromTheGenreRowLandsOnTheGridsEntryCell() {
        setContent(genresTabState())
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_genre_9").assertIsFocused()

        composeRule.onNodeWithTag("movies_genre_9")
            .performKeyInput { pressKey(Key.DirectionDown) }

        card(2).assertIsFocused()
    }

    @Test
    fun leftFromTheAllTabExitsToTheNavigationRail() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("movies_tab_all")
            .performKeyInput { pressKey(Key.DirectionLeft) }

        composeRule.onNodeWithContentDescription("Movies").assertIsFocused()
    }

    @Test
    fun leftFromTheFirstGenreChipExitsToTheNavigationRail() {
        setContent(genresTabState(genre = MoviesFilter.Genre(id = 7, tag = "Action")))
        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("movies_genre_7")
            .performKeyInput { pressKey(Key.DirectionLeft) }

        composeRule.onNodeWithContentDescription("Movies").assertIsFocused()
    }

    @Test
    fun rightFromTheLikedTabStaysPut() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_tab_all")
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("movies_tab_genres")
            .performKeyInput { pressKey(Key.DirectionRight) }
        val lastTab = composeRule.onNodeWithTag("movies_tab_liked")
        lastTab.assertIsFocused()

        lastTab.performKeyInput { pressKey(Key.DirectionRight) }

        lastTab.assertIsFocused()
    }

    @Test
    fun rightFromTheLastGenreChipStaysPut() {
        setContent(genresTabState())
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        val lastChip = composeRule.onNodeWithTag("movies_genre_9")
        lastChip.assertIsFocused()

        lastChip.performKeyInput { pressKey(Key.DirectionRight) }

        lastChip.assertIsFocused()
    }

    /** Focus is the switch: landing on a tab reports it, and focus stays there. */
    @Test
    fun focusingATabReportsTheTab() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        // The selected tab taking focus is not a switch.
        assertEquals(emptyList<MoviesTab>(), selectedTabs)

        composeRule.onNodeWithTag("movies_tab_all")
            .performKeyInput { pressKey(Key.DirectionRight) }

        assertEquals(listOf(MoviesTab.Genres), selectedTabs)
        composeRule.onNodeWithTag("movies_tab_genres").assertIsFocused()
    }

    /**
     * A press on a focused-but-unselected tab is the retry after a failed switch reverted it.
     * It reports as a press, not a second focus landing: the view model debounces the landing
     * and a deliberate press must not wait behind it.
     */
    @Test
    fun pressingAnUnselectedTabReportsAPress() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_tab_all")
            .performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(listOf(MoviesTab.Genres), selectedTabs)

        composeRule.onNodeWithTag("movies_tab_genres").performClick()

        assertEquals(listOf(MoviesTab.Genres), selectedTabs)
        assertEquals(listOf(MoviesTab.Genres), pressedTabs)
    }

    /**
     * The revert leaves a tab focused that the strip no longer highlights, and moves the grid's
     * wired `up` edge onto the tab it snapped back to. Both have to keep working.
     */
    @Test
    fun aRevertedTabStaysFocusedAndTheGridStillReachesTheStrip() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_tab_all")
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("movies_tab_genres").assertIsFocused()

        // The switch failed: the grid never moved, so the selection snapped back to All.
        moviesState = testMoviesState(notice = "The server is unreachable.")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("movies_tab_genres").assertIsFocused()
        composeRule.onNodeWithTag("movies_tab_genres")
            .performKeyInput { pressKey(Key.DirectionDown) }
        card(1).assertIsFocused()

        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("movies_tab_all").assertIsFocused()
    }

    /**
     * The one replacement that must not re-anchor: the tab the user is standing on caused it.
     * Pulling focus into the grid would make the strip impossible to traverse.
     */
    @Test
    fun aTabSwitchLandingKeepsFocusOnTheTab() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_tab_all")
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("movies_tab_genres")
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("movies_tab_liked").assertIsFocused()

        moviesState = testMoviesState(
            tab = MoviesTab.Liked,
            grid = IglooRailState.Loaded(testMovieGridItems.drop(10).take(6)),
            totalMovies = 6,
            contentGeneration = 1,
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("movies_tab_liked").assertIsFocused()
        // The grid is at the top, so the next press down lands on its first card.
        composeRule.onNodeWithTag("movies_tab_liked")
            .performKeyInput { pressKey(Key.DirectionDown) }
        card(11).assertIsFocused()
    }

    @Test
    fun aTabSwitchLandingOnAnEmptyViewKeepsFocusOnTheTab() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_tab_all")
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("movies_tab_genres")
            .performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("movies_tab_liked").assertIsFocused()

        moviesState = testMoviesState(
            tab = MoviesTab.Liked,
            grid = IglooRailState.Loaded(emptyList()),
            totalMovies = 0,
            contentGeneration = 1,
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("movies_tab_liked").assertIsFocused()
    }

    @Test
    fun selectingAGenreChipReportsTheGenre() {
        setContent(genresTabState())

        composeRule.onNodeWithTag("movies_genre_7").performClick()

        assertEquals(listOf(MoviesFilter.Genre(id = 7, tag = "Action")), selectedGenres)
    }

    /** Pins the deliberate yank: a chip press re-anchors on content, like Refresh. */
    @Test
    fun aGenreChipPressReanchorsFocusOnTheFirstCard() {
        setContent(genresTabState())
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).performKeyInput { pressKey(Key.DirectionRight) }
        card(3).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_genre_9")
            .performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithTag("movies_genre_7").assertIsFocused()

        moviesState = genresTabState(
            genre = MoviesFilter.Genre(id = 7, tag = "Action"),
            contentGeneration = 1,
        )
        composeRule.waitForIdle()

        card(1).assertIsFocused()
    }

    /** The genres fetch resolves after first composition; the list landing must not move focus. */
    @Test
    fun genresArrivingLaterDoNotMoveFocus() {
        setContent(testMoviesState(genres = emptyList()))
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_tab_all").assertIsFocused()

        moviesState = testMoviesState()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("movies_tab_all").assertIsFocused()
        composeRule.onNodeWithTag("movies_genre_row").assertDoesNotExist()
    }

    @Test
    fun theGenresTabWithoutGenresShowsAPlaceholderThatTakesFocus() {
        setContent(testMoviesState(tab = MoviesTab.Genres, genre = null, genres = emptyList()))

        val placeholder = composeRule.onNodeWithContentDescription(NO_GENRES_MESSAGE)
        placeholder.assertIsFocused()
        composeRule.onNodeWithTag("movies_genre_row").assertDoesNotExist()

        placeholder.performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("movies_tab_genres").assertIsFocused()
    }

    @Test
    fun genresLandingOnThePlaceholderHandOffToTheFirstCard() {
        setContent(testMoviesState(tab = MoviesTab.Genres, genre = null, genres = emptyList()))
        composeRule.onNodeWithContentDescription(NO_GENRES_MESSAGE).assertIsFocused()

        moviesState = genresTabState(contentGeneration = 1)
        composeRule.waitForIdle()

        card(1).assertIsFocused()
        composeRule.onNodeWithTag("movies_genre_row").assertIsDisplayed()
    }

    /**
     * The strip renders outside the content `when`, so no grid state can take it away — an empty
     * Liked view or a failed first page must still let the user switch sections.
     */
    @Test
    fun theTabStripRendersInEveryGridState() {
        setContent(testMoviesState(grid = IglooRailState.Loading))
        composeRule.onNodeWithTag("movies_tabs").assertIsDisplayed()

        moviesState = testMoviesState(grid = IglooRailState.Error("The server is unreachable."))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("movies_tabs").assertIsDisplayed()

        moviesState = testMoviesState(
            tab = MoviesTab.Liked,
            grid = IglooRailState.Loaded(emptyList()),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("movies_tabs").assertIsDisplayed()
    }

    /** The remembered genre comes back selected, still carrying the grid's `up` edge. */
    @Test
    fun theGenrePickerReturnsWithTheRememberedChipAnchored() {
        setContent(genresTabState())
        composeRule.onNodeWithTag("movies_genre_9").assertIsDisplayed()

        moviesState = testMoviesState(tab = MoviesTab.All, contentGeneration = 1)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("movies_genre_row").assertDoesNotExist()

        moviesState = genresTabState(contentGeneration = 2)
        composeRule.waitForIdle()

        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_genre_9").assertIsFocused()
    }

    /**
     * A refreshed list can drop the selected genre before the view model has re-resolved it. The
     * anchor falls back to the first chip so the grid's wired `up` edge always resolves.
     */
    @Test
    fun theGenreAnchorFallsBackToTheFirstChip() {
        setContent(genresTabState(genre = MoviesFilter.Genre(id = 99, tag = "Gone")))

        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("movies_genre_7").assertIsFocused()
    }

    /** The Genres tab before its list has settled is a wait, not a failure. */
    @Test
    fun theGenresTabWaitingOnItsListShowsTheSkeletonAnchor() {
        setContent(
            testMoviesState(
                tab = MoviesTab.Genres,
                genre = null,
                genres = emptyList(),
                genresLoaded = false,
            ),
        )

        composeRule.onNodeWithContentDescription(NO_GENRES_MESSAGE).assertDoesNotExist()
        val anchor = composeRule.onNodeWithContentDescription("Loading genres")
        anchor.assertIsFocused()

        anchor.performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("movies_tab_genres").assertIsFocused()
    }

    @Test
    fun genresLandingExposesTheRetainedGridAndKeepsFocusThereAfterPageFailure() {
        setContent(
            testMoviesState(
                tab = MoviesTab.Genres,
                genre = null,
                genres = emptyList(),
                genresLoaded = false,
            ),
        )
        composeRule.onNodeWithContentDescription("Loading genres").assertIsFocused()

        moviesState = genresTabState()
        composeRule.waitForIdle()

        card(1).assertIsFocused()

        moviesState = testMoviesState(notice = "The server is unreachable.")
        composeRule.waitForIdle()

        card(1).assertIsFocused()
    }

    @Test
    fun anEmptyLikedViewStillReachesTheTabRow() {
        setContent(
            testMoviesState(
                tab = MoviesTab.Liked,
                grid = IglooRailState.Loaded(emptyList()),
            ),
        )
        val empty = composeRule.onNodeWithContentDescription(
            "No liked movies yet. Like a movie from its details page and it will appear here.",
        )
        empty.assertIsFocused()

        empty.performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("movies_tab_liked").assertIsFocused()
    }

    /** Focusable while the request is out, same contract as Refresh below. */
    @Test
    fun sortStaysAFocusTargetWhileTheOrderFlips() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_tab_all")
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
        composeRule.onNodeWithTag("movies_tab_all")
            .performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithContentDescription("Refresh the movie library")
            .performKeyInput { pressKey(Key.DirectionLeft) }

        composeRule.onNodeWithContentDescription("Sort order")
            .performKeyInput { pressKey(Key.DirectionLeft) }

        composeRule.onNodeWithContentDescription("Movies").assertIsFocused()
    }

    @Test
    fun aRefreshReplacementUsesTheFirstCardEvenWhenTheRememberedCardSurvives() {
        setContent()
        card(3).performClick()
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).performKeyInput { pressKey(Key.DirectionRight) }
        card(3).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("movies_tab_all")
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
        composeRule.onNodeWithTag("movies_tab_all")
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
                tab = MoviesTab.Liked,
                grid = IglooRailState.Loaded(testMovieGridItems.take(3)),
                totalMovies = 3,
            ),
        )
        card(1).performClick()
        composeRule.onNodeWithTag("details_play").assertIsFocused()

        moviesState = testMoviesState(
            tab = MoviesTab.Liked,
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
                tab = MoviesTab.Liked,
                grid = IglooRailState.Loaded(testMovieGridItems.take(3)),
                totalMovies = 3,
            ),
        )
        card(1).performClick()
        composeRule.onNodeWithTag("details_play").assertIsFocused()
        pressBack()
        card(1).assertIsFocused()

        moviesState = testMoviesState(
            tab = MoviesTab.Liked,
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
                tab = MoviesTab.Liked,
                grid = IglooRailState.Loaded(testMovieGridItems.take(1)),
                totalMovies = 1,
            ),
        )
        card(1).assertIsFocused()

        moviesState = testMoviesState(
            tab = MoviesTab.Liked,
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
                tab = MoviesTab.Liked,
                grid = IglooRailState.Loaded(testMovieGridItems.take(1)),
                totalMovies = 1,
            ),
        )
        card(1).performClick()
        composeRule.onNodeWithTag("details_play").assertIsFocused()

        moviesState = testMoviesState(
            tab = MoviesTab.Liked,
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
