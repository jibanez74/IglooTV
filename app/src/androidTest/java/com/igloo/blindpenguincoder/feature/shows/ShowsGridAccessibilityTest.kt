package com.igloo.blindpenguincoder.feature.shows

import androidx.compose.runtime.getValue
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
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.igloo.blindpenguincoder.AnimationScaleRule
import com.igloo.blindpenguincoder.TestIglooApp
import com.igloo.blindpenguincoder.core.design.IglooTheme
import com.igloo.blindpenguincoder.core.ui.IglooRailState
import com.igloo.blindpenguincoder.feature.library.LibraryFilter
import com.igloo.blindpenguincoder.feature.library.LibraryKind
import com.igloo.blindpenguincoder.feature.library.LibraryTab
import com.igloo.blindpenguincoder.feature.library.LibraryUiState
import com.igloo.blindpenguincoder.feature.shared.AppendState
import com.igloo.blindpenguincoder.testLibraryState
import com.igloo.blindpenguincoder.testShowGridItems
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the TV Shows pane says out loud (design-system.md section 12): the "show" wording on
 * every announcement the Movies suite pins, and a card that names itself without promising an
 * action it cannot perform.
 */
@RunWith(AndroidJUnit4::class)
class ShowsGridAccessibilityTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var showsState by mutableStateOf(testLibraryState(LibraryKind.Shows))
    private var columns = 0

    private fun setContent(initial: LibraryUiState = testLibraryState(LibraryKind.Shows)) {
        showsState = initial
        composeRule.setContent {
            IglooTheme {
                columns = IglooTheme.layout.gridColumns
                TestIglooApp(shows = showsState)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("TV Shows").performClick()
        composeRule.waitForIdle()
    }

    private fun showOneRow(append: AppendState) {
        showsState = testLibraryState(
            kind = LibraryKind.Shows,
            grid = IglooRailState.Loaded(testShowGridItems.take(columns)),
            append = append,
        )
        composeRule.waitForIdle()
    }

    /** Inert until show details exist: the name and year, but no role and no "Open" action. */
    @Test
    fun eachCardIsOneStopWithoutAnAction() {
        setContent()

        composeRule.onNodeWithTag("show_card_1")
            .assertContentDescriptionEquals("Show 1, 2001")
            .assertHasNoClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
    }

    @Test
    fun theHeadingNamesThePane() {
        setContent()

        composeRule.onNodeWithTag("shows_count")
            .assertContentDescriptionEquals("Showing 40 of 96 shows")
        composeRule.onNodeWithContentDescription("Refresh the TV show library")
            .assertHasClickAction()
    }

    @Test
    fun theCountSaysSoWhileTheLibrarySizeIsUnknown() {
        setContent(testLibraryState(LibraryKind.Shows, grid = IglooRailState.Loading, total = null))

        composeRule.onNodeWithTag("shows_count")
            .assertContentDescriptionEquals("Loading the TV show library")
    }

    @Test
    fun thePersistentCountRegionAnnouncesThatMoreShowsAreComingPolitely() {
        setContent()
        showOneRow(AppendState.Loading)

        composeRule.onNodeWithTag("shows_count")
            .assertContentDescriptionEquals("Showing $columns of 96 shows. Loading more shows.")
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
            )
    }

    @Test
    fun aFailedPageOffersItsRetry() {
        setContent()
        showOneRow(AppendState.Error("Something went wrong"))

        composeRule.onNodeWithContentDescription("Retry loading more shows").assertHasClickAction()
    }

    @Test
    fun theFirstPageErrorNamesTheLibrary() {
        setContent(
            testLibraryState(LibraryKind.Shows, grid = IglooRailState.Error("Something went wrong")),
        )

        composeRule.onNodeWithContentDescription("Retry loading the TV show library")
            .assertIsFocused()
            .assertHasClickAction()
    }

    @Test
    fun refreshAnnouncesItsPendingStateWithoutLosingItsLabel() {
        setContent(testLibraryState(LibraryKind.Shows, refreshing = true))

        composeRule.onNodeWithContentDescription("Refresh the TV show library")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Refreshing"))
    }

    /** Two tabs, each one cleared node with a Tab role; there is no Liked tab to announce. */
    @Test
    fun eachTabAnnouncesItsNameRoleSelectionAndAction() {
        setContent()

        composeRule.onNodeWithTag("shows_tab_all")
            .assertContentDescriptionEquals("All shows")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(
                SemanticsMatcher("announces \"Show all shows\" as its click action") { node ->
                    node.config.getOrNull(SemanticsActions.OnClick)?.label == "Show all shows"
                },
            )
        composeRule.onNodeWithTag("shows_tab_genres")
            .assertContentDescriptionEquals("Genres")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        composeRule.onNodeWithTag("shows_tab_liked").assertDoesNotExist()
    }

    /** The chip speaks the count with the right noun: "2 shows", "1 show". */
    @Test
    fun eachGenreChipAnnouncesItsNameCountSelectionAndAction() {
        setContent(
            testLibraryState(
                kind = LibraryKind.Shows,
                tab = LibraryTab.Genres,
                genre = LibraryFilter.Genre(id = 7, tag = "Comedy"),
            ),
        )

        composeRule.onNodeWithTag("shows_genre_7")
            .assertContentDescriptionEquals("Comedy, 2 shows")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
            .assert(
                SemanticsMatcher("announces \"Show Comedy shows\" as its click action") { node ->
                    node.config.getOrNull(SemanticsActions.OnClick)?.label == "Show Comedy shows"
                },
            )
        composeRule.onNodeWithTag("shows_genre_9")
            .assertContentDescriptionEquals("Drama, 1 show")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun theCountSpeaksTheSelectedGenre() {
        setContent(
            testLibraryState(
                kind = LibraryKind.Shows,
                tab = LibraryTab.Genres,
                genre = LibraryFilter.Genre(id = 7, tag = "Comedy"),
                grid = IglooRailState.Loaded(testShowGridItems.take(2)),
                total = 2,
            ),
        )

        composeRule.onNodeWithTag("shows_count")
            .assertContentDescriptionEquals("Showing 2 of 2 Comedy shows")
    }

    @Test
    fun anEmptyGenreViewAnnouncesItself() {
        setContent(
            testLibraryState(
                kind = LibraryKind.Shows,
                tab = LibraryTab.Genres,
                genre = LibraryFilter.Genre(id = 7, tag = "Comedy"),
                grid = IglooRailState.Loaded(emptyList()),
            ),
        )

        composeRule.onNodeWithContentDescription("No Comedy shows in your library.")
            .assertIsFocused()
    }
}
