package com.igloo.blindpenguincoder.feature.shows

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsFocused
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
import com.igloo.blindpenguincoder.feature.home.findActivity
import com.igloo.blindpenguincoder.feature.library.LibraryActions
import com.igloo.blindpenguincoder.feature.library.LibraryFilter
import com.igloo.blindpenguincoder.feature.library.LibraryTab
import com.igloo.blindpenguincoder.feature.library.LibraryUiState
import com.igloo.blindpenguincoder.testShowsState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the TV Shows pane does differently on the shared library screen (design-system.md
 * section 11.4): its own tags and wording, a two-tab strip, and cards that are inert until a
 * show details screen exists. The grid's geometry, paging and reconcile contracts are pinned
 * once, over Movies, in `MoviesGridBehaviorTest`.
 */
@RunWith(AndroidJUnit4::class)
class ShowsGridBehaviorTest {

    @get:Rule(order = 0)
    val animationScale = AnimationScaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private var showsState by mutableStateOf(testShowsState())
    private val selectedTabs = mutableListOf<LibraryTab>()
    private val selectedGenres = mutableListOf<LibraryFilter.Genre>()
    private var hostActivity: Activity? = null

    private fun setContent(initial: LibraryUiState = testShowsState()) {
        showsState = initial
        selectedTabs.clear()
        selectedGenres.clear()
        composeRule.setContent {
            val context = LocalContext.current
            SideEffect { hostActivity = context.findActivity() }
            IglooTheme {
                TestIglooApp(
                    shows = showsState,
                    showsActions = LibraryActions(
                        onRefresh = {},
                        onRetryFirstPage = {},
                        onRetryAppend = {},
                        onLoadMore = {},
                        onSelectTab = { selectedTabs += it },
                        onPressTab = {},
                        onSelectGenre = { selectedGenres += it },
                        onToggleSort = {},
                    ),
                )
            }
        }
        composeRule.waitForIdle()
        openShows()
    }

    private fun openShows() {
        composeRule.onNodeWithContentDescription("TV Shows").performClick()
        composeRule.waitForIdle()
    }

    private fun card(id: Long) = composeRule.onNodeWithTag("show_card_$id")

    private fun genresTabState() = testShowsState(
        tab = LibraryTab.Genres,
        genre = LibraryFilter.Genre(id = 9, tag = "Drama"),
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
    fun theLoadingSkeletonHoldsTheAnchorAndStillExitsToTheSpine() {
        setContent(testShowsState(grid = IglooRailState.Loading, total = null))

        val anchor = composeRule.onNodeWithContentDescription("Loading shows")
        anchor.assertIsFocused()

        anchor.performKeyInput { pressKey(Key.DirectionLeft) }

        composeRule.onNodeWithContentDescription("TV Shows").assertIsFocused()
    }

    @Test
    fun anEmptyLibraryStillHoldsTheAnchor() {
        setContent(testShowsState(grid = IglooRailState.Loaded(emptyList()), total = 0))

        composeRule.onNodeWithContentDescription("No shows found in your library.")
            .assertIsFocused()
    }

    @Test
    fun theFirstPageErrorPutsTheAnchorOnItsRetry() {
        setContent(testShowsState(grid = IglooRailState.Error("Something went wrong")))

        composeRule.onNodeWithContentDescription("Retry loading the TV show library")
            .assertIsFocused()
    }

    // --- d-pad geometry -----------------------------------------------------------------------

    @Test
    fun leftFromTheFirstColumnExitsToTheNavigationRail() {
        setContent()

        card(1).performKeyInput { pressKey(Key.DirectionLeft) }

        composeRule.onNodeWithContentDescription("TV Shows").assertIsFocused()
    }

    @Test
    fun upFromTheFirstRowLandsOnTheSelectedTab() {
        setContent()

        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("shows_tab_all").assertIsFocused()
    }

    @Test
    fun upFromTheFirstRowLandsOnTheSelectedGenreChip() {
        setContent(genresTabState())

        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("shows_genre_9").assertIsFocused()
    }

    /** No Liked tab: the backend keeps no show likes, so Genres is the strip's right edge. */
    @Test
    fun theStripHasTwoTabsAndItsRightEdgeIsPinned() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("shows_tab_liked").assertDoesNotExist()

        composeRule.onNodeWithTag("shows_tab_all")
            .performKeyInput { pressKey(Key.DirectionRight) }
        val lastTab = composeRule.onNodeWithTag("shows_tab_genres")
        lastTab.assertIsFocused()
        assertEquals(listOf(LibraryTab.Genres), selectedTabs)

        lastTab.performKeyInput { pressKey(Key.DirectionRight) }

        lastTab.assertIsFocused()
    }

    @Test
    fun upFromTheTabRowLandsOnRefresh() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithTag("shows_tab_all")
            .performKeyInput { pressKey(Key.DirectionUp) }

        composeRule.onNodeWithContentDescription("Refresh the TV show library").assertIsFocused()
    }

    @Test
    fun selectingAGenreChipReportsTheGenre() {
        setContent(genresTabState())

        composeRule.onNodeWithTag("shows_genre_7").performClick()

        assertEquals(listOf(LibraryFilter.Genre(id = 7, tag = "Comedy")), selectedGenres)
    }

    // --- inert cards --------------------------------------------------------------------------

    /**
     * There is no show details screen yet: a card is a focus target with no action, the same
     * contract as an empty rail (section 10), so pressing it opens nothing.
     */
    @Test
    fun aCardPressOpensNothing() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).assertIsFocused()

        card(2).assertHasNoClickAction()
        card(2).performKeyInput { pressKey(Key.DirectionCenter) }

        card(2).assertIsFocused()
        composeRule.onNodeWithTag("details_layer").assertDoesNotExist()
    }

    // --- the shell around the pane ------------------------------------------------------------

    @Test
    fun leavingForHomeAndReturningRestoresTheFocusedCard() {
        setContent()
        card(1).performKeyInput { pressKey(Key.DirectionRight) }
        card(2).assertIsFocused()

        composeRule.onNodeWithContentDescription("Home").performClick()
        composeRule.waitForIdle()
        openShows()

        card(2).assertIsFocused()
    }

    @Test
    fun backFromTheGridOpensTheRailOnTvShows() {
        setContent()
        card(1).assertIsFocused()

        pressBack()

        composeRule.onNodeWithContentDescription("TV Shows").assertIsFocused()
    }
}
