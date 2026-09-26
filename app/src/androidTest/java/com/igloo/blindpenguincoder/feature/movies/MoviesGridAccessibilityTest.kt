package com.igloo.blindpenguincoder.feature.movies

import androidx.compose.runtime.getValue
import com.igloo.blindpenguincoder.feature.shared.AppendState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.library.LibraryFilter
import com.igloo.blindpenguincoder.feature.library.LibraryTab
import com.igloo.blindpenguincoder.feature.library.LibraryUiState
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

    private var moviesState by mutableStateOf(testMoviesState())
    private var columns = 0

    private fun setContent(initial: LibraryUiState = testMoviesState()) {
        moviesState = initial
        composeRule.setContent {
            IglooTheme {
                columns = IglooTheme.layout.gridColumns
                TestIglooApp(
                    movies = moviesState,
                    onMovieSelected = {},
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
    private fun showOneRow(append: AppendState) {
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
    fun thePersistentCountRegionAnnouncesThatMoreMoviesAreComingPolitely() {
        setContent()
        showOneRow(AppendState.Loading)

        composeRule.onNodeWithTag("movies_count")
            .assertContentDescriptionEquals("Showing $columns of 96 movies. Loading more movies.")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    androidx.compose.ui.semantics.LiveRegionMode.Polite,
                ),
            )
    }

    @Test
    fun everyLoadingTailSkeletonIsHiddenFromAccessibility() {
        setContent()
        showOneRow(AppendState.Loading)

        repeat(columns * 2) { index ->
            composeRule.onNodeWithTag("movies_grid")
                .performScrollToNode(hasTestTag("tail_skeleton_$index"))
            composeRule.onNodeWithTag("tail_skeleton_$index", useUnmergedTree = true)
                .assert(
                    SemanticsMatcher.keyIsDefined(
                        SemanticsProperties.HideFromAccessibility,
                    ),
                )
        }
    }

    @Test
    fun idleAndEndTailsRemoveTheLoadingPhrase() {
        setContent()
        showOneRow(AppendState.Idle)
        composeRule.onNodeWithTag("movies_count")
            .assertContentDescriptionEquals("Showing $columns of 96 movies")

        showOneRow(AppendState.End)
        composeRule.onNodeWithTag("movies_count")
            .assertContentDescriptionEquals("Showing $columns of 96 movies")
    }

    @Test
    fun aFailedPageReportsPolitelyRatherThanInterrupting() {
        setContent()
        showOneRow(AppendState.Error("Something went wrong"))

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

    // --- the tab strip, the genre row, and the header ----------------------------------------

    /** One cleared node per tab, a Tab role, and `selected` only on the one that is. */
    @Test
    fun eachTabAnnouncesItsNameRoleAndSelection() {
        setContent()

        composeRule.onNodeWithTag("movies_tab_all")
            .assertContentDescriptionEquals("All movies")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertHasClickAction()

        // Never `selected = false`: TalkBack would append "not selected" to every other tab.
        composeRule.onNodeWithTag("movies_tab_genres")
            .assertContentDescriptionEquals("Genres")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            .assertHasClickAction()

        composeRule.onNodeWithTag("movies_tab_liked")
            .assertContentDescriptionEquals("Liked movies")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            .assertHasClickAction()
    }

    /** The press names what it does, not just what it is (section 12). */
    @Test
    fun eachTabNamesTheActionAPressPerforms() {
        setContent()

        composeRule.onNodeWithTag("movies_tab_liked").assert(
            SemanticsMatcher("announces \"Show liked movies\" as its click action") { node ->
                node.config.getOrNull(SemanticsActions.OnClick)?.label == "Show liked movies"
            },
        )
    }

    /** One cleared node per chip: the drawn "Action · 26" must not leak past the spoken form. */
    @Test
    fun eachGenreChipAnnouncesItsNameCountAndSelection() {
        setContent(
            testMoviesState(
                tab = LibraryTab.Genres,
                genre = LibraryFilter.Genre(id = 7, tag = "Action"),
            ),
        )

        composeRule.onNodeWithTag("movies_genre_7")
            .assertContentDescriptionEquals("Action, 26 movies")
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"),
            )

        // Only the selected chip carries the state; every other chip stays silent about it.
        composeRule.onNodeWithTag("movies_genre_9")
            .assertContentDescriptionEquals("Drama, 14 movies")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun theGenresTabWithoutGenresAnnouncesThePlaceholder() {
        setContent(testMoviesState(tab = LibraryTab.Genres, genre = null, genres = emptyList()))

        composeRule.onNodeWithContentDescription(
            "Genres aren't available right now. Refresh to try again.",
        ).assertIsFocused()
        composeRule.onNodeWithTag("movies_count")
            .assertContentDescriptionEquals("Genres unavailable")
    }

    /** A list still in flight is a wait, and must not be spoken as a list that is unavailable. */
    @Test
    fun theGenresTabWaitingOnItsListAnnouncesTheWait() {
        setContent(
            testMoviesState(
                tab = LibraryTab.Genres,
                genre = null,
                genres = emptyList(),
                genresLoaded = false,
            ),
        )

        composeRule.onNodeWithContentDescription("Loading genres").assertIsFocused()
        composeRule.onNodeWithTag("movies_count")
            .assertContentDescriptionEquals("Loading the genre list")
    }

    /** The spoken count names the active view; on Genres that is the genre, not "movies". */
    @Test
    fun theCountSpeaksTheSelectedGenre() {
        setContent(
            testMoviesState(
                tab = LibraryTab.Genres,
                genre = LibraryFilter.Genre(id = 7, tag = "Action"),
                grid = IglooRailState.Loaded(testMovieGridItems.take(3)),
                totalMovies = 26,
            ),
        )

        composeRule.onNodeWithTag("movies_count")
            .assertContentDescriptionEquals("Showing 3 of 26 Action movies")
    }

    /**
     * A refresh that failed with cards still on screen reports rather than interrupting: the
     * notice is a polite live region, not an alert over a grid that still works (section 12).
     */
    @Test
    fun aRefreshNoticeReportsPolitely() {
        setContent(testMoviesState(notice = "The server is unreachable."))

        composeRule.onNodeWithText("The server is unreachable.").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
    }

    @Test
    fun theSortButtonAnnouncesItsOrderAndItsAction() {
        setContent()

        composeRule.onNodeWithContentDescription("Sort order")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "A to Z"),
            )
            .assertHasClickAction()
    }

    /** The count region is what announces a filter change, so it must name the active view. */
    @Test
    fun theCountSpeaksTheActiveFilter() {
        setContent(
            testMoviesState(
                tab = LibraryTab.Liked,
                grid = IglooRailState.Loaded(testMovieGridItems.take(3)),
                totalMovies = 3,
            ),
        )

        composeRule.onNodeWithTag("movies_count")
            .assertContentDescriptionEquals("Showing 3 of 3 liked movies")
    }

    @Test
    fun anEmptyLikedViewAnnouncesItself() {
        setContent(
            testMoviesState(
                tab = LibraryTab.Liked,
                grid = IglooRailState.Loaded(emptyList()),
            ),
        )

        composeRule.onNodeWithContentDescription(
            "No liked movies yet. Like a movie from its details page and it will appear here.",
        ).assertIsFocused()
    }

    @Test
    fun anEmptyGenreViewAnnouncesItself() {
        setContent(
            testMoviesState(
                tab = LibraryTab.Genres,
                genre = LibraryFilter.Genre(id = 7, tag = "Action"),
                grid = IglooRailState.Loaded(emptyList()),
            ),
        )

        composeRule.onNodeWithContentDescription("No Action movies in your library.")
            .assertIsFocused()
    }
}
